package me.manga.kira.platform.cbz

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.manga.kira.platform.download.BgDownloadLog
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * iOS actual for [CbzWriter].
 *
 * Produces a real, readable CBZ as a pure-Kotlin STORE-method ZIP, assembled by hand via
 * [StoreZipWriter] on an okio sink (Kotlin-Native has no `java.util.zip`, and Foundation exposes no
 * public stream-oriented ZIP API).
 *
 * Pages are **transcoded to WebP** via [SkiaWebpEncoder] (skiko) at the requested [quality], the
 * non-Android analogue of Android's `Bitmap.compress(WEBP_LOSSY)`, so the "convert to WebP" storage
 * saving actually applies on iOS. A page that Skia cannot decode (e.g. AVIF — skiko ships no
 * libavif) is stored **verbatim under its true extension** (never relabelled `.webp`); it is logged
 * and remains present + counted (`DefaultCbzReader`'s allow-list includes those extensions). Tall
 * webtoon pages are split into vertical bands by the encoder so peak memory stays bounded —
 * [createCbzWithSplitting] therefore passes its real [maxHeight] rather than delegating to
 * [createCbz] (which encodes whole pages, `maxHeight = Int.MAX_VALUE`).
 *
 * Behaviour matches the Desktop actual for cross-platform parity: same output location
 * (`AppFileSystem.chapterDir` + `chapter_<chapterId>.cbz`), `page_NNNN.<ext>` entry naming, missing
 * source files warn-and-skipped, source files deleted after a successful encode, and the work runs
 * on `Dispatchers.Default` with cooperative cancellation. STORE (no deflate) stays correct — WebP is
 * already compressed.
 *
 * Construction takes an [AppFileSystem] so the conventional output location can be resolved
 * without callers threading path roots through every layer.
 */
class IosCbzWriter(private val fs: AppFileSystem) : CbzWriter {

    private val log = Logger.withTag(TAG)
    private val system: FileSystem get() = fs.fileSystem()

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
        // M3: write to a sibling `.part`, then atomically rename to the final `.cbz` on success. A
        // process-kill mid-write leaves only the `.part` (overwritten on the next attempt) — never a
        // truncated `.cbz` that would read as a valid-but-corrupt archive.
        val tempPath = cbzPath.parent!! / "${cbzPath.name}.part"
        // B7: re-derive each source page under the LIVE chapter dir. Callers may pass absolute paths
        // captured at download time; on iOS the sandbox container UUID changes across reinstall/restore,
        // so a stored absolute path goes stale while the file still exists under chapterDir/<filename>.
        // The background finalize already passes live paths (this is a no-op there); the manual Yami
        // Compressor passes stored localImagePaths, which this rescues from a silent 0-page skip+failure.
        val chapterDir = cbzPath.parent!!
        val sources = imagePaths.map { p ->
            PagePathRederivation.resolveSourcePage(stored = p, chapterDir = chapterDir, exists = system::exists)
        }
        val filesToDelete = mutableListOf<Path>()
        var pageCounter = 0
        BgDownloadLog.log("cbz.partWrite.start", "chapterId" to chapterId, "tempPath" to tempPath.toString(), "sourcePages" to imagePaths.size)

        // #28: if the archive write fails partway (sink/zip error, OOM, cancellation), delete the
        // partial/corrupt .cbz and rethrow — mirrors DesktopCbzWriter. Source pages are deleted only
        // AFTER a clean finish (below), so a failure leaves the loose pages intact for a retry.
        try {
            system.sink(tempPath).buffer().use { sink ->
                val zip = StoreZipWriter(sink)
                sources.forEachIndexed { index, path ->
                    ensureActive()
                    if (index % YIELD_EVERY_N_PAGES == 0) yield()

                    if (!system.exists(path)) {
                        log.w { "File not found: $path" }
                        return@forEachIndexed
                    }
                    // A read failure (no entry opened yet) is safe to skip — it does not corrupt the
                    // stream. A failure AFTER writeEntry propagates to the outer catch (deletes the
                    // partial .cbz, keeps loose pages, rethrows) — mirrors DesktopCbzWriter; a
                    // per-page catch here would seal a page-short archive yet still delete its source.
                    val bytes = runCatching {
                        system.source(path).buffer().use { it.readByteArray() }
                    }.getOrNull()
                    if (bytes == null) {
                        log.e { "FAILED READ: $path" }
                        return@forEachIndexed
                    }
                    // iOS encodes WebP via libwebp + ImageIO (IosLibWebpEncoder), NOT skiko — the Skia
                    // path's Kotlin/Native heap bitmaps caused the COMPRESSING-stage GC stalls. Same WebP
                    // output + banding + verbatim-on-null contract, so this is a drop-in swap. The
                    // IosWebpEncoderFlags toggle keeps the Skia path reachable for the A/B benchmark;
                    // Desktop always uses SkiaWebpEncoder.
                    val webp = if (IosWebpEncoderFlags.USE_LIBWEBP) {
                        IosLibWebpEncoder.encodeToWebpPages(bytes, quality, maxHeight, maxMemoryBytes)
                    } else {
                        SkiaWebpEncoder.encodeToWebpPages(bytes, quality, maxHeight, maxMemoryBytes)
                    }
                    if (webp != null) {
                        webp.forEach { zip.writeEntry(pageEntryName(pageCounter++, "webp"), it) }
                    } else {
                        val ext = verbatimPageExtension(path.name, bytes)
                        log.w { "WebP unavailable for $path; storing verbatim as .$ext" }
                        zip.writeEntry(pageEntryName(pageCounter++, ext), bytes)
                    }
                    filesToDelete += path
                }
                zip.finish()
            }
        } catch (t: Throwable) {
            runCatching { system.delete(tempPath) }
            throw t
        }

        // Every requested page was skipped (all missing/unreadable): a 0-page archive must not be
        // reported as success — delete it and throw so the caller's per-chapter fallback keeps the
        // loose source pages referenced rather than rewriting the chapter to an empty .cbz.
        if (pageCounter == 0 && imagePaths.isNotEmpty()) {
            runCatching { system.delete(tempPath) }
            error("CBZ write produced 0 pages from ${imagePaths.size} source paths")
        }

        // B2-durable: PUBLISH the archive (atomic `.part` -> `.cbz`) BEFORE deleting the loose source
        // pages. The old order deleted loose first, so a process-kill between the delete and the rename
        // left the chapter with NEITHER artifact (loose gone, only a `.part` that reads as nothing) —
        // unrecoverable data loss. After the rename the `.cbz` is the durable copy and the loose pages
        // are redundant, so deleting them is always safe; a kill before the rename simply retries from
        // the still-present loose pages. POSIX rename replaces an existing target; the getOrElse fallback
        // covers a re-finalize where the destination already exists on a FS that refuses to overwrite.
        runCatching { system.atomicMove(tempPath, cbzPath) }.getOrElse {
            BgDownloadLog.warn("cbz.atomicRename.replaceExisting", "chapterId" to chapterId)
            runCatching { system.delete(cbzPath) }
            system.atomicMove(tempPath, cbzPath)
        }
        BgDownloadLog.log("cbz.atomicRename.success", "chapterId" to chapterId, "finalPath" to cbzPath.toString(), "pages" to pageCounter)
        filesToDelete.forEach { runCatching { system.delete(it) } }
        BgDownloadLog.log("cbz.loosePagesDeleted", "chapterId" to chapterId, "count" to filesToDelete.size)
        log.i { "Created CBZ with $pageCounter pages at $cbzPath" }
        cbzPath
    }

    private fun ensureCbzDestination(mangaId: Long, chapterId: Long): Path {
        val dir = fs.chapterDir(mangaId, chapterId)
        system.createDirectories(dir)
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
