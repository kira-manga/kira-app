package me.manga.kira.presentation.features.download.domain.clean

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.media.AndroidPageMediaInspector
import me.manga.kira.platform.media.PageByteLimitExceeded
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaException
import okio.ByteString.Companion.decodeBase64
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scoped Ktor consumer + actual Android inspector. MockEngine is not real wire/OS download proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoundedPageDownloadTest {
    private val fs = FileSystem.SYSTEM
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "bounded-page-http-${Random.nextLong().toULong()}"
    private val png =
        requireNotNull(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
                .decodeBase64(),
        ).toByteArray()
    private val inspector = AndroidPageMediaInspector()

    @After
    fun cleanup() = fs.deleteRecursively(directory, mustExist = false)

    @Test
    fun mislabeledUrlAndMimeUseNativeFormatAndKeepRequestHeaders() =
        runTest {
            val prior = seedPrior()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        assertEquals("https://source.example/", request.headers[HttpHeaders.Referrer])
                        assertEquals("fixture-agent", request.headers[HttpHeaders.UserAgent])
                        respond(ByteReadChannel(png), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
                    },
                )
            try {
                val result =
                    downloadValidatedPage(
                        client,
                        PageDownloadRequest(
                            "https://images.example/page.jpg",
                            mapOf(
                                "Referer" to "https://source.example/",
                                "User-Agent" to "fixture-agent",
                            ),
                            directory,
                            0,
                        ),
                        fs,
                        inspector,
                        PageBytePolicy(png.size.toLong()),
                    )
                assertEquals("image_0.png", result.name)
                assertContentEquals(png, fs.read(result) { readByteArray() })
                assertFalse(fs.exists(prior), "alternate is cleaned only after successful publication")
                assertEquals(1, requests)
                assertNoPartial()
            } finally {
                client.close()
            }
        }

    @Test
    fun htmlTruncationAndBadPngCrcCannotReplaceAGoodPage() =
        runTest {
            val prior = seedPrior()
            val badCrc = png.copyOf().apply { this[PNG_CRC_OFFSET] = (this[PNG_CRC_OFFSET].toInt() xor 1).toByte() }
            val invalidPages =
                listOf(
                    "<html>challenge</html>".encodeToByteArray(),
                    png.copyOf(png.size - TRUNCATED_BYTES),
                    badCrc,
                )
            for (bad in invalidPages) {
                val client = client(bad)
                try {
                    assertFailsWith<PageMediaException> { download(client) }
                    assertContentEquals(png, fs.read(prior) { readByteArray() })
                    assertFalse(fs.exists(directory / "image_0.png"))
                    assertNoPartial()
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun missingAndUnderstatedLengthStillEnforceActualByteCeiling() =
        runTest {
            val prior = seedPrior()
            for (declared in listOf(null, "1")) {
                val client = client(png, declared)
                try {
                    assertFailsWith<PageByteLimitExceeded> {
                        download(client, policy = PageBytePolicy(png.size.toLong() - 1))
                    }
                    assertContentEquals(png, fs.read(prior) { readByteArray() })
                    assertNoPartial()
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun failedRenameDoesNotPredeletePriorPageOrAlternates() =
        runTest {
            val prior = seedPrior()
            val failing =
                object : ForwardingFileSystem(fs) {
                    override fun atomicMove(
                        source: Path,
                        target: Path,
                    ) = throw IOException("synthetic page rename failure")
                }
            val client = client(png)
            try {
                assertFailsWith<IOException> { download(client, files = failing) }
                assertContentEquals(png, fs.read(prior) { readByteArray() })
                assertFalse(fs.exists(directory / "image_0.png"))
                assertNoPartial()
            } finally {
                client.close()
            }
        }

    @Test
    fun httpCacheClientIsRejectedBeforeAnyResponseCanBuffer() =
        runTest {
            var calls = 0
            val client =
                HttpClient(
                    MockEngine {
                        calls++
                        respond(ByteReadChannel(png))
                    },
                ) { install(HttpCache) }
            try {
                assertFailsWith<IllegalArgumentException> { download(client) }
                assertEquals(0, calls)
            } finally {
                client.close()
            }
        }

    private fun client(
        bytes: ByteArray,
        length: String? = null,
    ): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    ByteReadChannel(bytes),
                    HttpStatusCode.OK,
                    if (length ==
                        null
                    ) {
                        headersOf()
                    } else {
                        headersOf(HttpHeaders.ContentLength, length)
                    },
                )
            },
        )

    private suspend fun download(
        client: HttpClient,
        files: FileSystem = fs,
        policy: PageBytePolicy = PageBytePolicy(),
    ): Path =
        downloadValidatedPage(
            client,
            PageDownloadRequest("https://images.example/page.jpg", emptyMap(), directory, 0),
            files,
            inspector,
            policy,
        )

    private fun seedPrior(): Path {
        fs.createDirectories(directory)
        return (directory / "image_0.jpg").also { fs.write(it) { write(png) } }
    }

    private fun assertNoPartial() = assertTrue(fs.list(directory).none { it.name.endsWith(".partial") })
}

private const val PNG_CRC_OFFSET = 29
private const val TRUNCATED_BYTES = 4
