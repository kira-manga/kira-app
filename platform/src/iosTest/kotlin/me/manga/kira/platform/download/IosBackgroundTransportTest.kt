@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.manga.kira.platform.download

import me.manga.kira.platform.media.PAGE_POLICY_REJECTED_PREFIX
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspectionPolicy
import me.manga.kira.platform.media.PageMediaTestImages
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
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
    fun callbackRetainsOutsideTheLiveTreeUntilTheOriginalAttemptAcceptsIt() {
        withHarness { h ->
            val prior = h.seedPage(0, "png", PageMediaTestImages.gif())
            val abandoned = h.seedStaging(".image_7-$TEST_ATTEMPT_TOKEN.partial")
            var staged: StagedDownloadPage? = null
            var acknowledgeStage: (() -> Unit)? = null
            h.transport.setListener(object : TransferListener {
                override fun onPageComplete(
                    mangaId: Long, chapterId: Long, pageIndex: Int, attemptToken: String,
                    page: StagedDownloadPage, acknowledge: () -> Unit,
                ) {
                    assertEquals(TEST_ATTEMPT_TOKEN, attemptToken)
                    staged = page
                    acknowledgeStage = acknowledge
                }
                override fun onPageFailed(
                    mangaId: Long, chapterId: Long, pageIndex: Int, attemptToken: String,
                    message: String?, acknowledge: () -> Unit,
                ) {
                    error("Unexpected handoff failure: $message")
                }
            })
            val task = h.task(0)
            val source = h.sourceFile(PageMediaTestImages.png())
            h.transport.handleFinishedDownload(task, source.url(), h.response())
            h.transport.handleCompleted(task, error = null)
            val retained = checkNotNull(staged)
            val acknowledgeRetained = checkNotNull(acknowledgeStage)
            assertFalse(h.system.exists(abandoned), "direct callbacks prepare before their first retain")
            assertTrue(retained.path.toString().contains("/.download-staging/"))
            assertContentEquals(PageMediaTestImages.png(), h.system.read(retained.path) { readByteArray() })
            assertContentEquals(PageMediaTestImages.gif(), h.system.read(prior) { readByteArray() })
            // Preparing a later first session touch and another callback must not rescan a live handoff.
            repeat(2) { h.transport.prepareStaging() }
            val nextTask = h.task(1)
            h.transport.handleFinishedDownload(nextTask, h.sourceFile(PageMediaTestImages.png()).url(), h.response())
            h.transport.handleCompleted(nextTask, error = null)
            checkNotNull(staged).discard()
            checkNotNull(acknowledgeStage)()
            assertContentEquals(PageMediaTestImages.png(), h.system.read(retained.path) { readByteArray() })
            // A stale listener rejects/disposes its own staging only, never a current live artifact.
            retained.discard()
            acknowledgeRetained()
            assertFalse(h.system.exists(retained.path))
            assertTrue(h.system.exists(prior))
        }
    }

    @Test
    fun startupPrunesOnlyGeneratedRegularStagesAndPreservesUnknownAndLiveTreeBytes() {
        withHarness { h ->
            val png = PageMediaTestImages.png()
            val abandoned = listOf(
                h.seedStaging(".image_0-${TEST_ATTEMPT_TOKEN.uppercase()}.partial"),
                h.seedStaging(".image_2147483647-$TEST_ATTEMPT_TOKEN.partial"),
            )
            val unknown = listOf(
                ".image_0-incomplete.partial",
                ".image_-1-$TEST_ATTEMPT_TOKEN.partial",
                ".image_2147483648-$TEST_ATTEMPT_TOKEN.partial",
                ".image_01-$TEST_ATTEMPT_TOKEN.partial",
                ".image_0-$TEST_ATTEMPT_TOKEN.partial.extra",
                "notes.txt",
            ).map { h.seedStaging(it) }
            val staging = checkNotNull(abandoned.first().parent)
            val directory = staging / ".image_1-$TEST_ATTEMPT_TOKEN.partial"
            h.system.createDirectories(directory)
            val nested = directory / ".image_2-$TEST_ATTEMPT_TOKEN.partial"
            h.system.write(nested) { write(png) }
            val canonical = h.seedPage(0, "png", png)
            val chapter = checkNotNull(canonical.parent)
            val legacyPartial = chapter / ".image_0-$TEST_ATTEMPT_TOKEN.partial"
            h.system.write(legacyPartial) { write(png) }
            val restored = chapter / "_restored" / TEST_ATTEMPT_TOKEN / "chapter.cbz"
            h.system.createDirectories(checkNotNull(restored.parent))
            h.system.write(restored) { write(png) }

            h.transport.prepareStaging() // The exact preparation used before native session creation.

            abandoned.forEach { assertFalse(h.system.exists(it)) }
            (unknown + listOf(nested, canonical, legacyPartial, restored)).forEach { path ->
                assertContentEquals(png, h.system.read(path) { readByteArray() })
            }
            assertTrue(h.system.metadata(directory).isDirectory)
        }
    }

    @Test
    fun startupNeverTraversesAStagingEntryOrRootSymlink() {
        for (linkRoot in listOf(false, true)) {
            withHarness { h ->
                val targetDirectory = h.root / "outside-staging"
                h.system.createDirectories(targetDirectory)
                val target = targetDirectory / ".image_0-$TEST_ATTEMPT_TOKEN.partial"
                h.system.write(target) { write(PageMediaTestImages.png()) }
                val staging = h.root / "files" / ".download-staging"
                val link = if (linkRoot) staging else staging / ".image_1-$TEST_ATTEMPT_TOKEN.partial"
                h.system.createDirectories(checkNotNull(link.parent))
                h.system.createSymlink(link, if (linkRoot) targetDirectory else target)
                val abandoned = if (linkRoot) null else h.seedStaging(".image_2-$TEST_ATTEMPT_TOKEN.partial")

                h.transport.prepareStaging()

                assertTrue(h.system.metadata(link).symlinkTarget != null)
                assertContentEquals(PageMediaTestImages.png(), h.system.read(target) { readByteArray() })
                if (abandoned != null) assertFalse(h.system.exists(abandoned))
            }
        }
    }

    @Test
    fun failedStartupPruneRetainsBytesWithoutRetryingOverLaterTransfers() {
        var failedPath: Path? = null
        var failedDeletes = 0
        val failing = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun delete(path: Path, mustExist: Boolean) {
                if (path == failedPath) {
                    failedDeletes++
                    throw IOException("synthetic staging deletion failure")
                }
                super.delete(path, mustExist)
            }
        }
        withHarness(fileSystem = failing) { h ->
            val abandoned = h.seedStaging(".image_0-$TEST_ATTEMPT_TOKEN.partial")
            failedPath = abandoned
            val removable = h.seedStaging(".image_1-$TEST_ATTEMPT_TOKEN.partial")
            h.transport.prepareStaging()
            assertEquals(1, failedDeletes)
            assertFalse(h.system.exists(removable), "one failed deletion does not block another")
            val task = h.task(0)
            h.transport.handleFinishedDownload(task, h.sourceFile(PageMediaTestImages.png()).url(), h.response())
            h.transport.handleCompleted(task, error = null)
            h.transport.prepareStaging()

            assertEquals(1, failedDeletes, "failed bytes wait for a later process, not a live rescan")
            assertContentEquals(PageMediaTestImages.png(), h.system.read(abandoned) { readByteArray() })
            assertContentEquals(PageMediaTestImages.png(), h.system.read(h.page(0, "png")) { readByteArray() })
            assertEquals(listOf(TestEvent(0, complete = true)), h.events)
        }
    }

    @Test
    fun legacyTokenlessCallbackCannotBeAdoptedAsTheCurrentAttempt() {
        withHarness { h ->
            val prior = h.seedPage(0, "png", PageMediaTestImages.gif())
            val task = h.task(0).apply { taskDescription = "1|2|0" }
            val source = h.sourceFile(PageMediaTestImages.png())
            h.transport.handleFinishedDownload(task, source.url(), h.response())
            h.transport.handleCompleted(task, error = null)
            assertTrue(h.events.isEmpty())
            assertTrue(h.system.exists(source))
            assertContentEquals(PageMediaTestImages.gif(), h.system.read(prior) { readByteArray() })
        }
    }

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
            val invalid =
                listOf(
                    "html" to PageMediaTestImages.html(),
                    "truncated_png" to png.copyOf(png.size - 1),
                    "bad_png_crc" to PageMediaTestImages.badPngCrc(),
                    "crc_correct_corrupt_png_pixels" to PageMediaTestImages.corruptPngPixels(),
                )
            invalid.forEachIndexed { index, (name, bytes) ->
                val prior = h.seedPage(index, "png", png)
                val alternate = h.seedPage(index, "jpg", PageMediaTestImages.gif())
                val source = h.sourceFile(bytes)
                val task = h.task(index)
                h.transport.handleFinishedDownload(task, source.url(), h.response())
                h.transport.handleCompleted(task, error = null)

                assertEquals(index, h.events.last().pageIndex)
                assertFalse(h.events.last().complete, name)
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
        val failing =
            object : ForwardingFileSystem(FileSystem.SYSTEM) {
                override fun atomicMove(
                    source: Path,
                    target: Path,
                ) {
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
        val changing =
            object : ForwardingFileSystem(FileSystem.SYSTEM) {
                override fun metadataOrNull(path: Path): FileMetadata? {
                    if (!changed && path.name.endsWith(".partial")) {
                        changed = true
                        // Deliberate filesystem fault: mutate only the newly adopted owned snapshot.
                        FileSystem.SYSTEM
                            .appendingSink(path)
                            .buffer()
                            .use { it.writeByte(0) }
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

    private fun TransportHarness.seedStaging(name: String): Path =
        (root / "files" / ".download-staging" / name).also { path ->
            system.createDirectories(checkNotNull(path.parent))
            system.write(path) { write(PageMediaTestImages.png()) }
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
