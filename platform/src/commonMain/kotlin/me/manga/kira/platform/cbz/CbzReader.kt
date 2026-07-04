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
 *  - Extracted pages land in `cacheDir/cbz_extract/<mangaId>/<chapterId>/`, named after their
 *    ZIP entry name and sorted lexicographically (matches the page-write order in [CbzWriter]).
 *
 * Default implementation: [DefaultCbzReader]. The interface is exposed so consumers in `:data`
 * can substitute a fake for unit tests (the okio in-memory filesystem makes that straightforward
 * but a fake is still cleaner for the orchestrator-level assertions).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster147.staleKdocSweep.cascade,
 * Task #603, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-sixty-ninth sibling of the cluster57-146
 * sweep — second file of the wave-26 :platform tier cluster147 5-leaf
 * cbz batch alongside CbzWriter plus DefaultCbzReader plus CbzSettings
 * plus getCbzSettings):
 *  (a) "Cross-platform-CBZ-archive-reader-SPI + Companion-to-CbzWriter-
 *  locates-inspects-extracts-CBZ-archives-produced-by-the-writer-or-
 *  downloaded-from-external-sources-that-follow-the-same-layout +
 *  Backed-on-every-platform-by-okio.FileSystem.openZip-which-has-
 *  supported-Native-plus-JVM-ZIP-reading-since-okio-3.9-no-per-platform-
 *  actual-needed" — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified:
 *  zero actuals exist at platform/src/{android,ios,desktop}Main/cbz/
 *  for CbzReader (intentional — the SPI is satisfied by a pure-common-
 *  Main DefaultCbzReader via okio's cross-platform ZIP support). The
 *  "no per-platform actual needed" forecast is FULFILLED — verified
 *  okio 3.9+ FileSystem.openZip is the sole ZIP backend across Android
 *  (JVM), iOS (Native), Desktop (JVM); no per-target divergence has
 *  been required. iOS gets CBZ READ for free here despite CBZ WRITE
 *  still throwing NotImplementedError (cross-classified at sibling
 *  168 CbzWriter).
 *  (b) "The-reader-knows-the-on-disk-layout-convention-shared-with-
 *  CbzWriter + Archive-at-filesDir-manga-mangaId-chapter_chapterId-
 *  chapter_chapterId.cbz + Extracted-pages-land-in-cacheDir-cbz_
 *  extract-mangaId-chapterId-named-after-their-ZIP-entry-name-and-
 *  sorted-lexicographically-matches-the-page-write-order-in-CbzWriter
 *  + Default-implementation-DefaultCbzReader + The-interface-is-
 *  exposed-so-consumers-in-:data-can-substitute-a-fake-for-unit-tests-
 *  the-okio-in-memory-filesystem-makes-that-straightforward-but-a-
 *  fake-is-still-cleaner-for-the-orchestrator-level-assertions" —
 *  LIVE-NOT-STALE. Verified: the on-disk layout convention (filesDir/
 *  manga/<id>/chapter_<cid>/chapter_<cid>.cbz + cacheDir/cbz_extract/
 *  <id>/<cid>/) is honored by DefaultCbzReader (verified: lines 29-30
 *  + 59 of the impl match). The "consumers in :data substitute a
 *  fake" prediction is honored by the rework :data downloads tier —
 *  no in-tree fake exists yet but the interface seam is preserved
 *  unmodified, which is what the prediction requires (the absence of
 *  a current fake is a deferred-test-coverage gap, not an SPI shape
 *  regression).
 *  Two classifications STAND on their own merits. Original Phase
 *  5.w.5 (Task #185) :platform-relocation prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
interface CbzReader {

    /** Conventional location of a chapter's CBZ archive. */
    fun cbzPath(mangaId: Long, chapterId: Long): Path

    /** True iff [cbzPath] for this chapter exists on disk. */
    fun cbzExists(mangaId: Long, chapterId: Long): Boolean

    /** Count of image entries inside [cbzPath]. Returns 0 if the archive is missing / unreadable. */
    suspend fun pageCount(cbzPath: Path): Int

    /**
     * Extract every image entry into `cacheDir/cbz_extract/<mangaId>/<chapterId>/` and return
     * the extracted paths sorted by entry name. Any previously-extracted files in the same
     * directory are wiped before extraction (the directory is treated as flat).
     */
    suspend fun extractImages(cbzPath: Path, mangaId: Long, chapterId: Long): List<Path>

    /** Delete the CBZ archive for [mangaId]/[chapterId]. Returns true iff a file existed and was deleted. */
    suspend fun deleteCbz(mangaId: Long, chapterId: Long): Boolean

    /** Recursively delete the cache directory used by [extractImages] for this chapter. */
    suspend fun cleanupExtractedCache(mangaId: Long, chapterId: Long)
}
