package me.manga.kira.platform.cbz

import co.touchlab.kermit.Logger
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/**
 * Cross-platform [CbzReader] backed entirely by `okio.FileSystem.openZip`. No platform-specific
 * actuals required — okio's ZIP support works on Android (JVM), iOS (Native), and Desktop (JVM)
 * since okio 3.9.
 *
 * Verbatim port from legacy `:shared/commonMain/.../core/cbz/CbzReader.kt`. The only API-shape
 * change is the legacy plain-class becoming an explicit `: CbzReader` implementation so consumers
 * can mock the interface.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster147.staleKdocSweep.cascade,
 * Task #603, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-seventieth sibling of the cluster57-146
 * sweep — third file of the wave-26 :platform tier cluster147 5-leaf
 * cbz batch alongside CbzWriter plus CbzReader plus CbzSettings plus
 * getCbzSettings):
 *  (a) "Cross-platform-CbzReader-backed-entirely-by-okio.FileSystem.
 *  openZip + No-platform-specific-actuals-required-okio-s-ZIP-support-
 *  works-on-Android-JVM-iOS-Native-and-Desktop-JVM-since-okio-3.9" —
 *  LIVE-NOT-STALE. Verified: this class lives in commonMain (no per-
 *  platform actual exists); imports only okio.FileSystem + okio.Path
 *  + okio.openZip + okio.buffer + okio.use plus stdlib coroutines —
 *  zero per-target API surface in the impl. The okio 3.9+ contract
 *  on FileSystem.openZip across Android/iOS/Desktop holds; the rework
 *  okio dep version is satisfied (verified across cluster144 sweep
 *  of AppFileSystem which co-depends on okio).
 *  (b) "Verbatim-port-from-legacy-:shared-commonMain-core-cbz-CbzReader.
 *  kt + The-only-API-shape-change-is-the-legacy-plain-class-becoming-
 *  an-explicit-:-CbzReader-implementation-so-consumers-can-mock-the-
 *  interface" — LIVE-NOT-STALE plus PARTIALLY-FULFILLED-FORECAST.
 *  Verified: the impl byte-for-byte matches legacy line-by-line modulo
 *  the `: CbzReader` interface conformance addition (verified: lines
 *  29-124 of the impl mirror the legacy CbzReader plain-class
 *  method-by-method). The legacy :shared CbzReader plain-class is
 *  still LIVE — cross-classified at Task #422 BLOCKER on the §250
 *  shadow-legacy-facade retire path (the legacy CBZ pipeline runs
 *  through the legacy :shared CbzReader; rework :data hasn't yet
 *  flipped to the :platform interface). The "consumers can mock the
 *  interface" prediction is PARTIALLY-FULFILLED — the mockable seam
 *  exists (DefaultCbzReader implements CbzReader interface), but no
 *  current rework test substitutes a fake against it.
 *  Two classifications STAND on their own merits. Original Phase
 *  5.w.5 (Task #185) :platform-relocation prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
class DefaultCbzReader(
    private val fs: AppFileSystem,
    private val dispatchers: DispatcherProvider,
) : CbzReader {

    private val log = Logger.withTag(TAG)
    private val system: FileSystem get() = fs.fileSystem()

    override fun cbzPath(mangaId: Long, chapterId: Long): Path =
        fs.chapterDir(mangaId, chapterId) / "chapter_$chapterId$CBZ_EXTENSION"

    override fun cbzExists(mangaId: Long, chapterId: Long): Boolean =
        system.exists(cbzPath(mangaId, chapterId))

    override suspend fun pageCount(cbzPath: Path): Int = withContext(dispatchers.io) {
        if (!system.exists(cbzPath)) return@withContext 0
        try {
            val zipFs = system.openZip(cbzPath)
            zipFs.listRecursively(ZIP_ROOT).count { entry: Path ->
                val md = zipFs.metadataOrNull(entry)
                md?.isRegularFile == true && isImageFile(entry.name)
            }
        } catch (e: Exception) {
            log.e(e) { "pageCount failed for $cbzPath" }
            0
        }
    }

    override suspend fun extractImages(
        cbzPath: Path,
        mangaId: Long,
        chapterId: Long,
    ): List<Path> = withContext(dispatchers.io) {
        if (!system.exists(cbzPath)) {
            log.e { "CBZ file does not exist: $cbzPath" }
            return@withContext emptyList()
        }

        val extractDir = fs.cacheDir / EXTRACT_ROOT / mangaId.toString() / chapterId.toString()
        system.createDirectories(extractDir)
        system.list(extractDir).forEach { existing ->
            try {
                system.delete(existing)
            } catch (_: Exception) {
                // best-effort cleanup of previously-extracted files
            }
        }

        val extracted = mutableListOf<Path>()
        try {
            val zipFs = system.openZip(cbzPath)
            val entries: List<Path> = zipFs.listRecursively(ZIP_ROOT)
                .filter { entry: Path ->
                    val md = zipFs.metadataOrNull(entry)
                    md?.isRegularFile == true && isImageFile(entry.name)
                }
                .toList()
                .sortedBy { entry: Path -> entry.name }

            entries.forEach { entry ->
                val outPath = extractDir / entry.name
                zipFs.source(entry).buffer().use { source ->
                    system.sink(outPath).buffer().use { sink ->
                        sink.writeAll(source)
                    }
                }
                extracted += outPath
            }
            log.i { "Extracted ${extracted.size} images from $cbzPath" }
            extracted
        } catch (e: Exception) {
            log.e(e) { "Error extracting CBZ file $cbzPath" }
            emptyList()
        }
    }

    override suspend fun deleteCbz(mangaId: Long, chapterId: Long): Boolean =
        withContext(dispatchers.io) {
            val target = cbzPath(mangaId, chapterId)
            if (system.exists(target)) {
                try {
                    system.delete(target)
                    true
                } catch (e: Exception) {
                    log.e(e) { "Failed to delete CBZ $target" }
                    false
                }
            } else {
                false
            }
        }

    override suspend fun cleanupExtractedCache(mangaId: Long, chapterId: Long) {
        withContext(dispatchers.io) {
            val extractDir = fs.cacheDir / EXTRACT_ROOT / mangaId.toString() / chapterId.toString()
            if (system.exists(extractDir)) {
                try {
                    system.deleteRecursively(extractDir)
                } catch (e: Exception) {
                    log.e(e) { "Failed to clean up extract dir $extractDir" }
                }
            }
        }
    }

    private companion object {
        const val TAG = "CbzReader"
        const val CBZ_EXTENSION = ".cbz"
        const val EXTRACT_ROOT = "cbz_extract"
        val ZIP_ROOT: Path = "/".toPath()
        // "avif" is included so a page the WebP writers could not transcode (skiko has no libavif)
        // and therefore stored verbatim under its true `.avif` name still counts as a page here —
        // the writers never relabel non-WebP bytes as `.webp`, so the reader must accept `.avif`.
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")

        fun isImageFile(filename: String): Boolean {
            val ext = filename.substringAfterLast('.', "").lowercase()
            return ext in IMAGE_EXTENSIONS
        }
    }
}
