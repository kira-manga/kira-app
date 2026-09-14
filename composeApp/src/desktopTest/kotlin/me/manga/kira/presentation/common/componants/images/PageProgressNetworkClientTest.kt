package me.manga.kira.presentation.common.componants.images

import coil3.Extras
import coil3.network.NetworkClient
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.PageProgressAttempt
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.ForwardingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

private const val FINAL_FRACTION_MIN = 0.99f
private const val EXPECTED_UNKNOWN_LENGTH_TICKS = 3
private const val FIXTURE_BODY_BYTES = 16 * 1024 + 17
private const val FIXTURE_CHUNK_BYTES = 1024
private const val FIXTURE_PATTERN_MODULUS = 251

@OptIn(ExperimentalTime::class)
class PageProgressNetworkClientTest {
    @Test
    fun taggedSinkAndFileWritesPreserveExactBytesMetadataAndCloseOwnership() =
        runTest {
            val directory = Files.createTempDirectory("reader-progress-").toString().toPath()
            try {
                for (toFile in listOf(false, true)) {
                    val clock = TestTimeSource()
                    val body = ProgressBody { clock += 10.milliseconds }
                    val statuses = mutableListOf<PageDownloadProgress>()
                    val original = response(body, body.bytes.size.toString())
                    val request = taggedRequest(statuses)
                    val client = PageProgressNetworkClient(FixedResponseClient(request, original), clock)
                    client.executeRequest(request) { wrapped ->
                        assertResponseMetadata(original, wrapped)
                        val output = assertNotNullBody(wrapped)
                        if (toFile) {
                            val file = directory / "page.bin"
                            output.writeTo(FileSystem.SYSTEM, file)
                            assertContentEquals(body.bytes, FileSystem.SYSTEM.read(file) { readByteArray() })
                        } else {
                            assertSinkWrite(body, output)
                        }
                        assertEquals(0, body.closed)
                        output.close()
                        assertEquals(1, body.closed)
                    }
                    assertEquals(1, body.sinkWrites)
                    assertEquals(0, body.fileWrites)
                    assertEquals(PageDownloadProgress.Decoding, statuses.last())
                    val lastTick = assertIs<PageDownloadProgress.InProgress>(statuses[statuses.lastIndex - 1])
                    assertTrue((lastTick.fraction ?: 0f) in FINAL_FRACTION_MIN..1f)
                }
            } finally {
                FileSystem.SYSTEM.deleteRecursively(directory)
            }
        }

    @Test
    fun untaggedSameUrlAndUnrelatedRequestsBypassTheBodyDecorator() =
        runTest {
            val directory = Files.createTempDirectory("reader-cover-").toString().toPath()
            try {
                for (url in listOf("https://reader.test/page.png", "https://reader.test/cover.png")) {
                    val body = ProgressBody()
                    val request = NetworkRequest(url)
                    val original = response(body, null)
                    PageProgressNetworkClient(FixedResponseClient(request, original)).executeRequest(request) { result ->
                        assertSame(original, result)
                        assertSame(body, result.body)
                        result.body?.writeTo(FileSystem.SYSTEM, directory / "cover.bin")
                    }
                    assertEquals(1, body.fileWrites, "untagged requests retain the adapter's native file fast path")
                    assertEquals(0, body.sinkWrites)
                }
            } finally {
                FileSystem.SYSTEM.deleteRecursively(directory)
            }
        }

    @Test
    fun absentInvalidOrNonPositiveContentLengthStaysIndeterminateAndTimeThrottled() =
        runTest {
            for (length in listOf(null, "invalid", "0", "-1")) {
                val clock = TestTimeSource()
                val body = ProgressBody { clock += 10.milliseconds }
                val statuses = mutableListOf<PageDownloadProgress>()
                val request = taggedRequest(statuses)
                PageProgressNetworkClient(FixedResponseClient(request, response(body, length)), clock)
                    .executeRequest(request) { it.body?.writeTo(Buffer()) }
                val ticks = statuses.filterIsInstance<PageDownloadProgress.InProgress>()
                assertEquals(
                    EXPECTED_UNKNOWN_LENGTH_TICKS,
                    ticks.size,
                    "17 chunks at 10ms must emit at 50ms, 100ms and 150ms",
                )
                assertTrue(ticks.all { it.fraction == null })
                assertEquals(PageDownloadProgress.Decoding, statuses.last())
            }
        }

    @Test
    fun cancellationPropagatesWithoutASecondPumpOrClosingTheCallersSink() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            var closed = 0
            val body =
                object : NetworkResponseBody {
                    override suspend fun writeTo(sink: BufferedSink) {
                        sink.writeUtf8("partial")
                        entered.complete(Unit)
                        awaitCancellation()
                    }

                    override suspend fun writeTo(
                        fileSystem: FileSystem,
                        path: Path,
                    ) = error("unused file path")

                    override fun close() {
                        closed++
                    }
                }
            val statuses = mutableListOf<PageDownloadProgress>()
            val request = taggedRequest(statuses)
            val sink = TrackingSink()
            val call =
                async {
                    PageProgressNetworkClient(FixedResponseClient(request, response(body, null)))
                        .executeRequest(request) { result ->
                            try {
                                result.body?.writeTo(sink.buffer())
                            } finally {
                                result.body?.close()
                            }
                        }
                }
            entered.await()
            call.cancelAndJoin()
            assertEquals(1, closed)
            assertEquals(0, sink.closes)
            assertEquals(0, sink.flushes)
            assertEquals(0L, sink.output.size, "cancellation must not flush the wrapper's partial buffer")
            assertTrue(statuses.isEmpty(), "a cancelled body must not claim Decoding or completion")
        }

    private suspend fun assertSinkWrite(
        body: ProgressBody,
        output: NetworkResponseBody,
    ) {
        val tracking = TrackingSink()
        val caller = tracking.buffer()
        output.writeTo(caller)
        assertEquals(0, tracking.closes)
        assertEquals(0, tracking.flushes)
        caller.emit()
        assertContentEquals(body.bytes, tracking.output.readByteArray())
        caller.writeUtf8("still-open").emit()
        assertEquals("still-open", tracking.output.readUtf8())
        caller.close()
    }

    private fun assertResponseMetadata(
        original: NetworkResponse,
        wrapped: NetworkResponse,
    ) {
        assertEquals(original.code, wrapped.code)
        assertEquals(original.requestMillis, wrapped.requestMillis)
        assertEquals(original.responseMillis, wrapped.responseMillis)
        assertSame(original.headers, wrapped.headers)
        assertSame(original.delegate, wrapped.delegate)
    }

    private fun taggedRequest(statuses: MutableList<PageDownloadProgress>): NetworkRequest =
        NetworkRequest(
            url = "https://reader.test/page.png",
            headers = NetworkHeaders.Builder().set("Referer", "https://source.test/").build(),
            extras =
                Extras
                    .Builder()
                    .apply {
                        this[pageProgressAttemptKey] = PageProgressAttempt { statuses += it }
                    }.build(),
        )

    private fun response(
        body: NetworkResponseBody,
        length: String?,
    ): NetworkResponse =
        NetworkResponse(
            code = 200,
            requestMillis = 123L,
            responseMillis = 456L,
            headers = NetworkHeaders.Builder().apply { if (length != null) set("Content-Length", length) }.build(),
            body = body,
            delegate = body,
        )

    private fun assertNotNullBody(response: NetworkResponse): NetworkResponseBody = kotlin.test.assertNotNull(response.body)
}

private class FixedResponseClient(
    private val expected: NetworkRequest,
    private val response: NetworkResponse,
) : NetworkClient {
    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (NetworkResponse) -> T,
    ): T {
        assertSame(expected, request, "request URL, headers, method, body and Extras must not be rebuilt")
        return block(response)
    }
}

private class TrackingSink(
    val output: Buffer = Buffer(),
) : ForwardingSink(output) {
    var closes = 0
    var flushes = 0

    override fun close() {
        closes++
    }

    override fun flush() {
        flushes++
    }
}

private class ProgressBody(
    private val beforeChunk: () -> Unit = {},
) : NetworkResponseBody {
    val bytes = ByteArray(FIXTURE_BODY_BYTES) { (it % FIXTURE_PATTERN_MODULUS).toByte() }
    var sinkWrites = 0
    var fileWrites = 0
    var closed = 0

    override suspend fun writeTo(sink: BufferedSink) {
        sinkWrites++
        var offset = 0
        while (offset < bytes.size) {
            beforeChunk()
            val count = minOf(FIXTURE_CHUNK_BYTES, bytes.size - offset)
            sink.write(bytes, offset, count).emit()
            offset += count
        }
    }

    override suspend fun writeTo(
        fileSystem: FileSystem,
        path: Path,
    ) {
        fileWrites++
        fileSystem.write(path) { write(bytes) }
    }

    override fun close() {
        closed++
    }
}
