package me.manga.kira.core.cbz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.manga.kira.platform.device.DeviceTierProbe
import org.aomedia.avif.android.AvifDecoder
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Phase 8.14 port of upstream `me.manga.kira.core.cbz.OptimizedCbzManager`.
 *
 * Parallel decode + compress CBZ encoder with AVIF source support. Hilt
 * `@Inject @Singleton` + `@ApplicationContext` annotations stripped (Koin provides
 * `Context` via `androidContext()`). Source code is otherwise byte-identical, including
 * the AVIF native decoder mutex (the `AvifDecoder` JNI is not thread-safe) and the
 * device-tier-driven semaphore sizing.
 *
 * Device-tier detection (PC-1, Platform Cutover): the legacy no-arg `detectDeviceTier()`
 * top-level function (and its `setAndroidDeviceTierContext(...)` opt-in registration) has been
 * deleted. The runtime tier now comes from the injected `:platform` [DeviceTierProbe] —
 * `single<DeviceTierProbe> { AndroidDeviceTierProbe(androidContext()) }` in `PlatformModule.android`.
 * Same `getCbzSettings(tier)` lookup, same per-tier behaviour vs upstream.
 */
class OptimizedCbzManager(
    private val context: Context,
    deviceTierProbe: DeviceTierProbe,
) {
    private companion object {
        private const val TAG = "OptimizedCBZ"
    }

    private val tier = deviceTierProbe.detect()
    private val settings = getCbzSettings(tier)

    private val decodeSemaphore = Semaphore(settings.maxParallelDecode)
    private val compressSemaphore = Semaphore(settings.maxParallelCompress)

    private val avifDecoderMutex = Mutex()

    private val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }

    private fun isAvifFile(file: File): Boolean {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(12)
                val read = stream.read(header)

                if (read < 12) return false

                header[4] == 'f'.code.toByte() &&
                    header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() &&
                    header[7] == 'p'.code.toByte() &&
                    (
                        (
                            header[8] == 'a'.code.toByte() &&
                                header[9] == 'v'.code.toByte() &&
                                header[10] == 'i'.code.toByte() &&
                                header[11] == 'f'.code.toByte()
                            ) ||
                            (
                                header[8] == 'a'.code.toByte() &&
                                    header[9] == 'v'.code.toByte() &&
                                    header[10] == 'i'.code.toByte() &&
                                    header[11] == 's'.code.toByte()
                                )
                        )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking AVIF magic bytes", e)
            false
        }
    }

    private suspend fun decodeAvifImage(file: File): Bitmap? = avifDecoderMutex.withLock {
        try {
            val bytes = file.readBytes()

            if (bytes.size < 12) {
                Log.w(TAG, "AVIF file too small: ${file.name}")
                return@withLock null
            }

            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.rewind()

            val info = AvifDecoder.Info()
            if (!AvifDecoder.getInfo(buffer, buffer.capacity(), info)) {
                Log.w(TAG, "Invalid AVIF image: ${file.name}")
                return@withLock null
            }

            if (info.width <= 0 || info.height <= 0 || info.width > 8192 || info.height > 8192) {
                Log.w(TAG, "Invalid AVIF dimensions: ${info.width}x${info.height}")
                return@withLock null
            }

            Log.d(TAG, "Decoding AVIF: ${file.name} - ${info.width}x${info.height}, alpha=${info.alphaPresent}")

            val bitmap = createBitmap(
                info.width,
                info.height,
                if (info.alphaPresent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565,
            )

            buffer.rewind()
            val success = AvifDecoder.decode(buffer, buffer.capacity(), bitmap, 0)

            if (!success) {
                bitmap.recycle()
                Log.w(TAG, "Failed to decode AVIF: ${file.name}")
                return@withLock null
            }

            Log.d(TAG, "Successfully decoded AVIF: ${file.name}")
            bitmap
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "AVIF native library not available", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory decoding AVIF: ${file.name}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding AVIF: ${file.name}", e)
            null
        } catch (e: Error) {
            Log.e(TAG, "Fatal error in AVIF decoder: ${file.name}", e)
            null
        }
    }

    private suspend fun decodeAvifWithRegionSplit(file: File, maxChunkHeight: Int): List<Bitmap> {
        val fullBitmap = decodeAvifImage(file) ?: return emptyList()

        val chunks = mutableListOf<Bitmap>()
        return try {
            val height = fullBitmap.height
            val width = fullBitmap.width

            if (height <= maxChunkHeight) {
                return listOf(fullBitmap)
            }

            var y = 0

            while (y < height) {
                val chunkHeight = minOf(maxChunkHeight, height - y)
                val chunk = Bitmap.createBitmap(fullBitmap, 0, y, width, chunkHeight)
                chunks.add(chunk)
                y += chunkHeight
            }

            fullBitmap.recycle()
            chunks
        } catch (e: Exception) {
            Log.e(TAG, "Error splitting AVIF bitmap", e)
            // Recycle any chunks already allocated before the failure — they hold native pixel
            // memory exactly when memory is tightest.
            chunks.forEach { it.recycle() }
            fullBitmap.recycle()
            emptyList()
        }
    }

    @Suppress("unused")
    private fun decodeAndSplitWithRegionDecoder(
        file: File,
        maxChunkHeight: Int,
        quality: Int,
    ): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val width = bounds.outWidth
        val height = bounds.outHeight

        file.inputStream().use { stream ->
            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            }

            decoder?.let { dec ->
                var y = 0

                while (y < height) {
                    val chunkHeight = minOf(maxChunkHeight, height - y)
                    val region = Rect(0, y, width, y + chunkHeight)

                    val bitmap = dec.decodeRegion(region, BitmapFactory.Options())
                    if (bitmap != null) {
                        val bytes = compressBitmap(bitmap, quality)
                        chunks.add(bytes)
                        bitmap.recycle()
                    }

                    y += chunkHeight
                }

                dec.recycle()
            }
        }

        return chunks
    }

    private fun decodeWithSampling(file: File, maxDimension: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)

        val sampleSize = calculateInSampleSize(
            options.outWidth,
            options.outHeight,
            maxDimension,
        )

        options.inSampleSize = sampleSize
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565

        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var size = 1
        while (width / size > maxDim || height / size > maxDim) {
            size *= 2
        }
        return size
    }

    private fun compressBitmap(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(webpFormat, quality, output)
        return output.toByteArray()
    }

    suspend fun createCbzParallel(
        imageFiles: List<String>,
        mangaId: Long,
        chapterId: Long,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): String = withContext(Dispatchers.Default) {

        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId")
        chapterDir.mkdirs()

        val outputFile = File(chapterDir, "chapter_${chapterId}.cbz")

        try {
            val processed = coroutineScope {
                imageFiles.mapIndexed { index, path ->
                    async {
                        ensureActive()

                        val decoded = decodeSemaphore.withPermit {
                            ensureActive()
                            decodeImageSafely(File(path))
                        }

                        val compressed = compressSemaphore.withPermit {
                            ensureActive()
                            compressChunks(decoded)
                        }

                        onProgress?.invoke(index + 1, imageFiles.size)
                        compressed
                    }
                }.awaitAll()
            }

            ensureActive()

            // A page whose decode failed yields an empty chunk list (decodeImageSafely returns
            // emptyList() on a null BitmapFactory/AvifDecoder result). Zipping anyway would archive
            // the chapter with pages silently missing and then delete the only copy of the source
            // bytes below — unrecoverable data loss. Fail loudly instead so the download surfaces as
            // an error and the originals are left untouched for a retry.
            if (processed.any { it.isEmpty() }) {
                throw IllegalStateException(
                    "CBZ encode failed for chapter $chapterId: ${processed.count { it.isEmpty() }} of " +
                        "${processed.size} pages could not be decoded",
                )
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile), 64 * 1024)).use { zip ->
                var index = 0
                processed.forEach { chunkList ->
                    ensureActive()
                    chunkList.forEach { bytes ->
                        zip.putNextEntry(ZipEntry("page_%04d.webp".format(index)))
                        zip.write(bytes)
                        zip.closeEntry()
                        index++
                    }
                }
            }

            ensureActive()

            imageFiles.forEach { File(it).delete() }

            outputFile.absolutePath
        } catch (e: CancellationException) {
            Log.w(TAG, "CBZ creation cancelled for chapter $chapterId")

            if (outputFile.exists()) {
                try {
                    outputFile.delete()
                    Log.d(TAG, "Deleted partial CBZ: ${outputFile.absolutePath}")
                } catch (deleteException: Exception) {
                    Log.e(TAG, "Failed to delete partial CBZ", deleteException)
                }
            }

            throw e
        } catch (e: Throwable) {
            // Throwable (not Exception): bitmap-heavy work realistically throws OutOfMemoryError,
            // which is an Error — it must still clean up the truncated chapter_<id>.cbz before
            // rethrowing. CancellationException is handled by the branch above.
            if (outputFile.exists()) {
                outputFile.delete()
            }

            Log.e(TAG, "Error creating CBZ: ${e.message}", e)
            throw e
        }
    }

    private suspend fun decodeImageSafely(file: File): List<Bitmap> {
        if (isAvifFile(file)) {
            return decodeAvifImageSafely(file)
        }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)

        val width = opts.outWidth
        val height = opts.outHeight
        val estimatedBytes = width * height * 4L

        Log.d(TAG, "Load ${file.name}: ${width}x${height}, ~${estimatedBytes / 1_000_000}MB")

        return when {
            height > settings.regionDecodeThreshold ->
                decodeAndSplitBitmaps(file, settings.regionDecodeThreshold)

            estimatedBytes > settings.samplingThreshold ->
                listOfNotNull(decodeWithSampling(file, settings.regionDecodeThreshold))

            else ->
                listOfNotNull(BitmapFactory.decodeFile(file.absolutePath))
        }
    }

    private suspend fun decodeAvifImageSafely(file: File): List<Bitmap> {
        return try {
            // Probe dimensions via AvifDecoder.getInfo (no full decode) so an over-threshold image
            // is decoded exactly once. A full decode here just to measure, then recycled and decoded
            // again, doubled the time spent in the serialized avifDecoderMutex for the largest images.
            val info = probeAvifInfo(file)
                ?: run {
                    Log.w(TAG, "Failed to read AVIF info: ${file.name}")
                    return emptyList()
                }

            val width = info.width
            val height = info.height
            val estimatedBytes = width * height * 4L

            Log.d(TAG, "AVIF ${file.name}: ${width}x${height}, ~${estimatedBytes / 1_000_000}MB")

            if (height > settings.regionDecodeThreshold) {
                decodeAvifWithRegionSplit(file, settings.regionDecodeThreshold)
            } else {
                listOfNotNull(decodeAvifImage(file))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding AVIF safely: ${file.name}", e)
            emptyList()
        }
    }

    private suspend fun probeAvifInfo(file: File): AvifDecoder.Info? = avifDecoderMutex.withLock {
        try {
            val bytes = file.readBytes()
            if (bytes.size < 12) {
                Log.w(TAG, "AVIF file too small: ${file.name}")
                return@withLock null
            }

            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.rewind()

            val info = AvifDecoder.Info()
            if (!AvifDecoder.getInfo(buffer, buffer.capacity(), info)) {
                Log.w(TAG, "Invalid AVIF image: ${file.name}")
                return@withLock null
            }

            if (info.width <= 0 || info.height <= 0 || info.width > 8192 || info.height > 8192) {
                Log.w(TAG, "Invalid AVIF dimensions: ${info.width}x${info.height}")
                return@withLock null
            }

            info
        } catch (e: Exception) {
            Log.e(TAG, "Error reading AVIF info: ${file.name}", e)
            null
        }
    }

    private fun decodeAndSplitBitmaps(file: File, maxChunkHeight: Int): List<Bitmap> {
        val result = mutableListOf<Bitmap>()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val width = bounds.outWidth
        val height = bounds.outHeight

        file.inputStream().use { stream ->
            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            }

            decoder?.let { dec ->
                var y = 0
                while (y < height) {
                    val h = minOf(maxChunkHeight, height - y)
                    val region = Rect(0, y, width, y + h)
                    dec.decodeRegion(region, BitmapFactory.Options())?.let {
                        result.add(it)
                    }
                    y += h
                }
                dec.recycle()
            }
        }

        return result
    }

    private fun compressChunks(bitmaps: List<Bitmap>): List<ByteArray> =
        bitmaps.map { bmp ->
            val arr = compressBitmap(bmp, settings.webpQuality)
            bmp.recycle()
            arr
        }
}

/* ----------------------------------------------------------------------------
 * §253 AUDIT-TRAIL POSTSCRIPT — cluster257 (2026-05-29)
 * ----------------------------------------------------------------------------
 *
 *  CLASSIFICATION: LIVE — ANDROIDMAIN-SOLO-DOUBLET (leaf 2 of 2)
 *
 *  Sibling of CbzManager.kt (cluster257 leaf 1). See that file's §253
 *  postscript for the doublet-level rationale (ANDROIDMAIN-SOLO-DOUBLET,
 *  HILT-TO-KOIN-ANNOTATION-STRIP, SEQUENTIAL-VS-PARALLEL-AXIS,
 *  PHASE-8.14-PORT-LINEAGE). This postscript only documents the leaf-2
 *  deltas that distinguish Optimized from the simpler sibling.
 *
 *  AVIF-DECODER-DEPENDENCY-LIVE: line 22 imports
 *  `org.aomedia.avif.android.AvifDecoder`. This is the libavif JNI binding
 *  shipped via the `:shared` androidMain dependency on the libavif Android
 *  bundle (see memory entry project_yami_avif_decoder for the broader Coil
 *  registration rationale — that entry covers reading AVIF; THIS file covers
 *  ENCODE-time AVIF source decode for the WEBP+ZIP repack pipeline). Without
 *  AvifDecoder, AVIF-source manga chapters would fail to decode → empty CBZ.
 *
 *  AVIF-THREAD-SAFETY-MUTEX-LIVE: line 58 declares `avifDecoderMutex: Mutex`.
 *  Line 100 + every AVIF decode site wraps the JNI call in
 *  `avifDecoderMutex.withLock { ... }` because the `AvifDecoder` JNI is NOT
 *  thread-safe (documented constraint upstream). Future readers MUST NOT
 *  remove this mutex even if profiling suggests contention — the JNI binding
 *  will crash without serialization. The KDoc at line 38 documents this
 *  constraint and the citation is LIVE.
 *
 *  DEVICE-TIER-DRIVEN-PARALLELISM-LIVE: lines 52-56 read
 *  `detectDeviceTier()` → `getCbzSettings(tier)` → use `maxParallelDecode`
 *  + `maxParallelCompress` to size the two Semaphore instances. This is the
 *  upstream's heap-pressure mitigation. `detectDeviceTier()` was made no-arg
 *  in Phase 8.12 (the Android actual reads the application context registered
 *  via `setAndroidDeviceTierContext(...)` in `MyApp.onCreate()` before Koin
 *  starts). The class KDoc at line 41 documents this lifecycle constraint;
 *  the citation is LIVE.
 *
 *  KOIN-BINDING-LIVE: registered as
 *      single { OptimizedCbzManager(androidContext()) }
 *  in `shared/androidMain/.../di/PlatformModule.android.kt:158`. Resolved by
 *  ChapterDownloadService ctor (line 60 of the service).
 *
 *  CHAPTERDOWNLOADSERVICE-CONSUMER-LIVE: this class is the HOT-PATH CBZ
 *  encoder for ChapterDownloadService — line 186 + line 318 of the service
 *  call `optimizedCbzManager.createCbzParallel(...)` for both the streaming
 *  download chunk and the retry/repack path. The simpler CbzManager sibling
 *  is held as a fallback API surface (see leaf-1 postscript) but the actual
 *  encode work flows through this file.
 *
 *  REGION-DECODE-PARTIAL-FULFILL: `decodeAndSplitWithRegionDecoder` at
 *  line 188 is marked `@Suppress("unused")` because the live code path
 *  uses `decodeAndSplitBitmaps` at line 402 (which is the equivalent
 *  region-decode path but returns Bitmap chunks rather than pre-compressed
 *  byte arrays). The `@Suppress("unused")` variant is RESERVED upstream code
 *  retained for parity — same justification as the sibling's
 *  `compressionDispatcher` field (verbatim upstream preservation).
 *
 *  RGB_565-PREFERENCE-LIVE: line 248 sets
 *  `options.inPreferredConfig = Bitmap.Config.RGB_565` for the sampling path.
 *  This is the same memory-pressure mitigation that the post-port
 *  buildImageRequest fix (memory entry project_yami_image_quality_buildrequest)
 *  applied to the reader page-decoder. Future readers should NOT change this
 *  to ARGB_8888 — the CBZ encoder is heap-pressure-bounded by device tier
 *  and ARGB_8888 doubles the in-flight bitmap footprint.
 *
 *  WEBP_LOSSY-API-30-GATE-LIVE: lines 60-65 + line 248 branch on
 *  `Build.VERSION.SDK_INT >= R` to select WEBP_LOSSY vs the deprecated WEBP
 *  CompressFormat — identical to the sibling's branch. Behaviour parity
 *  with upstream.
 *
 *  AVIF-MAGIC-BYTES-DETECTION-LIVE: lines 67-98 hand-roll an AVIF file-type
 *  detection by reading the 12-byte ISOBMFF header and matching the
 *  `ftyp avif` / `ftyp avis` brand. This is intentional — using
 *  `BitmapFactory.decodeFile` to probe AVIF would fail on pre-API-31 devices
 *  where the platform decoder has no AVIF support. The hand-rolled probe
 *  routes AVIF sources through the AvifDecoder JNI on ALL Android versions.
 *
 *  CANCELLATION-CLEANUP-LIVE: lines 321-333 catch CancellationException and
 *  delete the partial CBZ output file before rethrowing. This prevents
 *  corrupted CBZ files from polluting the library when a chapter download
 *  is cancelled mid-encode. Future readers should NOT swallow the
 *  CancellationException — rethrow is mandatory for coroutine cooperation.
 *
 *  CLUSTER257 SOLO-DOUBLET REGISTER (closer):
 *      leaf 1: CbzManager.kt (sibling postscript)
 *      leaf 2: OptimizedCbzManager.kt (this file)
 *  CLOSES THE androidMain-SOLO cbz/ doublet. Future scouts: skip cbz/ —
 *  remaining cbz/ files are part of the CbzWriter 3-actual fan (cluster218)
 *  or commonMain expect/actual facades (cluster180), both fully swept.
 *
 *  CLUSTER258 PIVOT PREDICTION: see leaf-1 postscript — strongest candidate
 *  is ChapterDownloadService.kt (the downstream consumer of this doublet),
 *  closing out the Android-only download chain.
 *
 *  SATURATION-WATCH: leaf-2 added 5 new delta-axes beyond the sibling
 *  (AVIF-DECODER-DEP, AVIF-THREAD-SAFETY-MUTEX, DEVICE-TIER-PARALLELISM,
 *  RGB_565-PREFERENCE, AVIF-MAGIC-BYTES-DETECTION, CANCELLATION-CLEANUP) —
 *  cluster257 is NOT a saturation cluster. Continue sweep; reset the
 *  3-consecutive-null-delta counter to zero.
 *
 *  Verified pre-postscript:
 *    - Grep'd consumers — same two sites as the sibling: PlatformModule
 *      Koin binding + ChapterDownloadService injection/invocation
 *    - Confirmed the AvifDecoder import is the org.aomedia.avif.android
 *      libavif JNI package (NOT a Coil decoder — Coil reads AVIF via the
 *      separate AvifDecoderCoil.Factory registration documented in memory
 *      entry project_yami_avif_decoder)
 *
 *  Build gates: Android + iOS Arm64 + iOS SimulatorArm64 (Desktop not
 *  required — androidMain-only).
 *
 * --------------------------------------------------------------------------
 */
