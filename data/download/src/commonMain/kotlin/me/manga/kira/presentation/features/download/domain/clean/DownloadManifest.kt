package me.manga.kira.presentation.features.download.domain.clean

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.manga.kira.platform.download.BgDownloadLog
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import okio.buffer
import okio.use

/**
 * Per-chapter download manifest, persisted as `chapter_<chapterId>/manifest.json` next to the
 * downloaded page files (background-downloads M3).
 *
 * It records the resolved page list (so a resume after suspension/force-quit never re-scrapes the
 * source), the expected page count (so completion can be decided without re-resolving), and per-page
 * failure counts (so retry is bounded). Written when a chapter is prepared; deleted on successful
 * finalize / chapter delete. Lives in `commonMain` (no platform types) so the reconciler that reads
 * it stays fully unit-testable.
 */
@Serializable
data class DownloadManifest(
    val mangaId: Long,
    val chapterId: Long,
    val api: String,
    val pages: List<ManifestPage>,
)

@Serializable
data class ManifestPage(
    val index: Int,
    val url: String,
    val headers: Map<String, String>,
    /** Number of failed transfer attempts so far (drives bounded retry; see [BackgroundReconciler]). */
    val attempts: Int = 0,
)

/**
 * Reads/writes [DownloadManifest] files under each chapter directory. Tolerant: a missing or corrupt
 * manifest reads back as `null` (the engine then falls back to re-resolving the chapter). Traced
 * under the `KiraBgDownload` tag, including the manifest path.
 */
class DownloadManifestStore(private val appFileSystem: AppFileSystem) {

    private val system get() = appFileSystem.fileSystem()

    private fun path(mangaId: Long, chapterId: Long) =
        appFileSystem.chapterDir(mangaId, chapterId) / MANIFEST_NAME

    /** True when a manifest file exists for the chapter — a cheap fs probe, no read/parse. Used by
     *  the resolve-ahead window check, which runs per pump and must not pay JSON parsing. */
    fun exists(mangaId: Long, chapterId: Long): Boolean = system.exists(path(mangaId, chapterId))

    fun read(mangaId: Long, chapterId: Long): DownloadManifest? {
        val p = path(mangaId, chapterId)
        if (!system.exists(p)) {
            BgDownloadLog.log("manifest.store.read.miss", "chapterId" to chapterId, "path" to p.toString())
            return null
        }
        val manifest = runCatching {
            val text = system.source(p).buffer().use { it.readUtf8() }
            json.decodeFromString(DownloadManifest.serializer(), text)
        }.onFailure {
            BgDownloadLog.warn("manifest.store.read.unreadable", "chapterId" to chapterId, "path" to p.toString())
        }.getOrNull()
        if (manifest != null) {
            BgDownloadLog.log("manifest.store.read.hit", "chapterId" to chapterId, "pages" to manifest.pages.size, "path" to p.toString())
        }
        return manifest
    }

    fun write(manifest: DownloadManifest) {
        val dir = appFileSystem.chapterDir(manifest.mangaId, manifest.chapterId)
        runCatching {
            system.createDirectories(dir)
            val text = json.encodeToString(DownloadManifest.serializer(), manifest)
            system.sink(dir / MANIFEST_NAME).buffer().use { it.writeUtf8(text) }
            BgDownloadLog.log("manifest.store.write", "chapterId" to manifest.chapterId, "pages" to manifest.pages.size, "path" to (dir / MANIFEST_NAME).toString())
        }.onFailure {
            BgDownloadLog.error(it, "manifest.store.write.failed", "chapterId" to manifest.chapterId)
        }
    }

    fun delete(mangaId: Long, chapterId: Long) {
        val p = path(mangaId, chapterId)
        runCatching {
            if (system.exists(p)) {
                system.delete(p)
                BgDownloadLog.log("manifest.store.delete", "chapterId" to chapterId, "path" to p.toString())
            }
        }
    }

    /** Increment the attempt count for [pageIndex]; returns the new count (0 when there is no manifest). */
    fun incrementAttempt(mangaId: Long, chapterId: Long, pageIndex: Int): Int {
        val manifest = read(mangaId, chapterId) ?: return 0
        var newCount = 0
        val updated = manifest.copy(
            pages = manifest.pages.map { page ->
                if (page.index == pageIndex) {
                    newCount = page.attempts + 1
                    page.copy(attempts = newCount)
                } else {
                    page
                }
            },
        )
        write(updated)
        BgDownloadLog.log("manifest.store.attemptIncremented", "chapterId" to chapterId, "pageIndex" to pageIndex, "attempt" to newCount)
        return newCount
    }

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
