package me.manga.kira.platform.cbz

import okio.Path

/**
 * Cross-platform CBZ archive reader SPI.
 *
 * Companion to [CbzWriter]: locates / inspects / extracts CBZ archives produced by the writer
 * (or downloaded from external sources that follow the same layout). Backed on every platform
 * by `okio.FileSystem.openZip`, which has supported Native + JVM ZIP reading since okio 3.9 —
 * no per-platform actual needed.
 *
 * The reader knows the on-disk layout convention shared with [CbzWriter]:
 *  - Archive at `filesDir/manga/<mangaId>/chapter_<chapterId>/chapter_<chapterId>.cbz`.
 *  - Extracted pages land in a new generation under `cacheDir/cbz_extract/<mangaId>/<chapterId>/`,
 *    ordered by ZIP entry path and named by index plus their native-validated format.
 *
 * Default implementation: [DefaultCbzReader]. The interface is exposed so consumers in `:data`
 * can substitute a fake for unit tests (the okio in-memory filesystem makes that straightforward
 * but a fake is still cleaner for the orchestrator-level assertions).
 *
 * Legacy archives without an input/split manifest cannot prove the original source-page roster.

 */
interface CbzReader {
    /** Conventional location of a chapter's CBZ archive. */
    fun cbzPath(
        mangaId: Long,
        chapterId: Long,
    ): Path

    /** True iff [cbzPath] for this chapter exists on disk. */
    fun cbzExists(
        mangaId: Long,
        chapterId: Long,
    ): Boolean

    /** Complete validated image-entry count, or 0 on any invalid/read/policy failure; never a subset. */
    suspend fun pageCount(cbzPath: Path): Int

    /**
     * Validate every image entry and emit the SAME bytes into a new complete cache generation.
     * Returns paths in archive-entry order, or an empty list on any invalid/read/policy failure.
     * Previous cache generations and the archive survive failure; cancellation propagates.
     * Filenames/existence alone never establish readability, and old extracted files are not reused.
     */
    suspend fun extractImages(
        cbzPath: Path,
        mangaId: Long,
        chapterId: Long,
    ): List<Path>

    /** Delete the CBZ archive for [mangaId]/[chapterId]. Returns true iff a file existed and was deleted. */
    suspend fun deleteCbz(
        mangaId: Long,
        chapterId: Long,
    ): Boolean

    /** Recursively delete the cache directory used by [extractImages] for this chapter. */
    suspend fun cleanupExtractedCache(
        mangaId: Long,
        chapterId: Long,
    )
}
