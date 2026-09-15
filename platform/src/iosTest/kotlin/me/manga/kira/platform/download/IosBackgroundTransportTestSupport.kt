@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.manga.kira.platform.download

import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageInspectionPolicy
import me.manga.kira.platform.media.PageMediaInspector
import okio.FileSystem
import okio.Path
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTaskStateSuspended
import platform.Foundation.NSUUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal data class TestEvent(
    val pageIndex: Int,
    val complete: Boolean,
    val failure: String? = null,
)

internal const val TEST_ATTEMPT_TOKEN = "4e9b782c-a343-4b0b-88ec-b5bd20330e70"

internal class TransportHarness(
    bytePolicy: PageBytePolicy,
    fileSystem: FileSystem,
    inspectionPolicy: PageInspectionPolicy,
) {
    val system: FileSystem = FileSystem.SYSTEM
    val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ios-page-transport-${NSUUID().UUIDString}"
    private val files =
        object : AppFileSystem {
            override val filesDir: Path = root / "files"
            override val cacheDir: Path = root / "cache"

            override fun fileSystem(): FileSystem = fileSystem
        }
    private val session =
        NSURLSession.sessionWithConfiguration(
            NSURLSessionConfiguration.ephemeralSessionConfiguration,
            delegate = null,
            delegateQueue = null,
        )
    private val requestUrl = requireNotNull(NSURL.URLWithString("https://page-fixture.invalid/page.jpg"))
    val events = mutableListOf<TestEvent>()
    val inspector = RecordingNativeInspector(IosPageMediaInspector(inspectionPolicy, fileSystem))
    val transport =
        IosBackgroundTransport(files, inspector, bytePolicy).apply {
            setListener(
                object : TransferListener {
                    override fun onPageComplete(
                        mangaId: Long,
                        chapterId: Long,
                        pageIndex: Int,
                        attemptToken: String,
                        page: StagedDownloadPage,
                    ) {
                        assertEquals(1L, mangaId)
                        assertEquals(2L, chapterId)
                        assertEquals(TEST_ATTEMPT_TOKEN, attemptToken)
                        // Test consumer accepts the validated handoff. Production accepts it only
                        // inside the data-layer original-token file gate, covered by custody tests.
                        page.publish(files.chapterDir(mangaId, chapterId), pageIndex)
                        events += TestEvent(pageIndex, complete = true)
                    }

                    override fun onPageFailed(
                        mangaId: Long,
                        chapterId: Long,
                        pageIndex: Int,
                        attemptToken: String,
                        message: String?,
                    ) {
                        assertEquals(1L, mangaId)
                        assertEquals(2L, chapterId)
                        events += TestEvent(pageIndex, complete = false, failure = message)
                    }
                },
            )
        }

    fun task(index: Int): NSURLSessionDownloadTask =
        session.downloadTaskWithRequest(NSMutableURLRequest.requestWithURL(requestUrl)).apply {
            taskDescription = IosTransferIdentity(1, 2, index, TEST_ATTEMPT_TOKEN).encode()
            assertEquals(NSURLSessionTaskStateSuspended, state)
            // No resume: callbacks are driven synchronously through the production handler seams.
        }

    fun response(
        status: Int = 200,
        declared: Long? = null,
    ): NSHTTPURLResponse =
        NSHTTPURLResponse(
            uRL = requestUrl,
            statusCode = status.toLong(),
            HTTPVersion = "HTTP/1.1",
            headerFields =
                buildMap<Any?, Any?> {
                    put("Content-Type", "image/jpeg") // Deliberately wrong: actual native format wins.
                    if (declared != null) put("Content-Length", declared.toString())
                },
        )

    fun sourceFile(bytes: ByteArray): Path =
        (root / "os-${NSUUID().UUIDString}.tmp").also {
            system.createDirectories(root)
            system.write(it) { write(bytes) }
        }

    fun page(
        index: Int,
        suffix: String,
    ): Path = files.chapterDir(1, 2) / "image_$index.$suffix"

    fun seedPage(
        index: Int,
        suffix: String,
        bytes: ByteArray,
    ): Path =
        page(index, suffix).also {
            system.createDirectories(requireNotNull(it.parent))
            system.write(it) { write(bytes) }
        }

    fun assertNoPartial() {
        if (system.exists(root)) {
            assertTrue(system.listRecursively(root).none { it.name.endsWith(".partial") })
        }
    }

    fun close() {
        session.invalidateAndCancel()
        system.deleteRecursively(root, mustExist = false)
    }
}

/** Records the real inspector's file boundary without faking any validity verdict. */
internal class RecordingNativeInspector(
    private val native: PageMediaInspector,
) : PageMediaInspector {
    val paths = mutableListOf<Path>()

    override fun inspect(encoded: ByteArray): PageInspection = native.inspect(encoded)

    override fun inspect(path: Path): PageInspection {
        paths += path
        return native.inspect(path)
    }
}

internal fun Path.url(): NSURL = NSURL.fileURLWithPath(toString())
