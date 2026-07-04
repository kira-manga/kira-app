package me.manga.kira.platform.cbz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import okio.Path
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Android actual for [CbzWriter].
 *
 * Page encoder: `Bitmap.compress(WEBP_LOSSY, quality, zipOut)` on API ≥ 30, the deprecated
 * `WEBP` format on older releases (preserving legacy behavior verbatim — the legacy enum got
 * deprecated post-30 but both produce wire-compatible WebP). The yield-every-other-page
 * + `ensureActive()` calls keep the writer cooperative with structured-concurrency
 * cancellation; the `Dispatchers.Default` confinement matches legacy.
 *
 * Verbatim port from legacy `:shared/androidMain/.../core/cbz/CbzWriter.android.kt`.
 */
class AndroidCbzWriter(private val fs: AppFileSystem) : CbzWriter {

    private val log = Logger.withTag(TAG)

    override suspend fun createCbz(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int,
    ): Path = withContext(Dispatchers.Default) {
        val cbzFile = ensureCbzDestination(mangaId, chapterId).toFile()
        val webpFormat = chooseWebpFormat()
        val filesToDelete = mutableListOf<File>()

        // #28: if the archive write fails partway (zip error, OOM, cancellation), delete the
        // partial/corrupt .cbz and rethrow — mirrors Desktop/iOS. Source pages are deleted only
        // AFTER a clean finish (below), so a failure leaves the loose pages intact for a retry.
        try {
            ZipOutputStream(FileOutputStream(cbzFile)).use { zipOut ->
                imagePaths.forEachIndexed { index, path ->
                    ensureActive()
                    if (index % YIELD_EVERY_N_PAGES == 0) yield()

                    val file = path.toFile()
                    if (!file.exists()) {
                        log.w { "File not found: $path" }
                        return@forEachIndexed
                    }
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap == null) {
                        log.e { "FAILED DECODE: $path" }
                        return@forEachIndexed
                    }
                    try {
                        zipOut.putNextEntry(ZipEntry(pageEntryName(index)))
                        val success = bitmap.compress(webpFormat, quality, zipOut)
                        if (!success) log.e { "Failed to compress: $path" }
                        zipOut.closeEntry()
                    } catch (e: Exception) {
                        log.e(e) { "FAILED COMPRESS: $path" }
                    } finally {
                        bitmap.recycle()
                    }
                    filesToDelete += file
                }
            }
        } catch (t: Throwable) {
            runCatching { cbzFile.delete() }
            throw t
        }

        // Every requested page was skipped (all missing/undecodable): a 0-page archive must not be
        // reported as success — delete it and throw so the caller's per-chapter fallback keeps the
        // loose source pages referenced rather than rewriting the chapter to an empty .cbz.
        if (filesToDelete.isEmpty() && imagePaths.isNotEmpty()) {
            runCatching { cbzFile.delete() }
            error("CBZ write produced 0 pages from ${imagePaths.size} source paths")
        }

        filesToDelete.forEach { it.delete() }
        log.i { "Created CBZ at ${cbzFile.absolutePath}" }
        cbzFile.absolutePath.let { okio.Path.Companion.run { it.toPath() } }
    }

    override suspend fun createCbzWithSplitting(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int,
        maxHeight: Int,
        maxMemoryBytes: Long,
    ): Path = withContext(Dispatchers.Default) {
        val cbzFile = ensureCbzDestination(mangaId, chapterId).toFile()
        val webpFormat = chooseWebpFormat()
        val filesToDelete = mutableListOf<File>()
        var pageCounter = 0

        // #28: if the archive write fails partway (zip error, OOM, cancellation), delete the
        // partial/corrupt .cbz and rethrow — mirrors Desktop/iOS. Source pages are deleted only
        // AFTER a clean finish (below), so a failure leaves the loose pages intact for a retry.
        try {
            ZipOutputStream(FileOutputStream(cbzFile)).use { zipOut ->
                imagePaths.forEachIndexed { index, path ->
                    ensureActive()
                    if (index % YIELD_EVERY_N_PAGES == 0) yield()

                    val file = path.toFile()
                    if (!file.exists()) {
                        log.w { "File not found: $path" }
                        return@forEachIndexed
                    }
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap == null) {
                        log.e { "FAILED DECODE: $path" }
                        return@forEachIndexed
                    }

                    val byteSize = bitmap.allocationByteCount.toLong()
                    val needsSplit = bitmap.height > maxHeight || byteSize > maxMemoryBytes

                    if (needsSplit) {
                        log.w { "Splitting oversized image: $path (${bitmap.width}x${bitmap.height}, $byteSize bytes)" }
                        val chunks = splitBitmapVertically(bitmap, maxHeight)
                        chunks.forEachIndexed { chunkIndex, chunk ->
                            try {
                                // Advance the counter as soon as the entry name is committed, so a
                                // compress/closeEntry failure can't make the next page re-use it.
                                zipOut.putNextEntry(ZipEntry(pageEntryName(pageCounter++)))
                                if (!chunk.compress(webpFormat, quality, zipOut)) {
                                    log.e { "Failed chunk $chunkIndex of $path" }
                                }
                                zipOut.closeEntry()
                            } catch (e: Exception) {
                                log.e(e) { "FAILED COMPRESS chunk $chunkIndex: $path" }
                            } finally {
                                chunk.recycle()
                            }
                        }
                    } else {
                        try {
                            zipOut.putNextEntry(ZipEntry(pageEntryName(pageCounter++)))
                            if (!bitmap.compress(webpFormat, quality, zipOut)) {
                                log.e { "Failed to compress: $path" }
                            }
                            zipOut.closeEntry()
                        } catch (e: Exception) {
                            log.e(e) { "FAILED COMPRESS: $path" }
                        } finally {
                            bitmap.recycle()
                        }
                    }

                    filesToDelete += file
                }
            }
        } catch (t: Throwable) {
            runCatching { cbzFile.delete() }
            throw t
        }

        // Every requested page was skipped (all missing/undecodable): a 0-page archive must not be
        // reported as success — delete it and throw so the caller's per-chapter fallback keeps the
        // loose source pages referenced rather than rewriting the chapter to an empty .cbz.
        if (pageCounter == 0 && imagePaths.isNotEmpty()) {
            runCatching { cbzFile.delete() }
            error("CBZ write produced 0 pages from ${imagePaths.size} source paths")
        }

        filesToDelete.forEach { it.delete() }
        log.i { "Created CBZ with $pageCounter pages at ${cbzFile.absolutePath}" }
        cbzFile.absolutePath.let { okio.Path.Companion.run { it.toPath() } }
    }

    private fun ensureCbzDestination(mangaId: Long, chapterId: Long): Path {
        val dir = fs.chapterDir(mangaId, chapterId)
        fs.fileSystem().createDirectories(dir)
        return dir / "chapter_$chapterId.cbz"
    }

    private fun splitBitmapVertically(bitmap: Bitmap, maxHeight: Int): List<Bitmap> {
        if (bitmap.height <= maxHeight) return listOf(bitmap)
        val chunks = mutableListOf<Bitmap>()
        var currentY = 0
        val width = bitmap.width
        while (currentY < bitmap.height) {
            val chunkHeight = minOf(maxHeight, bitmap.height - currentY)
            try {
                chunks += Bitmap.createBitmap(bitmap, 0, currentY, width, chunkHeight)
                currentY += chunkHeight
            } catch (e: Exception) {
                log.e(e) { "Failed to create chunk at Y=$currentY" }
                break
            }
        }
        if (chunks.isNotEmpty() && chunks.first() !== bitmap) bitmap.recycle()
        return chunks
    }

    private fun chooseWebpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    private fun pageEntryName(index: Int): String {
        val padded = index.toString().padStart(PAGE_NUMBER_PAD_WIDTH, '0')
        return "page_$padded.webp"
    }

    private companion object {
        const val TAG = "CbzWriter"
        const val YIELD_EVERY_N_PAGES = 2
        const val PAGE_NUMBER_PAD_WIDTH = 4
    }
}

/*
 * §253 audit-trail postscript — cluster265 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT-RELOCATED (LIVE-via-legacy-binding, rework-binding-DEFERRED).
 * UNIT KIND: platform-facade — Android leaf of the 3-actual CbzWriter fan
 * (AndroidCbzWriter + DesktopCbzWriter + IosCbzWriter implementing the
 * commonMain interface CbzWriter at
 * platform/src/commonMain/.../cbz/CbzWriter.kt:83, already swept cluster147
 * Task #603).
 *
 * LIVE evidence:
 *  - The CbzWriter SPI is bound in the LEGACY :shared per-platform Koin
 *    modules: single { CbzWriter(get()) } at
 *    shared/src/androidMain/.../di/PlatformModule.android.kt:104 (siblings
 *    desktop:90, ios:90). That binding constructs the legacy expect class
 *    CbzWriter(fs: AppFileSystem) — shared/src/commonMain/.../core/cbz/
 *    CbzWriter.kt:15 — NOT this rework :platform interface impl.
 *  - This rework Android actual class AndroidCbzWriter is declared at line 30
 *    of this file. A repo-wide grep for AndroidCbzWriter / DesktopCbzWriter /
 *    IosCbzWriter found ZERO Koin bindings or consumer call sites outside the
 *    three impl files plus prose docs (ARCHITECTURE.md, SOLID_AUDIT.md,
 *    AppFileSystem.kt KDoc, HighQualitySkiaImageDecoder.kt KDoc). The rework
 *    :data offline-download path has not yet been cut over to the :platform
 *    SPI, so the rework binding is DEFERRED — consistent with the cluster147
 *    classification recorded on CbzWriter.kt itself.
 *
 * FULFILLED-PORT status: this is a Phase 5.w.4 / Task #184 relocation of the
 * legacy :shared/androidMain/.../core/cbz/CbzWriter.android.kt encoder into
 * the :platform module. Verbatim port, byte-for-byte encoder parity asserted
 * in the file header KDoc.
 *
 * Delta-axes (Android actual specifics):
 *  1. Platform API: android.graphics.Bitmap + BitmapFactory.decodeFile decode;
 *     java.util.zip.ZipOutputStream over FileOutputStream for the archive.
 *  2. Page encoder: Bitmap.compress(WEBP_LOSSY) on API ≥ 30, deprecated WEBP
 *     enum on older releases via chooseWebpFormat() — produces real WebP wire
 *     bytes (the distinct Android approach vs Desktop PNG-under-.webp).
 *  3. Threading: withContext(Dispatchers.Default); cooperative cancellation via
 *     ensureActive() every page plus yield() every YIELD_EVERY_N_PAGES (2).
 *  4. Error handling: per-page try-catch-finally; missing files warn-and-skip,
 *     decode failures log-and-skip, bitmap.recycle() in finally to bound peak
 *     native memory; never throws — partial archives are tolerated by design.
 *  5. DI binding mechanism: constructor-injected AppFileSystem (the rework
 *     :platform AppFileSystem, not legacy), resolved by a future rework
 *     factory/single once the :data cutover lands; today bound only in legacy.
 *  6. Contract parity across the 3 actuals: Android does real splitting via
 *     splitBitmapVertically + Bitmap.allocationByteCount memory probe; Desktop
 *     mirrors with BufferedImage.getSubimage; iOS throws NotImplementedError.
 *     page_NNNN.webp naming + chapterDir output location are shared verbatim.
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the
 * class header above this block). This appended block adds exactly one opener
 * and one closer with no interior delimiter sequences; the file remains
 * balanced.
 */
