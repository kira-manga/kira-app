package me.manga.kira.platform.cbz

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.platform.media.requireValid
import okio.IOException
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGImageRef
import platform.ImageIO.CGImageSourceRef
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosCbzNativeOwnershipTest {
    @Test
    fun eachNativeBandIsCopiedFreedAndEmittedBeforeTheNextEncodeStarts() =
        runTest {
            val native = IosCbzRecordingCodec()
            val inspector = IosPageMediaInspector()
            var emitted = 0
            val result =
                IosLibWebpEncoder.encodeValidatedPage(
                    IOS_CBZ_PNG,
                    inspector.inspect(IOS_CBZ_PNG).requireValid(),
                    75,
                    24,
                    CbzWriter.DEFAULT_MAX_MEMORY_BYTES,
                    native,
                ) { bytes ->
                    assertEquals(emitted + 1, native.encodes, "the encoder must not precompute a list of all bands")
                    assertEquals(native.encodes, native.frees, "libwebp's output buffer is gone before the sink callback")
                    assertEquals(0, native.liveOutputs)
                    assertEquals(1, native.liveImages)
                    assertEquals(1, native.liveContexts)
                    assertTrue(inspector.inspect(bytes).requireValid().height in 1..24)
                    emitted++
                }
            assertEquals(CbzPageEncoding.Encoded(3), result)
            assertEquals(3, emitted)
            native.assertReleased()
        }

    @Test
    fun nativeOutputSizeIsCheckedBeforeCopyAndFreedEvenWhenCopyingFails() =
        runTest {
            NativeOutputFault.entries.forEach { fault ->
                val native =
                    object : IosCbzRecordingCodec() {
                        override fun encodeBand(
                            rgba: CPointer<UByteVar>,
                            width: Int,
                            height: Int,
                            stride: Int,
                            quality: Int,
                            output: CPointer<CPointerVar<UByteVar>>,
                        ): ULong {
                            val size = super.encodeBand(rgba, width, height, stride, quality, output)
                            return when (fault) {
                                NativeOutputFault.ZERO -> 0uL
                                NativeOutputFault.OVER_ADMITTED -> 1_000_000uL
                                NativeOutputFault.OVER_INT -> Int.MAX_VALUE.toULong() + 1uL
                                NativeOutputFault.OVER_SIGNED -> ULong.MAX_VALUE
                                NativeOutputFault.COPY -> size
                            }
                        }

                        override fun copyEncoded(
                            pointer: CPointer<UByteVar>,
                            size: Int,
                        ): ByteArray {
                            val bytes = super.copyEncoded(pointer, size)
                            if (fault == NativeOutputFault.COPY) throw IOException("copy failed")
                            return bytes
                        }
                    }
                val metadata = IosPageMediaInspector().inspect(IOS_CBZ_PNG).requireValid()

                assertFailsWith<IOException> {
                    IosLibWebpEncoder.encodeValidatedPage(IOS_CBZ_PNG, metadata, 75, 24, CbzWriter.DEFAULT_MAX_MEMORY_BYTES, native) {
                        error("a failed native band cannot be emitted")
                    }
                }

                assertEquals(1, native.encodes)
                assertEquals(if (fault == NativeOutputFault.COPY) 1 else 0, native.copies)
                assertEquals(1, native.frees)
                native.assertReleased()
            }
        }

    @Test
    fun genuineFullDecodeOrContextFailureCannotBecomeVerbatimSuccess() =
        iosCbzTest { fixture ->
            listOf(false, true).forEachIndexed { index, contextFailure ->
                val chapter = index + 1L
                val paths = fixture.pages(count = 1, chapterId = chapter)
                val previous = fixture.previousArchive(chapter)
                val originals = fixture.capture(paths)
                val native =
                    object : IosCbzRecordingCodec() {
                        override fun decode(source: CGImageSourceRef): CGImageRef? = if (contextFailure) super.decode(source) else null

                        override fun createContext(
                            plan: CbzTranscodePlan,
                            colorSpace: CGColorSpaceRef,
                        ): CGContextRef? = null
                    }

                assertFailsWith<IOException> {
                    IosCbzWriter(fixture.fileSystem(), IosCbzPageTranscoder(native = native)).createCbz(paths, fixture.mangaId, chapter)
                }

                assertEquals(if (contextFailure) 1 else 0, native.decodes)
                assertEquals(0, native.encodes)
                native.assertReleased()
                fixture.assertRetained(originals, previous, chapter)
            }
        }

    @Test
    fun aLateNativeEncodeFailureRollsBackEarlierRealBandsAndFreesAllBuffers() =
        iosCbzTest { fixture ->
            val paths = fixture.pages(count = 1)
            val previous = fixture.previousArchive()
            val originals = fixture.capture(paths)
            val native =
                object : IosCbzRecordingCodec() {
                    override fun encodeBand(
                        rgba: CPointer<UByteVar>,
                        width: Int,
                        height: Int,
                        stride: Int,
                        quality: Int,
                        output: CPointer<CPointerVar<UByteVar>>,
                    ): ULong {
                        val size = super.encodeBand(rgba, width, height, stride, quality, output)
                        return if (encodes == 2) 0uL else size
                    }
                }

            assertFailsWith<IOException> {
                IosCbzWriter(fixture.fileSystem(), IosCbzPageTranscoder(native = native))
                    .createCbzWithSplitting(paths, fixture.mangaId, 1L, maxHeight = 24)
            }

            assertEquals(2, native.encodes)
            assertEquals(1, native.copies)
            assertEquals(2, native.frees)
            native.assertReleased()
            fixture.assertRetained(originals, previous)
        }

    @Test
    fun cancellationAfterTheActualNativeEncodeStillReleasesHandlesAndDoesNotPublish() =
        iosCbzTest { fixture ->
            val paths = fixture.pages(count = 1)
            val previous = fixture.previousArchive()
            val originals = fixture.capture(paths)
            lateinit var conversionJob: Job
            val native =
                object : IosCbzRecordingCodec() {
                    override fun copyEncoded(
                        pointer: CPointer<UByteVar>,
                        size: Int,
                    ): ByteArray = super.copyEncoded(pointer, size).also { conversionJob.cancel("cancel after native encode") }
                }
            val conversion =
                async {
                    conversionJob = currentCoroutineContext().job
                    IosCbzWriter(fixture.fileSystem(), IosCbzPageTranscoder(native = native)).createCbz(paths, fixture.mangaId, 1L)
                }
            try {
                assertFailsWith<CancellationException> { conversion.await() }
            } finally {
                conversion.cancelAndJoin()
            }
            assertEquals(1, native.encodes)
            assertEquals(1, native.frees)
            native.assertReleased()
            fixture.assertRetained(originals, previous)
        }

    @Test
    fun rollbackEncoderLateEmissionFailureAlsoRetainsThePreviousArchive() =
        iosCbzTest { fixture ->
            val paths = fixture.pages(count = 1)
            val previous = fixture.previousArchive()
            val originals = fixture.capture(paths)
            var written = 0
            val encoder =
                IosCbzPageEncoder { source, metadata, quality, maxHeight, maxMemoryBytes, emit ->
                    SkiaWebpEncoder.encodeValidatedPage(source, metadata, quality, maxHeight, maxMemoryBytes) {
                        if (written == 1) throw IOException("second rollback band write failed")
                        emit("webp", it)
                        written++
                    }
                }

            assertFailsWith<IOException> {
                IosCbzWriter(fixture.fileSystem(), encoder).createCbzWithSplitting(paths, fixture.mangaId, 1L, maxHeight = 24)
            }

            assertEquals(1, written)
            fixture.assertRetained(originals, previous)
        }
}

private enum class NativeOutputFault { ZERO, OVER_ADMITTED, OVER_INT, OVER_SIGNED, COPY }
