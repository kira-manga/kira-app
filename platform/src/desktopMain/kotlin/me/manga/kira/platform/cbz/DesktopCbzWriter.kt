package me.manga.kira.platform.cbz

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
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Desktop actual for [CbzWriter].
 *
 * Pages are **transcoded to WebP** via [SkiaWebpEncoder] (skiko) at the requested [quality] — the
 * non-Android analogue of Android's `Bitmap.compress(WEBP_LOSSY)`, so the "convert to WebP" storage
 * saving actually applies on Desktop. This supersedes two earlier approaches: ImageIO→PNG (broke —
 * `javax.imageio` ships no WebP *decoder*, so `.webp` pages produced an empty 0-page CBZ) and the
 * stop-gap verbatim STORE (worked but never shrank anything). skiko's Skia decodes jpg/png/webp/gif/
 * bmp and encodes WebP, so re-encoding is both possible and lossy-compressing here.
 *
 * A page Skia cannot decode (e.g. AVIF — skiko ships no libavif) is stored **verbatim under its true
 * extension** (never relabelled `.webp`); it is logged and stays present + counted
 * (`DefaultCbzReader`'s allow-list includes those extensions). Tall webtoon pages are split into
 * vertical bands by the encoder so peak memory stays bounded — [createCbzWithSplitting] passes its
 * real [maxHeight] rather than delegating to [createCbz] (whole-page encode, `maxHeight = MAX_VALUE`).
 *
 * STORED entries (no deflate) avoid recompressing already-compressed WebP bytes. Output location +
 * naming match the other actuals (`AppFileSystem.chapterDir` + `chapter_<chapterId>.cbz`,
 * `page_NNNN.<ext>` entries); missing source files are warn-and-skipped; source pages are deleted
 * after a successful write; the work runs on `Dispatchers.Default` with cooperative cancellation.
 */
class DesktopCbzWriter(private val fs: AppFileSystem) : CbzWriter {

    private val log = Logger.withTag(TAG)

    override suspend fun createCbz(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int,
    ): Path = archive(imagePaths, mangaId, chapterId, quality, maxHeight = Int.MAX_VALUE)

    override suspend fun createCbzWithSplitting(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int,
        maxHeight: Int,
        maxMemoryBytes: Long,
    ): Path = archive(imagePaths, mangaId, chapterId, quality, maxHeight, maxMemoryBytes)

    private suspend fun archive(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int,
        maxHeight: Int,
        maxMemoryBytes: Long = CbzWriter.DEFAULT_MAX_MEMORY_BYTES,
    ): Path = withContext(Dispatchers.Default) {
        val cbzPath = ensureCbzDestination(mangaId, chapterId)
        val cbzFile = File(cbzPath.toString())
        val filesToDelete = mutableListOf<File>()
        var pageCounter = 0

        // On ANY failure mid-write (cancellation, disk-full, a STORED putNextEntry/write error) the
        // partial/corrupt .cbz is deleted before rethrowing. A STORED ZipOutputStream cannot "skip"
        // a failed entry — once putNextEntry opened one, every later putNextEntry and close() rethrow,
        // so there is no per-entry recovery; we fail the whole archive instead. This keeps the
        // caller's fallback-to-loose-pages clean: no stray .cbz remains to be double-counted by the
        // post-download folderSize() that captures sizeBytes. Loose source pages are deleted ONLY
        // after a fully successful archive.
        try {
            ZipOutputStream(FileOutputStream(cbzFile)).use { zipOut ->
                zipOut.setMethod(ZipOutputStream.STORED)
                imagePaths.forEachIndexed { index, path ->
                    ensureActive()
                    if (index % YIELD_EVERY_N_PAGES == 0) yield()

                    val file = File(path.toString())
                    if (!file.exists()) {
                        log.w { "File not found, skipping page: $path" }
                        return@forEachIndexed
                    }
                    // A read failure (no entry opened yet) is safe to skip — it does not corrupt the
                    // stream. A failure AFTER putNextEntry propagates to the outer catch.
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                    if (bytes == null) {
                        log.e { "FAILED READ, skipping page: $path" }
                        return@forEachIndexed
                    }
                    // Transcode to WebP (one entry per band); fall back to honest verbatim on failure.
                    val webp = SkiaWebpEncoder.encodeToWebpPages(bytes, quality, maxHeight, maxMemoryBytes)
                    if (webp != null) {
                        webp.forEach { writeStoredEntry(zipOut, pageEntryName(pageCounter++, "webp"), it) }
                    } else {
                        val ext = verbatimPageExtension(file.name, bytes)
                        log.w { "WebP unavailable for $path; storing verbatim as .$ext" }
                        writeStoredEntry(zipOut, pageEntryName(pageCounter++, ext), bytes)
                    }
                    filesToDelete += file
                }
            }
        } catch (t: Throwable) {
            runCatching { cbzFile.delete() }
            throw t
        }

        // Every requested page was skipped (all missing/unreadable): a 0-page archive must not be
        // reported as success — delete it and throw so the caller's per-chapter fallback keeps the
        // loose source pages referenced rather than rewriting the chapter to an empty .cbz.
        if (pageCounter == 0 && imagePaths.isNotEmpty()) {
            runCatching { cbzFile.delete() }
            error("CBZ write produced 0 pages from ${imagePaths.size} source paths")
        }

        filesToDelete.forEach { it.delete() }
        log.i { "Created CBZ with $pageCounter pages at ${cbzFile.absolutePath}" }
        cbzPath
    }

    /** Append one STORED (uncompressed) entry — STORED requires size/compressedSize/crc up front. */
    private fun writeStoredEntry(zipOut: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = CRC32().apply { update(data) }.value
        }
        zipOut.putNextEntry(entry)
        zipOut.write(data)
        zipOut.closeEntry()
    }

    private fun ensureCbzDestination(mangaId: Long, chapterId: Long): Path {
        val dir = fs.chapterDir(mangaId, chapterId)
        fs.fileSystem().createDirectories(dir)
        return dir / "chapter_$chapterId.cbz"
    }

    private fun pageEntryName(index: Int, extension: String): String {
        val padded = index.toString().padStart(PAGE_NUMBER_PAD_WIDTH, '0')
        return "page_$padded.$extension"
    }

    private companion object {
        const val TAG = "CbzWriter"
        const val YIELD_EVERY_N_PAGES = 2
        const val PAGE_NUMBER_PAD_WIDTH = 4
    }
}

/*
 * Audit-trail note (2026-06-02): the original Phase-5.w.4 / Task #184 Desktop actual decoded each
 * page via javax.imageio.ImageIO and re-encoded it as PNG into the ZIP. ImageIO ships no WebP
 * decoder by default, so WebP pages failed to decode and the archive came out with 0 pages — broken
 * downloads on Desktop. Replaced with a lossless verbatim STORE-method approach (mirrored the iOS
 * actual). The 3-actual CbzWriter fan (Android real-WebP re-encode / Desktop+iOS verbatim STORE)
 * still implements the commonMain CbzWriter interface; the chapterDir output convention +
 * page_NNNN.webp entry naming are preserved verbatim so DefaultCbzReader reads all three identically.
 *
 * Audit-trail note (2026-06-08): the verbatim STORE described above never shrank anything — "convert
 * to WebP" was a no-op on Desktop (and iOS) while real on Android, the owner's bug. Both non-Android
 * actuals now transcode to WebP via the shared skiko-backed SkiaWebpEncoder (nonAndroidMain), with an
 * honest verbatim fallback (true extension, not a cosmetic .webp) for formats skiko can't decode
 * (AVIF). The fan is now Android real-WebP / Desktop+iOS skiko-WebP; entry naming is page_NNNN.<ext>
 * (.webp for transcoded pages, true ext for fallbacks). avif was added to DefaultCbzReader's
 * inclusion allow-list so honest-named fallback pages stay counted.
 */
