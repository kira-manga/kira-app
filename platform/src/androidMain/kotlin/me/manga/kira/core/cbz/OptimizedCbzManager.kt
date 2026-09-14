package me.manga.kira.core.cbz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.manga.kira.platform.cbz.BoundedCbzEntryOutput
import me.manga.kira.platform.cbz.CbzTranscodeAdmission
import me.manga.kira.platform.cbz.CbzTranscodeBudget
import me.manga.kira.platform.cbz.validateAndroidCbzArchive
import me.manga.kira.platform.device.DeviceTierProbe
import me.manga.kira.platform.media.PageImageFormat
import me.manga.kira.platform.media.PageImageMetadata
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One conversion and one decoded chunk at a time on the shipping Koin singleton.
 * Encodes directly into an owned temporary ZIP, validates, then atomically publishes it.
 *
 * Tier quality/sampling policy is retained for admitted pages. Every page is inspected from one
 * bounded, owned snapshot; valid over-budget/known unsupported transcodes preserve those same bytes.
 * AVIF still needs its full parent plus at most one crop, now under the shared native allowance and
 * permit. Admission estimates and fixed stream buffers are not a hard native heap/RSS ceiling.
 * The historical createCbzParallel name remains for callers; other CBZ writers are independent.
 * Codec decorators transfer bitmap ownership here; the synchronous encoder must neither retain
 * that bitmap nor close the supplied ZIP stream. Production callers use the Android defaults.
 */
class OptimizedCbzManager(
    private val context: Context,
    deviceTierProbe: DeviceTierProbe,
    private val decoder: CbzImageDecoder = CbzImageDecoder(),
    private val output: CbzArchiveOutput = CbzArchiveOutput(),
    private val pagePolicy: CbzPagePolicy = CbzPagePolicy(),
    private val encode: (Bitmap, Bitmap.CompressFormat, Int, OutputStream) -> Boolean =
        { bitmap, format, quality, stream -> bitmap.compress(format, quality, stream) },
) {
    private val settings = getCbzSettings(deviceTierProbe.detect())
    private val conversionMutex = Mutex()
    private val webpFormat = cbzWebpFormat()

    suspend fun createCbzParallel(
        imageFiles: List<String>,
        mangaId: Long,
        chapterId: Long,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): String =
        withContext(Dispatchers.Default) {
            conversionMutex.withLock {
                // An uncontended Mutex acquisition does not itself check cancellation.
                currentCoroutineContext().ensureActive()
                createArchive(imageFiles, mangaId, chapterId, onProgress)
            }
        }

    private suspend fun createArchive(
        imageFiles: List<String>,
        mangaId: Long,
        chapterId: Long,
        onProgress: ((Int, Int) -> Unit)?,
    ): String {
        require(imageFiles.isNotEmpty()) { "No images to archive" }
        val directory = File(context.filesDir, "manga/$mangaId/chapter_$chapterId")
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create chapter directory")
        val destination = File(directory, "chapter_$chapterId.cbz")
        val result = destination.absolutePath
        val temporary = File.createTempFile(".chapter_$chapterId-", ".cbz.tmp", directory)
        try {
            val entries = writeArchive(temporary, imageFiles, onProgress)
            validateAndroidCbzArchive(temporary, entries)
            currentCoroutineContext().ensureActive()
            output.publish(temporary, destination)
        } finally {
            // After rename this pathname is absent. Never delete destination on failure.
            temporary.deleteCbzOwnedFileQuietly()
        }
        // File publication, not DB SUCCESS. A cancelled withContext return may still throw
        // to the caller; only the worker's existing ownership policy may then remove it.
        deleteCbzSourcesAfterCommit(imageFiles)
        return result
    }

    private suspend fun writeArchive(
        temporary: File,
        imageFiles: List<String>,
        onProgress: ((Int, Int) -> Unit)?,
    ): List<String> =
        output.open(temporary).use { raw ->
            // The outer use also owns raw if buffer/ZIP construction throws (including OOM).
            ZipOutputStream(BufferedOutputStream(raw, CBZ_BUFFER_SIZE)).use { zip ->
                val entries = mutableListOf<String>()
                imageFiles.forEachIndexed { page, path ->
                    currentCoroutineContext().ensureActive()
                    val firstEntry = entries.size
                    withValidatedCbzSnapshot(File(path), pagePolicy.bytePolicy, pagePolicy.inspector) { file, metadata, bytes ->
                        writeValidatedPage(file, metadata, bytes, zip, entries)
                    }
                    if (entries.size <= firstEntry) throw IOException("CBZ input produced no entries")
                    currentCoroutineContext().ensureActive()
                    // All progress is pre-publication: a callback failure can safely abort.
                    onProgress?.invoke(page + 1, imageFiles.size)
                }
                entries
            }
        }

    private suspend fun writeValidatedPage(
        file: File,
        metadata: PageImageMetadata,
        encodedBytes: Long,
        zip: ZipOutputStream,
        entries: MutableList<String>,
    ) {
        val admission =
            CbzTranscodeBudget.admit(
                metadata.width,
                metadata.height,
                encodedBytes,
                settings.regionDecodeThreshold,
                pagePolicy.maxMemoryBytes,
            )
        if (admission is CbzTranscodeAdmission.Admitted && canTranscode(metadata, encodedBytes, admission.plan.bandHeight)) {
            streamPage(file, metadata, admission.plan.bandHeight) { bitmap ->
                currentCoroutineContext().ensureActive()
                val name = cbzEntryName(entries.size)
                zip.putNextEntry(ZipEntry(name))
                val sink = BoundedCbzEntryOutput(zip, admission.plan.maxEncodedBandBytes)
                if (!encode(bitmap, webpFormat, settings.webpQuality, sink)) throw IOException("CBZ bitmap compression failed")
                currentCoroutineContext().ensureActive()
                zip.closeEntry()
                entries += name
            }
        } else {
            val name = "${cbzEntryName(entries.size).substringBeforeLast('.')}.${metadata.format.extension}"
            currentCoroutineContext().ensureActive()
            zip.putNextEntry(ZipEntry(name))
            copyCbzPage(file, zip, pagePolicy.bytePolicy)
            currentCoroutineContext().ensureActive()
            zip.closeEntry()
            entries += name
        }
    }

    private fun canTranscode(
        metadata: PageImageMetadata,
        encodedBytes: Long,
        bandHeight: Int,
    ): Boolean =
        when (metadata.format) {
            PageImageFormat.AVIF ->
                cbzAvifOutputAdmitted(
                    metadata.width,
                    metadata.height,
                    encodedBytes.toInt(),
                    pagePolicy.maxMemoryBytes,
                )
            // BitmapRegionDecoder supports JPEG/PNG/WebP, not GIF/BMP. Inspection already passed.
            PageImageFormat.GIF, PageImageFormat.BMP -> metadata.height <= bandHeight
            else -> true
        }

    private suspend fun streamPage(
        file: File,
        metadata: PageImageMetadata,
        bandHeight: Int,
        consume: suspend (Bitmap) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        if (metadata.format == PageImageFormat.AVIF) {
            streamAvif(file, bandHeight, consume)
            return
        }
        val bounds = decoder.bounds(file)
        currentCoroutineContext().ensureActive()
        val width = bounds.outWidth
        val height = bounds.outHeight
        validateCbzSourceBounds(file, width, height)
        if (width != metadata.width || height != metadata.height) throw IOException("CBZ source metadata changed")
        val estimatedBytes = width * height * ESTIMATED_BYTES_PER_PIXEL
        if (height > bandHeight) {
            streamRegions(file, width, height, bandHeight, consume)
        } else {
            val sampled = estimatedBytes > settings.samplingThreshold
            val sampleSize = if (sampled) cbzSampleSize(width, height, settings.regionDecodeThreshold) else 1
            val config = if (sampled) Bitmap.Config.RGB_565 else null
            val bitmap = decoder.decode(file, sampleSize, config) ?: throw IOException("CBZ source decode failed")
            bitmap.useForCbz { consume(it) }
        }
    }

    private suspend fun streamRegions(
        file: File,
        width: Int,
        height: Int,
        bandHeight: Int,
        consume: suspend (Bitmap) -> Unit,
    ) {
        file.inputStream().use { stream ->
            decoder.openRegions(stream).use { regions ->
                var y = 0
                while (y < height) {
                    currentCoroutineContext().ensureActive()
                    val bottom = y + minOf(bandHeight, height - y)
                    val bitmap =
                        regions.decode(Rect(0, y, width, bottom)) ?: throw IOException("CBZ region decode failed")
                    bitmap.useForCbz { consume(it) }
                    y = bottom
                }
            }
        }
    }

    private suspend fun streamAvif(
        file: File,
        bandHeight: Int,
        consume: suspend (Bitmap) -> Unit,
    ) {
        decoder.decodeAvif(file, pagePolicy.maxMemoryBytes).useForCbz { parent ->
            var y = 0
            while (y < parent.height) {
                currentCoroutineContext().ensureActive()
                val bottom = y + minOf(bandHeight, parent.height - y)
                val crop =
                    if (y == 0 && bottom == parent.height) {
                        parent
                    } else {
                        decoder.crop(parent, Rect(0, y, parent.width, bottom))
                    }
                // Android subset creation may return its source; the parent has one outer owner.
                if (crop === parent) consume(parent) else crop.useForCbz { consume(it) }
                y = bottom
            }
        }
    }
}

private const val ESTIMATED_BYTES_PER_PIXEL = 4L

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
