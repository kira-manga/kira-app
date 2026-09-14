@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.manga.kira.platform.download

import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.platform.media.PAGE_POLICY_REJECTED_PREFIX
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageInspectionPolicy
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.PageMediaTestImages
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.buffer
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTaskStateSuspended
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Native handler/file tests, not URLSession wire, background-relaunch, progress-cadence, cache, or
 * device-memory proof. Tasks belong to an ephemeral native session and are NEVER resumed. The
 * production lazy background session is never touched. The file path uses real Foundation moves,
 * real Okio files, and the production ImageIO inspector; only filesystem faults are injected.
 */
class IosBackgroundTransportTest {
    @Test
    fun exactUnknownAndUnderstatedLengthsPublishTheSameBytesWithTheirNativeSuffix() {
        val png = PageMediaTestImages.png()
        withHarness(PageBytePolicy(png.size.toLong())) { h ->
            listOf(null, 1L, png.size.toLong()).forEachIndexed { index, declared ->
                val prior = h.seedPage(index, "jpg", PageMediaTestImages.gif())
                val task = h.task(index)
                val source = h.sourceFile(png)
                h.transport.handleWroteData(task, png.size.toLong(), png.size.toLong(), declared ?: -1)
                assertEquals(index, h.events.size, "an admitted progress report is not completion")
                h.transport.handleFinishedDownload(task, source.url(), h.response(declared = declared))
                h.transport.handleCompleted(task, error = null)

                assertEquals(TestEvent(index, complete = true), h.events.last())
                assertContentEquals(png, h.system.read(h.page(index, "png")) { readByteArray() })
                assertFalse(h.system.exists(source), "the callback-local file was adopted, not copied")
                assertFalse(h.system.exists(prior), "alternate cleanup occurs after publication")
                h.assertNoPartial()
            }
            assertEquals(3, h.events.size, "didComplete must not duplicate successful file callbacks")
            assertEquals(3, h.inspector.paths.size)
            assertTrue(h.inspector.paths.all { it.name.endsWith(".partial") })
        }
    }

    @Test
    fun progressRefusalsRemainPerTaskAndKeepTheirPolicyReasonThroughCancellation() {
        val png = PageMediaTestImages.png()
        val limit = png.size.toLong()
        withHarness(PageBytePolicy(limit)) { h ->
            val tooMuchActual = h.task(0)
            val tooMuchExpected = h.task(1)
            val survivor = h.task(2)
            h.transport.handleWroteData(tooMuchActual, limit + 1, limit + 1, -1)
            h.transport.handleWroteData(tooMuchExpected, 1, 1, limit + 1)
            h.transport.handleWroteData(tooMuchActual, 1, limit + 2, -1)
            h.transport.handleWroteData(survivor, limit, limit, -1)
            assertEquals(listOf(policyFailure(0), policyFailure(1)), h.events)

            for (task in listOf(tooMuchActual, tooMuchExpected)) {
                val unowned = h.sourceFile(png)
                h.transport.handleFinishedDownload(task, unowned.url(), h.response())
                assertTrue(h.system.exists(unowned), "a rejected callback file remains OS-owned")
                h.transport.handleCompleted(task, cancelledError())
            }
            assertEquals(listOf(policyFailure(0), policyFailure(1)), h.events)
            val accepted = h.sourceFile(png)
            h.transport.handleFinishedDownload(survivor, accepted.url(), h.response())
            h.transport.handleCompleted(survivor, error = null)
            assertEquals(listOf(policyFailure(0), policyFailure(1), TestEvent(2, complete = true)), h.events)
            assertEquals(1, h.inspector.paths.size, "one failed task must not reject another's file")
            assertContentEquals(png, h.system.read(h.page(2, "png")) { readByteArray() })
            h.assertNoPartial()
        }
    }

    @Test
    fun finalFileOverLimitCannotUseAnUnknownOrUnderstatedLengthToReachTheDecoder() {
        val png = PageMediaTestImages.png()
        withHarness(PageBytePolicy(png.size.toLong() - 1)) { h ->
            listOf(null, 1L).forEachIndexed { index, declared ->
                val prior = h.seedPage(index, "png", PageMediaTestImages.gif())
                val source = h.sourceFile(png)
                val task = h.task(index)
                h.transport.handleFinishedDownload(task, source.url(), h.response(declared = declared))
                h.transport.handleCompleted(task, error = null)
                assertEquals(policyFailure(index), h.events.last())
                assertTrue(h.system.exists(source), "over-limit file must not be adopted")
                assertContentEquals(PageMediaTestImages.gif(), h.system.read(prior) { readByteArray() })
                h.assertNoPartial()
            }
            assertTrue(h.inspector.paths.isEmpty(), "actual final size is checked before native inspection")
        }
    }

    @Test
    fun oversizedDeclaredLengthFailsBeforeAdoptionEvenWhenTheFinalFileWouldFit() {
        val png = PageMediaTestImages.png()
        withHarness(PageBytePolicy(png.size.toLong())) { h ->
            val source = h.sourceFile(png)
            val task = h.task(0)
            h.transport.handleFinishedDownload(task, source.url(), h.response(declared = png.size.toLong() + 1))
            h.transport.handleCompleted(task, error = null)
            assertEquals(listOf(policyFailure(0)), h.events)
            assertTrue(h.system.exists(source))
            assertTrue(h.inspector.paths.isEmpty())
            h.assertNoPartial()
        }
    }

    @Test
    fun missingOrBadHttpResponseCannotAdoptOrDecodeAFile() {
        withHarness { h ->
            listOf(null, h.response(status = 403)).forEachIndexed { index, response ->
                val source = h.sourceFile(PageMediaTestImages.png())
                val task = h.task(index)
                h.transport.handleFinishedDownload(task, source.url(), response)
                h.transport.handleCompleted(task, error = null)
                assertTrue(h.system.exists(source))
            }
            assertEquals(
                listOf(TestEvent(0, false, "Missing HTTP response"), TestEvent(1, false, "HTTP 403")),
                h.events,
            )
            assertTrue(h.inspector.paths.isEmpty())
            h.assertNoPartial()
        }
    }

    @Test
    fun emptyMissingAndNonregularSourcesAreNotAdoptedOrRecursivelyDeleted() {
        withHarness { h ->
            val empty = h.sourceFile(byteArrayOf())
            val missing = h.root / "missing-os-file"
            val directory = h.root / "os-directory"
            h.system.createDirectories(directory)
            val child = directory / "must-survive.txt"
            h.system.write(child) { writeUtf8("unowned") }
            listOf(empty, missing, directory).forEachIndexed { index, source ->
                val task = h.task(index)
                h.transport.handleFinishedDownload(task, source.url(), h.response())
                h.transport.handleCompleted(task, error = null)
                assertEquals(TestEvent(index, false, "Downloaded page could not be saved"), h.events.last())
                h.assertNoPartial()
            }
            assertTrue(h.system.exists(empty))
            assertTrue(h.system.metadata(directory).isDirectory)
            assertEquals("unowned", h.system.read(child) { readUtf8() })
            assertTrue(h.inspector.paths.isEmpty())
        }
    }

    @Test
    fun htmlTruncationBadCrcAndCrcCorrectCorruptPixelsCannotReplaceAGoodPage() {
        withHarness { h ->
            val png = PageMediaTestImages.png()
            val invalid = listOf(
                PageMediaTestImages.html(), png.copyOf(png.size - 1),
                PageMediaTestImages.badPngCrc(), PageMediaTestImages.corruptPngPixels(),
            )
            invalid.forEachIndexed { index, bytes ->
                val prior = h.seedPage(index, "png", png)
                val alternate = h.seedPage(index, "jpg", PageMediaTestImages.gif())
                val source = h.sourceFile(bytes)
                val task = h.task(index)
                h.transport.handleFinishedDownload(task, source.url(), h.response())
                h.transport.handleCompleted(task, error = null)

                assertEquals(index, h.events.last().pageIndex)
                assertFalse(h.events.last().complete)
                assertTrue(requireNotNull(h.events.last().failure).startsWith("Invalid downloaded image:"))
                assertFalse(h.system.exists(source), "the invalid file was adopted and then discarded")
                assertContentEquals(png, h.system.read(prior) { readByteArray() })
                assertContentEquals(PageMediaTestImages.gif(), h.system.read(alternate) { readByteArray() })
                h.assertNoPartial()
            }
            assertEquals(invalid.size, h.inspector.paths.size)
            assertEquals(invalid.size, h.events.size)
        }
    }

    @Test
    fun nativeMediaPolicyRejectionKeepsItsStableNonretryableReason() {
        withHarness(inspectionPolicy = PageInspectionPolicy(maxSourcePixels = 71)) { h ->
            val prior = h.seedPage(0, "jpg", PageMediaTestImages.gif())
            val source = h.sourceFile(PageMediaTestImages.png()) // 8 × 9 = 72 source pixels.
            val task = h.task(0)
            h.transport.handleFinishedDownload(task, source.url(), h.response())
            h.transport.handleCompleted(task, cancelledError())
            assertEquals(listOf(TestEvent(0, false, "${PAGE_POLICY_REJECTED_PREFIX}SOURCE_PIXELS")), h.events)
            assertContentEquals(PageMediaTestImages.gif(), h.system.read(prior) { readByteArray() })
            assertFalse(h.system.exists(h.page(0, "png")))
            h.assertNoPartial()
        }
    }

    @Test
    fun failedPublicationRetainsBothThePriorTargetAndAlternateAndDiscardsOnlyItsOwnedFile() {
        var renameAttempts = 0
        val failing = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                renameAttempts++
                throw IOException("synthetic publication failure")
            }
        }
        withHarness(fileSystem = failing) { h ->
            val previous = PageMediaTestImages.gif()
            val target = h.seedPage(0, "png", previous)
            val alternate = h.seedPage(0, "jpg", previous)
            val unrelated = h.seedPage(1, "png", previous)
            val source = h.sourceFile(PageMediaTestImages.png())
            val task = h.task(0)
            h.transport.handleFinishedDownload(task, source.url(), h.response())
            h.transport.handleCompleted(task, error = null)
            assertEquals(listOf(TestEvent(0, false, "Downloaded page could not be saved")), h.events)
            assertEquals(1, renameAttempts)
            assertEquals(1, h.inspector.paths.size, "real ImageIO validation precedes publication")
            assertFalse(h.system.exists(source))
            for (path in listOf(target, alternate, unrelated)) {
                assertContentEquals(previous, h.system.read(path) { readByteArray() })
            }
            h.assertNoPartial()
        }
    }

    @Test
    fun retainedFileSizeIsRecheckedBeforeNativeInspection() {
        val png = PageMediaTestImages.png()
        var changed = false
        val changing = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                if (!changed && path.name.endsWith(".partial")) {
                    changed = true
                    // Deliberate filesystem fault: mutate only the newly adopted owned snapshot.
                    FileSystem.SYSTEM.appendingSink(path).buffer().use { it.writeByte(0) }
                }
                return super.metadataOrNull(path)
            }
        }
        withHarness(PageBytePolicy(png.size.toLong()), fileSystem = changing) { h ->
            val prior = h.seedPage(0, "png", PageMediaTestImages.gif())
            val source = h.sourceFile(png)
            val task = h.task(0)
            h.transport.handleFinishedDownload(task, source.url(), h.response())
            h.transport.handleCompleted(task, error = null)
            assertTrue(changed)
            assertEquals(listOf(policyFailure(0)), h.events)
            assertTrue(h.inspector.paths.isEmpty())
            assertFalse(h.system.exists(source))
            assertContentEquals(PageMediaTestImages.gif(), h.system.read(prior) { readByteArray() })
            h.assertNoPartial()
        }
    }

    @Test
    fun userCancellationIsSilentButCompletionWithoutAFileIsNotSuccess() {
        withHarness { h ->
            h.transport.handleCompleted(h.task(0), cancelledError())
            assertTrue(h.events.isEmpty())
            h.transport.handleCompleted(h.task(1), error = null)
            assertEquals(listOf(TestEvent(1, false, "Download completed without a page")), h.events)
            assertTrue(h.inspector.paths.isEmpty())
            h.assertNoPartial()
        }
    }

    private fun policyFailure(index: Int): TestEvent =
        TestEvent(index, complete = false, failure = "${PAGE_POLICY_REJECTED_PREFIX}ENCODED_BYTES")

    private fun cancelledError(): NSError = NSError(NSURLErrorDomain, NSURLErrorCancelled, null)

    private inline fun withHarness(
        bytePolicy: PageBytePolicy = PageBytePolicy(),
        fileSystem: FileSystem = FileSystem.SYSTEM,
        inspectionPolicy: PageInspectionPolicy = PageInspectionPolicy(bytePolicy = bytePolicy),
        test: (TransportHarness) -> Unit,
    ) {
        val harness = TransportHarness(bytePolicy, fileSystem, inspectionPolicy)
        try {
            test(harness)
        } finally {
            harness.close()
        }
    }
}

private data class TestEvent(val pageIndex: Int, val complete: Boolean, val failure: String? = null)

private class TransportHarness(
    bytePolicy: PageBytePolicy,
    fileSystem: FileSystem,
    inspectionPolicy: PageInspectionPolicy,
) {
    val system: FileSystem = FileSystem.SYSTEM
    val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ios-page-transport-${NSUUID().UUIDString}"
    private val files = object : AppFileSystem {
        override val filesDir: Path = root / "files"
        override val cacheDir: Path = root / "cache"
        override fun fileSystem(): FileSystem = fileSystem
    }
    private val session = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration,
        delegate = null,
        delegateQueue = null,
    )
    private val requestUrl = requireNotNull(NSURL.URLWithString("https://page-fixture.invalid/page.jpg"))
    val events = mutableListOf<TestEvent>()
    val inspector = RecordingNativeInspector(IosPageMediaInspector(inspectionPolicy, fileSystem))
    val transport = IosBackgroundTransport(files, inspector, bytePolicy).apply {
        setListener(object : TransferListener {
            override fun onPageComplete(mangaId: Long, chapterId: Long, pageIndex: Int) {
                assertEquals(1L, mangaId)
                assertEquals(2L, chapterId)
                events += TestEvent(pageIndex, complete = true)
            }

            override fun onPageFailed(mangaId: Long, chapterId: Long, pageIndex: Int, message: String?) {
                assertEquals(1L, mangaId)
                assertEquals(2L, chapterId)
                events += TestEvent(pageIndex, complete = false, failure = message)
            }
        })
    }

    fun task(index: Int): NSURLSessionDownloadTask =
        session.downloadTaskWithRequest(NSMutableURLRequest.requestWithURL(requestUrl)).apply {
            taskDescription = "1|2|$index"
            assertEquals(NSURLSessionTaskStateSuspended, state)
            // No resume: callbacks are driven synchronously through the production handler seams.
        }

    fun response(status: Int = 200, declared: Long? = null): NSHTTPURLResponse = NSHTTPURLResponse(
        URL = requestUrl,
        statusCode = status.toLong(),
        HTTPVersion = "HTTP/1.1",
        headerFields = buildMap<Any?, Any?> {
            put("Content-Type", "image/jpeg") // Deliberately wrong: actual native format wins.
            if (declared != null) put("Content-Length", declared.toString())
        },
    )

    fun sourceFile(bytes: ByteArray): Path = (root / "os-${NSUUID().UUIDString}.tmp").also {
        system.createDirectories(root)
        system.write(it) { write(bytes) }
    }

    fun page(index: Int, suffix: String): Path = files.chapterDir(1, 2) / "image_$index.$suffix"

    fun seedPage(index: Int, suffix: String, bytes: ByteArray): Path = page(index, suffix).also {
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
private class RecordingNativeInspector(private val native: PageMediaInspector) : PageMediaInspector {
    val paths = mutableListOf<Path>()
    override fun inspect(encoded: ByteArray): PageInspection = native.inspect(encoded)
    override fun inspect(path: Path): PageInspection {
        paths += path
        return native.inspect(path)
    }
}

private fun Path.url(): NSURL = NSURL.fileURLWithPath(toString())
