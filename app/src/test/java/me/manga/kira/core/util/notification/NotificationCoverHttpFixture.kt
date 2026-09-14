package me.manga.kira.core.util.notification

import kotlinx.coroutines.CompletableDeferred
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.concurrent.thread

/** Generates compressible raster bytes row-by-row, never a full-size fixture bitmap. */
internal fun notificationPng(
    width: Int = 32,
    height: Int = 16,
    rows: Int = height,
): ByteArray {
    val compressed = ByteArrayOutputStream()
    DeflaterOutputStream(compressed).use { deflater ->
        val row = ByteArray(1 + width * 4)
        repeat(rows) { deflater.write(row) }
    }
    val header = ByteArrayOutputStream()
    DataOutputStream(header).use {
        it.writeInt(width)
        it.writeInt(height)
        it.write(byteArrayOf(8, 6, 0, 0, 0))
    }
    val png = ByteArrayOutputStream()
    DataOutputStream(png).use {
        it.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        it.pngChunk("IHDR", header.toByteArray())
        it.pngChunk("IDAT", compressed.toByteArray())
        it.pngChunk("IEND", byteArrayOf())
    }
    return png.toByteArray()
}

private fun DataOutputStream.pngChunk(
    type: String,
    bytes: ByteArray,
) {
    val tag = type.toByteArray(Charsets.US_ASCII)
    writeInt(bytes.size)
    write(tag)
    write(bytes)
    val crc =
        CRC32().apply {
            update(tag)
            update(bytes)
        }
    writeInt(crc.value.toInt())
}

/** Terminal in-memory transport oracle, not a claim about wire framing or Android network policy. */
internal fun notificationCoverCalls(
    bytes: ByteArray = notificationPng(),
    requests: AtomicInteger = AtomicInteger(),
): Call.Factory =
    OkHttpClient
        .Builder()
        .addInterceptor { chain ->
            requests.incrementAndGet()
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bytes.toResponseBody("image/png".toMediaType()))
                .build()
        }.build()

/** Test-owned loopback sockets only. Does not change the app's cleartext/TLS policy. */
internal class NotificationCoverServer(
    private val respond: (String, Socket) -> Unit,
) : AutoCloseable {
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentLinkedQueue<Socket>()
    private val failures = ConcurrentLinkedQueue<Throwable>()
    private val handlers = Executors.newFixedThreadPool(4)
    val requests = ConcurrentLinkedQueue<String>()
    private val acceptor =
        thread(name = "notification-cover-http", isDaemon = true) {
            while (!listener.isClosed) {
                try {
                    val socket = listener.accept()
                    sockets.add(socket)
                    handlers.execute { handle(socket) }
                } catch (failure: IOException) {
                    if (!listener.isClosed) failures.add(failure)
                }
            }
        }

    fun url(path: String = "/cover") = "http://127.0.0.1:${listener.localPort}$path"

    private fun handle(socket: Socket) {
        try {
            socket.use {
                val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                val path = checkNotNull(reader.readLine()).split(' ')[1]
                while (!reader.readLine().isNullOrEmpty()) Unit
                requests.add(path)
                respond(path, it)
            }
        } catch (_: IOException) {
            // The denial/cancellation cases deliberately close an incomplete response.
        } catch (failure: Throwable) {
            failures.add(failure)
        } finally {
            sockets.remove(socket)
        }
    }

    override fun close() {
        listener.close()
        acceptor.join(5_000)
        sockets.forEach { it.close() }
        handlers.shutdownNow()
        check(!acceptor.isAlive && handlers.awaitTermination(5, TimeUnit.SECONDS))
        check(failures.isEmpty()) { "Loopback fixture failed: ${failures.firstOrNull()}" }
    }
}

/** A real HTTP body held open after headers, with client and server completion witnesses. */
internal class NotificationCoverStall : AutoCloseable {
    private val reading = CountDownLatch(1)
    val disconnected = CompletableDeferred<Unit>()
    val failed = CompletableDeferred<Unit>()
    val client =
        OkHttpClient
            .Builder()
            .eventListener(
                object : EventListener() {
                    override fun responseBodyStart(call: Call) {
                        reading.countDown()
                    }

                    override fun callFailed(
                        call: Call,
                        ioe: IOException,
                    ) {
                        failed.complete(Unit)
                    }
                },
            ).build()
    val server =
        NotificationCoverServer { _, socket ->
            socket.writeCoverResponse(byteArrayOf(0), headers = listOf("Content-Length: 100000"))
            try {
                while (socket.getInputStream().read() != -1) Unit
            } finally {
                disconnected.complete(Unit)
            }
        }

    // Intentionally blocks a virtual-time test thread until real IO reaches the desired seam.
    fun awaitReading() = check(reading.await(5, TimeUnit.SECONDS))

    suspend fun awaitClosed() {
        disconnected.await()
        failed.await()
    }

    override fun close() {
        try {
            server.close()
        } finally {
            client.connectionPool.evictAll()
        }
    }
}

internal fun Socket.writeCoverResponse(
    body: ByteArray = notificationPng(),
    status: String = "200 OK",
    headers: List<String> = listOf("Content-Type: image/png", "Content-Length: ${body.size}"),
) {
    val start = listOf("HTTP/1.1 $status", "Connection: close") + headers
    getOutputStream().write((start.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.US_ASCII))
    getOutputStream().write(body)
    getOutputStream().flush()
}
