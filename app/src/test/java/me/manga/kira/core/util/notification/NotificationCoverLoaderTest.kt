package me.manga.kira.core.util.notification

import android.app.Application
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCoverLoaderTest {
    @Test
    fun collector_acceptsExactLimitAndReadsOnlyOneOverflowSentinel() =
        runBlocking {
            val exact = CountingBody(NotificationCoverLimits.BODY_BYTES)
            assertEquals(NotificationCoverLimits.BODY_BYTES, readCoverBytes(exact)?.size)
            assertEquals(NotificationCoverLimits.BODY_BYTES, exact.consumed)
            // The collector ignores metadata; a misleading short length cannot bypass its boundary.
            val overflow = CountingBody(NotificationCoverLimits.BODY_BYTES + 17)
            assertNull(readCoverBytes(overflow))
            assertEquals(NotificationCoverLimits.BODY_BYTES + 1, overflow.consumed)
        }

    @Test
    fun wireBodies_declaredUnknownChunkedGzipAndMalformed_fallBackWithoutPixels() =
        runBlocking {
            val responses = invalidWireResponses()
            responses.forEachIndexed { index, response ->
                val bounds = AtomicInteger()
                val decoder =
                    NotificationCoverDecoder { bytes, offset, size, options ->
                        assertTrue(options.inJustDecodeBounds)
                        bounds.incrementAndGet()
                        BitmapFactory.decodeByteArray(bytes, offset, size, options)
                    }
                NotificationCoverServer { _, socket -> response(socket) }.use { server ->
                    var posted = false
                    NotificationCoverLoader(decoder = decoder).withCover(server.url(), { true }) {
                        posted = true
                        assertNull(it)
                    }
                    assertTrue(posted)
                    assertEquals(1, server.requests.size)
                    assertEquals(if (index == responses.lastIndex) 1 else 0, bounds.get())
                }
            }
        }

    @Test
    fun relativeRedirectLoadsOnce_andInvalidOrExcessRedirectsNeverFollowForbiddenTargets() =
        runBlocking {
            assertRelativeRedirect()
            assertRejectedRedirects()
        }

    @Test
    fun initialCredentialsSchemesAndHttpsDowngrade_areRefusedBeforeDestinationCalls() =
        runBlocking {
            val calls = AtomicInteger()
            val loader = NotificationCoverLoader(downgradeCalls(calls))
            listOf("file:///image", "data:image/png,anything", "https://name:secret@example.test/image")
                .forEach { url -> loader.withCover(url, { true }) { assertNull(it) } }
            assertEquals(0, calls.get())
            // A controlled HTTPS response oracle, not a TLS-server or physical network-policy test.
            loader.withCover("https://example.test/image", { true }) { assertNull(it) }
            assertEquals(1, calls.get())
            assertNull(redirect("https://example.test/image".toHttpUrl(), "http://example.test/image"))
            assertNotNull(redirect("https://example.test/image".toHttpUrl(), "/next"))
        }

    @Test
    fun cancellationAfterHeaders_closesBodyAndJoinsBeforeReturn_thenFollowingCoverSucceeds() =
        runBlocking {
            val posts = AtomicInteger()
            val pixels = AtomicInteger()
            NotificationCoverStall().use { stall ->
                val worker =
                    launch(Dispatchers.Default) {
                        NotificationCoverLoader(stall.client, countingPixels(pixels))
                            .withCover(stall.server.url(), { true }) { posts.incrementAndGet() }
                    }
                try {
                    withContext(Dispatchers.IO) { stall.awaitReading() }
                    withTimeout(5_000) { worker.cancelAndJoin() }
                    withTimeout(5_000) { stall.awaitClosed() }
                    assertTrue(worker.isCompleted)
                    assertTrue(worker.isCancelled)
                    assertEquals(0, posts.get())
                    assertEquals(0, pixels.get())
                } finally {
                    withContext(NonCancellable) { worker.cancelAndJoin() }
                }
            }
            NotificationCoverLoader(notificationCoverCalls()).withCover("https://example.test/next", { true }) {
                assertNotNull(it)
            }
        }

    @Test
    fun ownBudgetDuringBodyRead_postsTextOnlyAfterTheCallHasActuallyClosed() =
        runTest {
            NotificationCoverStall().use { stall ->
                var posts = 0
                val worker =
                    launch {
                        NotificationCoverLoader(stall.client).withCover(stall.server.url(), { true }) {
                            assertTrue("fallback must await the cancelled blocking read", stall.failed.isCompleted)
                            assertNull(it)
                            posts++
                        }
                    }
                try {
                    runCurrent()
                    stall.awaitReading()
                    advanceTimeBy(NotificationCoverLimits.BUDGET_MILLIS + 1)
                    runCurrent()
                    worker.join()
                    assertEquals(1, posts)
                    assertTrue(stall.failed.isCompleted)
                } finally {
                    withContext(NonCancellable) { worker.cancelAndJoin() }
                }
            }
        }
}

private fun countingPixels(pixels: AtomicInteger) =
    NotificationCoverDecoder { bytes, offset, size, options ->
        if (!options.inJustDecodeBounds) pixels.incrementAndGet()
        BitmapFactory.decodeByteArray(bytes, offset, size, options)
    }

private fun invalidWireResponses(): List<(Socket) -> Unit> {
    val oversized = ByteArray(NotificationCoverLimits.BODY_BYTES + 1)
    val gzip =
        ByteArrayOutputStream()
            .also { out -> GZIPOutputStream(out).use { it.write(oversized) } }
            .toByteArray()
    return listOf(
        { it.writeCoverResponse(byteArrayOf(), headers = listOf("Content-Length: ${oversized.size}")) },
        { it.writeCoverResponse(oversized, headers = listOf("Content-Type: image/png")) },
        { it.writeChunkedCover(oversized) },
        { it.writeCoverResponse(gzip, headers = listOf("Content-Encoding: gzip", "Content-Length: ${gzip.size}")) },
        { it.writeCoverResponse("<html>not raster</html>".toByteArray()) },
    )
}

private suspend fun assertRelativeRedirect() {
    NotificationCoverServer { path, socket ->
        if (path == "/start") {
            socket.writeCoverResponse(byteArrayOf(), "302 Found", listOf("Location: /cover"))
        } else {
            socket.writeCoverResponse()
        }
    }.use { server ->
        NotificationCoverLoader().withCover(server.url("/start"), { true }) { assertNotNull(it) }
        assertEquals(listOf("/start", "/cover"), server.requests.toList())
    }
}

private suspend fun assertRejectedRedirects() {
    listOf("ftp://127.0.0.1/image", "http://user:password@127.0.0.1/image", "", null, "/loop")
        .forEach { location ->
            NotificationCoverServer { _, socket ->
                val headers = listOfNotNull(location?.let { "Location: $it" })
                socket.writeCoverResponse(byteArrayOf(), "302 Found", headers)
            }.use { server ->
                NotificationCoverLoader().withCover(server.url("/loop"), { true }) { assertNull(it) }
                assertEquals(if (location == "/loop") 4 else 1, server.requests.size)
            }
        }
}

private fun downgradeCalls(calls: AtomicInteger) =
    OkHttpClient
        .Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            calls.incrementAndGet()
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "http://127.0.0.1/forbidden")
                .body(byteArrayOf().toResponseBody())
                .build()
        }.build()

private class CountingBody(
    size: Int,
) : ByteArrayInputStream(ByteArray(size)) {
    var consumed = 0

    override fun read(): Int = super.read().also { if (it != -1) consumed++ }

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int = super.read(bytes, offset, length).also { if (it > 0) consumed += it }
}

private fun Socket.writeChunkedCover(body: ByteArray) {
    val output = getOutputStream()
    output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
    output.write("${body.size.toString(16)}\r\n".toByteArray())
    output.write(body)
    output.write("\r\n0\r\n\r\n".toByteArray())
    output.flush()
}
