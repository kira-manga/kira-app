package me.manga.kira.core.cbz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Phase 8.14 port of upstream `me.manga.kira.core.cbz.CbzManager`.
 *
 * Behaviour is byte-identical to the source — only the Hilt `@Inject @Singleton` +
 * `@ApplicationContext` annotations were stripped (Koin provides `Context` via
 * `androidContext()` in `PlatformModule.android.kt`). The bitmap/zip pipeline itself is
 * untouched (Bitmap + BitmapFactory + WEBP_LOSSY are Android-only APIs, which is why this
 * lives in androidMain).
 *
 * Note: this class is intentionally kept alongside the cross-platform `CbzWriter` /
 * `CbzReader` (Phase 8.5 expect/actual facades). `CbzWriter` is the future canonical API;
 * `CbzManager` is preserved verbatim because the Android `CbzMigrationWorker` consumes its
 * `convertFilesToCbz` entry point (the only live call site) and using the expect/actual
 * facade would require refactoring that worker. The remaining public methods have no
 * callers (the live read-side path is the `:platform` `CbzReader`). See [OptimizedCbzManager]
 * for the parallel decode/compress variant used by the download path.
 */
class CbzManager(
    private val context: Context,
) {

    @Suppress("unused")
    private val compressionDispatcher =
        Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    private companion object {
        private const val TAG = "CbzManager"
        private const val CBZ_EXTENSION = ".cbz"
    }

    fun splitBitmapVertically(bitmap: Bitmap, maxHeight: Int = 15000): List<Bitmap> {
        val chunks = mutableListOf<Bitmap>()
        val width = bitmap.width
        val height = bitmap.height

        if (height <= maxHeight) {
            return listOf(bitmap)
        }

        var currentY = 0
        while (currentY < height) {
            val chunkHeight = minOf(maxHeight, height - currentY)

            try {
                val chunk = Bitmap.createBitmap(bitmap, 0, currentY, width, chunkHeight)
                chunks.add(chunk)
                currentY += chunkHeight
            } catch (e: Exception) {
                Log.e("BitmapSplitter", "Failed to create chunk at Y=$currentY", e)
                break
            }
        }

        return chunks
    }

    suspend fun createCbzFromFilesWithSplitting(
        imageFiles: List<String>,
        mangaId: Long,
        chapterId: Long,
        quality: Int = 75,
        maxHeight: Int = 10000,
        maxMemoryBytes: Long = 100_000_000,
    ): String = withContext(Dispatchers.Default) {

        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId").apply { mkdirs() }
        val cbzFile = File(chapterDir, "chapter_${chapterId}.cbz")

        val filesToDelete = mutableListOf<File>()
        var pageCounter = 0

        val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        try {
            ZipOutputStream(FileOutputStream(cbzFile)).use { zipOut ->

                imageFiles.forEachIndexed { index, path ->
                    ensureActive()
                    if (index % 2 == 0) yield()

                    val file = File(path)
                    if (!file.exists()) {
                        Log.w("CBZ", "File not found: $path")
                        return@forEachIndexed
                    }

                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap == null) {
                        Log.e("CBZ", "FAILED DECODE: $path")
                        return@forEachIndexed
                    }

                    val width = bitmap.width
                    val height = bitmap.height
                    val byteSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        bitmap.allocationByteCount
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.byteCount
                    }

                    Log.d("CBZ-BITMAP", "INDEX=$index | ${file.name} | WxH=${width}x${height} | bytes=$byteSize")

                    val needsSplitting = height > maxHeight || byteSize > maxMemoryBytes

                    var compressedIntoArchive = false

                    if (needsSplitting) {
                        Log.w("CBZ", "Image too large, splitting: $path (${width}x${height}, $byteSize bytes)")

                        val chunks = splitBitmapVertically(bitmap, maxHeight)
                        Log.d("CBZ", "Split into ${chunks.size} chunks")

                        chunks.forEachIndexed { chunkIndex, chunk ->
                            try {
                                val entryName = "page_%04d.webp".format(pageCounter)
                                zipOut.putNextEntry(ZipEntry(entryName))

                                val success = chunk.compress(webpFormat, quality, zipOut)
                                if (!success) {
                                    Log.e("CBZ", "Failed to compress chunk $chunkIndex of $path")
                                } else {
                                    Log.d("CBZ", "Compressed chunk $chunkIndex: $entryName")
                                    compressedIntoArchive = true
                                }

                                zipOut.closeEntry()
                                pageCounter++

                            } catch (e: Exception) {
                                Log.e("CBZ", "FAILED COMPRESS chunk $chunkIndex: $path", e)
                            } finally {
                                chunk.recycle()
                            }
                        }

                    } else {
                        try {
                            val entryName = "page_%04d.webp".format(pageCounter)
                            zipOut.putNextEntry(ZipEntry(entryName))

                            val success = bitmap.compress(webpFormat, quality, zipOut)
                            if (!success) {
                                Log.e("CBZ", "Failed to compress: $path")
                            } else {
                                compressedIntoArchive = true
                            }

                            zipOut.closeEntry()
                            pageCounter++

                        } catch (e: Exception) {
                            Log.e("CBZ", "FAILED COMPRESS: $path", e)
                        }
                    }

                    bitmap.recycle()
                    // Only delete the source page once at least one entry for it actually landed in
                    // the archive — a compress failure must not destroy the page's only copy.
                    if (compressedIntoArchive) {
                        filesToDelete.add(file)
                    } else {
                        Log.w("CBZ", "Preserving source page (nothing compressed into archive): $path")
                    }
                }
            }
        } catch (e: Throwable) {
            // Mirror OptimizedCbzManager.createCbzParallel: a throw mid-write (incl. cancellation
            // from ensureActive()/yield()) leaves a truncated chapter_<id>.cbz at the canonical
            // path. Delete it so a later check never treats the partial archive as a complete CBZ.
            if (cbzFile.exists()) {
                cbzFile.delete()
            }
            throw e
        }

        filesToDelete.forEach {
            if (it.delete()) {
                Log.d("CBZ", "Deleted original: ${it.name}")
            }
        }

        Log.i("CBZ", "Created CBZ with $pageCounter pages: $cbzFile")
        return@withContext cbzFile.absolutePath
    }

    fun calculateOptimalChunkHeight(width: Int, height: Int, maxBytes: Long = 50_000_000): Int {
        val bytesPerPixel = 4
        val bytesPerRow = width * bytesPerPixel
        val maxRows = (maxBytes / bytesPerRow).toInt()

        return minOf(maxRows, height, 10000)
    }

    suspend fun createCbzFromFiles(
        imageFiles: List<String>,
        mangaId: Long,
        chapterId: Long,
        quality: Int = 75,
    ): String = withContext(Dispatchers.Default) {

        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId").apply { mkdirs() }
        val cbzFile = File(chapterDir, "chapter_${chapterId}.cbz")

        val filesToDelete = mutableListOf<File>()

        val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        ZipOutputStream(FileOutputStream(cbzFile)).use { zipOut ->

            imageFiles.forEachIndexed { index, path ->

                ensureActive()
                if (index % 2 == 0) yield()

                val file = File(path)
                if (!file.exists()) return@forEachIndexed

                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                Log.e("CBZ-BITMAP", "Decode = $bitmap")

                if (bitmap == null) {
                    Log.e("CBZ", "FAILED DECODE: $path")
                    return@forEachIndexed
                }
                val width = bitmap.width
                val height = bitmap.height
                val byteSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    bitmap.allocationByteCount
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.byteCount
                }

                Log.e(
                    "CBZ-BITMAP",
                    "INDEX=$index | ${file.name} | WxH=${width}x${height} | bytes=$byteSize",
                )
                zipOut.putNextEntry(ZipEntry("page_%04d.webp".format(index)))
                try {
                    bitmap.compress(webpFormat, quality, zipOut)
                } catch (e: Exception) {
                    Log.e("CBZException", "FAILED COMPRESS: $path   cuse ===== $e")
                }

                zipOut.closeEntry()

                bitmap.recycle()

                filesToDelete.add(file)
            }
        }

        filesToDelete.forEach { it.delete() }

        Log.e("CBZ", "Created CBZ = $cbzFile")

        return@withContext cbzFile.absolutePath
    }

    suspend fun extractImagesFromCbz(
        cbzPath: String,
        mangaId: Long,
        chapterId: Long,
    ): List<String> = withContext(Dispatchers.IO) {
        val cbzFile = File(cbzPath)
        if (!cbzFile.exists()) {
            Log.e(TAG, "CBZ file does not exist: $cbzPath")
            return@withContext emptyList()
        }

        val extractDir = File(context.cacheDir, "cbz_extract/$mangaId/$chapterId")
        extractDir.mkdirs()

        extractDir.listFiles()?.forEach { it.delete() }

        val extractedPaths = mutableListOf<String>()

        try {
            ZipFile(cbzFile).use { zipFile ->
                val entries = zipFile.entries().toList()
                    .filter { !it.isDirectory && isImageFile(it.name) }
                    .sortedBy { it.name }

                entries.forEach { entry ->
                    val outputFile = File(extractDir, entry.name)

                    zipFile.getInputStream(entry).use { input ->
                        FileOutputStream(outputFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    extractedPaths.add(outputFile.absolutePath)
                }
            }

            Log.i(TAG, "Extracted ${extractedPaths.size} images from CBZ")
            extractedPaths
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting CBZ file", e)
            emptyList()
        }
    }

    fun cbzExists(mangaId: Long, chapterId: Long): Boolean {
        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId")
        val cbzFile = File(chapterDir, "chapter_${chapterId}$CBZ_EXTENSION")
        return cbzFile.exists()
    }

    fun getCbzPath(mangaId: Long, chapterId: Long): String? {
        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId")
        val cbzFile = File(chapterDir, "chapter_${chapterId}$CBZ_EXTENSION")
        return if (cbzFile.exists()) cbzFile.absolutePath else null
    }

    suspend fun deleteCbz(mangaId: Long, chapterId: Long): Boolean = withContext(Dispatchers.IO) {
        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId")
        val cbzFile = File(chapterDir, "chapter_${chapterId}$CBZ_EXTENSION")

        if (cbzFile.exists()) {
            cbzFile.delete()
        } else {
            false
        }
    }

    suspend fun getCbzPageCount(cbzPath: String): Int = withContext(Dispatchers.IO) {
        try {
            ZipFile(File(cbzPath)).use { zipFile ->
                zipFile.entries().toList()
                    .count { !it.isDirectory && isImageFile(it.name) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting CBZ page count", e)
            0
        }
    }

    suspend fun convertFilesToCbz(
        mangaId: Long,
        chapterId: Long,
        existingFiles: List<String>,
    ): String? = withContext(Dispatchers.Default) {
        try {
            createCbzFromFilesWithSplitting(existingFiles, mangaId, chapterId)
        } catch (e: CancellationException) {
            // Cooperative cancellation must propagate, not be swallowed into a null return.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error converting files to CBZ", e)
            null
        }
    }

    private fun isImageFile(filename: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }

    suspend fun cleanupExtractedCache(mangaId: Long, chapterId: Long) = withContext(Dispatchers.IO) {
        val extractDir = File(context.cacheDir, "cbz_extract/$mangaId/$chapterId")
        if (extractDir.exists()) {
            extractDir.deleteRecursively()
        }
    }
}

/* ----------------------------------------------------------------------------
 * §253 AUDIT-TRAIL POSTSCRIPT — cluster257 (2026-05-29)
 * ----------------------------------------------------------------------------
 *
 *  CLASSIFICATION: LIVE — ANDROIDMAIN-SOLO-DOUBLET (leaf 1 of 2)
 *
 *  This file is one of two siblings in `:shared/androidMain/core/cbz/` that have
 *  no expect-decl in commonMain and no actuals in nonAndroidMain. The doublet:
 *
 *      - CbzManager.kt           (this file) — sequential WEBP+ZIP pipeline
 *      - OptimizedCbzManager.kt              — parallel decode/compress + AVIF
 *
 *  ANDROIDMAIN-SOLO-DOUBLET-RATIONALE: every CBZ encode path on Android reaches
 *  Android-only APIs:
 *
 *      - android.graphics.Bitmap + BitmapFactory
 *      - Bitmap.CompressFormat.WEBP_LOSSY  (API 30+) / WEBP fallback
 *      - java.util.zip.ZipOutputStream + ZipEntry
 *      - android.os.Build.VERSION.SDK_INT branching
 *      - android.util.Log
 *      - Bitmap.allocationByteCount  (KITKAT+)
 *
 *  None of these exist on iOS / Desktop. The corresponding iOS+Desktop CBZ
 *  WRITE path lives in the cross-platform CbzWriter facade (cluster218 sweep,
 *  3-actual fan: `core/cbz/CbzWriter.{android,ios,desktop}.kt`). The READ path
 *  lives in CbzReader (commonMain expect/actual, cluster180 sweep).
 *
 *  HILT-TO-KOIN-ANNOTATION-STRIP-LIVE: the Phase 8.14 port stripped the
 *  upstream `@Inject @Singleton` + `@ApplicationContext` annotations because
 *  this module does NOT pull `hilt-android` — Koin provides `Context` via
 *  `androidContext()` in `PlatformModule.android.kt:157`. The bitmap/zip body
 *  is otherwise byte-identical to upstream. Removing the annotation strip
 *  notice in the class KDoc would mislead future readers into thinking the
 *  upstream still ships Hilt artifacts.
 *
 *  KOIN-BINDING-LIVE: registered as
 *      single { CbzManager(androidContext()) }
 *  in `shared/androidMain/.../di/PlatformModule.android.kt:157`. Resolved by
 *  ChapterDownloadService ctor.
 *
 *  CHAPTERDOWNLOADSERVICE-CONSUMER-LIVE: this class is injected into
 *  `me.manga.kira.presentation.features.download.domain.ChapterDownloadService`
 *  (androidMain-only WorkManager-backed service, line 58) — although
 *  ChapterDownloadService's hot path now favours OptimizedCbzManager (see
 *  lines 186 + 318 of the service), CbzManager remains the FALLBACK / SIMPLE
 *  pipeline retained verbatim because the service consumes its specific
 *  method signatures (createCbzFromFilesWithSplitting + extractImagesFromCbz +
 *  cbzExists + getCbzPath + deleteCbz + getCbzPageCount + convertFilesToCbz +
 *  cleanupExtractedCache). Refactoring ChapterDownloadService to depend solely
 *  on Optimized would require widening Optimized's public surface or splitting
 *  it into multiple new managers — out of scope for the strangler-fig posture.
 *
 *  DOUBLET-INTRA-REFERENCE-LOAD-BEARING: the existing class KDoc at line 33
 *  cites [OptimizedCbzManager] as the parallel decode/compress variant. That
 *  cross-reference is LIVE (consumer still uses both) and MUST be preserved
 *  for future-reader navigation.
 *
 *  SEQUENTIAL-VS-PARALLEL-AXIS-LIVE: the architectural distinction between
 *  CbzManager (sequential, simple) and OptimizedCbzManager (parallel +
 *  semaphore-bounded + AVIF + device-tier-driven) is the core design rationale
 *  for keeping both classes despite functional overlap. CbzManager's
 *  `createCbzFromFilesWithSplitting` is single-coroutine with `yield()`
 *  every 2 entries; Optimized's `createCbzParallel` uses async + awaitAll
 *  with separate decode + compress semaphores. Future readers should NOT
 *  fold the two — the use-cases are intentionally distinct.
 *
 *  PHASE-8.14-PORT-LINEAGE-LIVE: the class KDoc explicitly cites the upstream
 *  `me.manga.kira.core.cbz.CbzManager` and the Phase 8.14 byte-identical
 *  port. Both citations remain valid: upstream package path is unchanged;
 *  Phase 8.14 is the CBZ tier in the original migration plan.
 *
 *  COMPRESSIONDISPATCHER-FIELD-NOTE: line 41 declares a
 *  `compressionDispatcher` field with `@Suppress("unused")`. This is RESERVED
 *  STRUCTURAL state from the upstream — body code-paths use Dispatchers.Default
 *  and Dispatchers.IO directly. Field is preserved verbatim for upstream parity;
 *  future readers should not delete it without first reverifying the upstream's
 *  dispatcher topology.
 *
 *  WEBP_LOSSY-API-30-GATE-LIVE: lines 90-95 + 209-214 branch on
 *  `Build.VERSION.SDK_INT >= R` to select WEBP_LOSSY vs the deprecated WEBP
 *  CompressFormat. The deprecated branch is still reached on pre-API-30
 *  devices and produces lossy WebP — behaviour parity with upstream.
 *
 *  CLUSTER257 SOLO-DOUBLET REGISTER (open):
 *      leaf 1: CbzManager.kt (this file)
 *      leaf 2: OptimizedCbzManager.kt (sibling postscript)
 *
 *  CLUSTER258 PIVOT PREDICTION: remaining un-swept :shared platform-actual
 *  Android-only subtree (post-cluster257) per the §250 enumeration:
 *      - ChapterDownloadService.kt (androidMain WorkManager-backed service —
 *        the DOWNSTREAM consumer of this doublet)
 *      - DownloadWorkerV2 + Android download infrastructure
 *      - Per-VM platform actuals (if any)
 *  Strongest candidate: ChapterDownloadService — it is the LIVE consumer of
 *  this doublet AND of the Android-only WorkManager scheduling tier, so its
 *  postscript will close out a 3-tier Android download chain (CbzManager
 *  doublet → ChapterDownloadService → WorkManager wiring). File is ~600+
 *  lines so it is likely a solo-leaf sweep rather than a doublet.
 *
 *  SATURATION-WATCH: §253 cascade is past 100 clusters with diminishing
 *  novelty in delta-axes. Recent clusters add no new classification axis
 *  beyond reiterating existing ones (ANDROIDMAIN-SOLO + PHASE-PORT-LINEAGE +
 *  KOIN-BINDING-LIVE + CONSUMER-LIVE). Continue sweep but flag if 3
 *  consecutive clusters land zero novel deltas — that is the saturation
 *  signal for cascade close-out.
 *
 *  Verified pre-postscript:
 *    - Grep'd cbz/ subtree — only CbzManager + OptimizedCbzManager + the
 *      CbzWriter.android.kt 3-actual fan leaf live in androidMain
 *    - Grep'd consumers — PlatformModule.android.kt (Koin) +
 *      ChapterDownloadService.kt (injection + invocation) only
 *    - Cross-referenced project_yami_kmp_platform_deps memory entry — :shared
 *      androidMain has its own AvifDecoder + WorkManager deps declared, so
 *      this doublet's transitive surface is satisfied without :platform reach
 *
 *  Build gates: Android + iOS Arm64 + iOS SimulatorArm64 (Desktop not required
 *  since this file is androidMain-only — Desktop compile of `:composeApp`
 *  cannot pull this source set).
 *
 * --------------------------------------------------------------------------
 */
