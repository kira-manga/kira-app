package me.manga.kira.platform.cbz

import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.platform.media.PageImageFormat
import me.manga.kira.platform.media.PageImageMetadata
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageInspectionPolicy
import me.manga.kira.platform.media.PageInspectionRejection
import me.manga.kira.platform.media.PageMediaException
import me.manga.kira.platform.media.requireValid
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IosCbzMemoryAdmissionTest {
    @Test
    fun exactBudgetReachesNativeDecodeButOneByteLessDoesNot() =
        runTest {
            val source = IOS_CBZ_EXACT_ROWS_PNG
            assertEquals(68, source.size)
            val metadata = IosPageMediaInspector().inspect(source).requireValid()
            assertEquals(PageImageMetadata(PageImageFormat.PNG, 8, 9), metadata)
            // 3*68 +16*(8*9) +4MiB +2*64KiB +48*8*1 =4,327,116, independently fixed for this fixture.
            val exactBudget = 4_327_116L
            val page = ValidatedCbzPage(source, metadata)
            assertSingleRowTranscode(page, exactBudget)
            assertBudgetDenied(page, exactBudget - 1)
        }

    @Test
    fun oneSourcePixelOrRowOverTheSameExactBudgetCannotEnterNativeDecode() =
        runTest {
            // Same encoded lengths on each side: the denial cannot be attributed to encoded-byte growth.
            val cases =
                listOf(
                    Triple(IOS_CBZ_EXACT_PIXEL_PNG, IOS_CBZ_EXTRA_PIXEL_PNG, 4_326_025L),
                    Triple(IOS_CBZ_EXACT_ROWS_PNG, IOS_CBZ_EXTRA_ROW_PNG, 4_327_116L),
                )
            cases.forEach { (exact, larger, budget) ->
                assertEquals(exact.size, larger.size)
                val inspector = IosPageMediaInspector()
                assertSingleRowTranscode(ValidatedCbzPage(exact, inspector.inspect(exact).requireValid()), budget)
                assertBudgetDenied(ValidatedCbzPage(larger, inspector.inspect(larger).requireValid()), budget)
            }
        }

    @Test
    fun tinyBudgetPreservesTheValidatedSnapshotWithoutFullDecodeOrContextCreation() =
        iosCbzTest { fixture ->
            val paths = fixture.pages(count = 1)
            val original = fixture.bytes(paths.single())
            val native = IosCbzRecordingCodec()
            val writer = IosCbzWriter(fixture.fileSystem(), IosCbzPageTranscoder(native = native))

            writer.createCbzWithSplitting(paths, fixture.mangaId, 1L, maxMemoryBytes = 1)

            val (name, bytes) = fixture.archiveEntries().single()
            assertEquals("page_0000.png", name)
            assertContentEquals(original, bytes)
            native.assertNoTranscode()
            assertFalse(fixture.system.exists(paths.single()))
            fixture.assertNoTemporary()
        }

    @Test
    fun validHighlyCompressedLargePageIsPreservedUnderTheDefaultBudget() =
        iosCbzTest { fixture ->
            assertEquals(1112, IOS_CBZ_LARGE_PNG.size)
            val paths = fixture.pages(count = 1, bytes = IOS_CBZ_LARGE_PNG)
            val native = IosCbzRecordingCodec()
            val writer = IosCbzWriter(fixture.fileSystem(), IosCbzPageTranscoder(native = native))

            writer.createCbzWithSplitting(paths, fixture.mangaId, 1L)

            val (name, bytes) = fixture.archiveEntries().single()
            assertEquals("page_0000.png", name)
            assertContentEquals(IOS_CBZ_LARGE_PNG, bytes)
            assertEquals(
                PageImageMetadata(PageImageFormat.PNG, 512, 16_384),
                IosPageMediaInspector().inspect(bytes).requireValid(),
            )
            native.assertNoTranscode()
            assertFalse(fixture.system.exists(paths.single()))
            fixture.assertNoTemporary()
        }

    @Test
    fun validatedWidthBeyondWebpCapacityIsPreservedWithoutAThrowawayDecode() =
        iosCbzTest { fixture ->
            val paths = fixture.pages(count = 1, bytes = IOS_CBZ_TOO_WIDE_PNG)
            val native = IosCbzRecordingCodec()

            IosCbzWriter(fixture.fileSystem(), IosCbzPageTranscoder(native = native))
                .createCbz(paths, fixture.mangaId, 1L)

            val (name, bytes) = fixture.archiveEntries().single()
            assertEquals("page_0000.png", name)
            assertContentEquals(IOS_CBZ_TOO_WIDE_PNG, bytes)
            native.assertNoTranscode()
            fixture.assertNoTemporary()
        }

    @Test
    fun invalidBytesCannotUseBudgetPreservationWithEitherEncoderToggle() =
        iosCbzTest { fixture ->
            listOf(true, false).forEachIndexed { index, useLibWebp ->
                val chapter = index + 1L
                val paths = fixture.pages(count = 1, chapterId = chapter, bytes = "not an image".encodeToByteArray())
                val originals = fixture.capture(paths)
                val previous = fixture.previousArchive(chapter)
                val native = IosCbzRecordingCodec()
                val writer = IosCbzWriter(fixture.fileSystem(), IosCbzPageTranscoder(useLibWebp, native))

                assertFailsWith<IOException> {
                    writer.createCbzWithSplitting(paths, fixture.mangaId, chapter, maxMemoryBytes = 1)
                }

                native.assertNoTranscode()
                fixture.assertRetained(originals, previous, chapter)
            }
        }

    @Test
    fun inspectionPolicyRejectionIsNotAValidatedBudgetPreservationDecision() =
        iosCbzTest { fixture ->
            val paths = fixture.pages(count = 1)
            val originals = fixture.capture(paths)
            val previous = fixture.previousArchive()
            val native = IosCbzRecordingCodec()
            val inspector = IosPageMediaInspector(PageInspectionPolicy(maxSourcePixels = 10))
            val writer = IosCbzWriter(fixture.fileSystem(), IosCbzPageTranscoder(native = native), inspector)

            val failure =
                assertFailsWith<PageMediaException> {
                    writer.createCbzWithSplitting(paths, fixture.mangaId, 1L, maxMemoryBytes = 1)
                }

            assertEquals(
                PageInspectionRejection.SOURCE_PIXELS,
                assertIs<PageInspection.Rejected>(failure.inspection).reason,
            )
            native.assertNoTranscode()
            fixture.assertRetained(originals, previous)
        }

    @Test
    fun skiaRollbackAlsoAdmitsBeforeDecodeAndStillProducesRealSplitPages() =
        iosCbzTest { fixture ->
            val source = IOS_CBZ_PNG
            val metadata = IosPageMediaInspector().inspect(source).requireValid()
            val result =
                SkiaWebpEncoder.encodeValidatedPage(
                    ValidatedCbzPage(source, metadata),
                    CbzEncodingOptions(75, 24, 1),
                    decode = { error("rollback budget denial must precede native decode") },
                ) { error("budget denial cannot emit a WebP band") }
            assertEquals(CbzPageEncoding.PreserveOriginal(CbzPreservationReason.MEMORY_BUDGET), result)
            val paths = fixture.pages(count = 1)

            IosCbzWriter(fixture.fileSystem(), IosCbzPageTranscoder(useLibWebp = false))
                .createCbzWithSplitting(paths, fixture.mangaId, 1L, maxHeight = 24)

            fixture.assertWebpDimensions(listOf(23 to 24, 23 to 24, 23 to 17))
            assertTrue(paths.none { fixture.system.exists(it) })
            fixture.assertNoTemporary()
        }

    private suspend fun assertSingleRowTranscode(
        page: ValidatedCbzPage,
        budget: Long,
    ) {
        val native = IosCbzRecordingCodec()
        var bands = 0
        val result =
            IosLibWebpEncoder.encodeValidatedPage(page, CbzEncodingOptions(75, 1, budget), native) {
                assertEquals(
                    PageImageMetadata(PageImageFormat.WEBP, page.metadata.width, 1),
                    IosPageMediaInspector().inspect(it).requireValid(),
                )
                bands++
            }
        assertEquals(CbzPageEncoding.Encoded(page.metadata.height), result)
        assertEquals(page.metadata.height, bands)
        assertEquals(1, native.decodes)
        assertEquals(1, native.contexts)
        assertEquals(page.metadata.height, native.frees)
        native.assertReleased()
    }

    private suspend fun assertBudgetDenied(
        page: ValidatedCbzPage,
        budget: Long,
    ) {
        val denied = IosCbzRecordingCodec()
        val result =
            IosLibWebpEncoder.encodeValidatedPage(page, CbzEncodingOptions(75, 1, budget), denied) {
                error("budget denial cannot emit transcoded bytes")
            }
        assertEquals(CbzPageEncoding.PreserveOriginal(CbzPreservationReason.MEMORY_BUDGET), result)
        denied.assertNoTranscode()
    }
}
