package me.manga.kira.platform.cbz

import okio.Path

/**
 * Cross-platform CBZ archive writer.
 *
 * A CBZ is a ZIP archive containing one image file per page in lexicographic order. This SPI
 * encapsulates the platform-specific bits (image decode, page encode, vertical splitting of tall
 * pages) so callers in `:data` can request "encode these N source paths into a CBZ at the
 * conventional location" without knowing whether the page encoder is Android's
 * `Bitmap.compress(WEBP_LOSSY)` or iOS's ImageIO/libwebp pipeline (with a Skia rollback encoder).
 *
 * Output archive lives at
 * `filesDir/manga/<mangaId>/chapter_<chapterId>/chapter_<chapterId>.cbz`, with the chapter
 * directory created on demand via `AppFileSystem.chapterDir`.
 *
 * Shipping mobile implementations validate every requested input before encoding or preservation;
 * any missing/read/invalid/decode/encode failure aborts without publishing a page-short archive.
 * Originals are removed only after a complete staged archive has atomically replaced the final.
 *
 * Per-platform notes:
 *  - **Android**: `Bitmap.CompressFormat.WEBP_LOSSY` on API ≥ 30, the deprecated `WEBP` on older
 *    releases.
 *  - **iOS**: native ImageIO/CoreGraphics + libwebp by default; the compiled Skia rollback follows
 *    the same pre-decode admission and sequential-emission contract.
 *  - **Android/iOS**: only already-validated pages denied by the transcode memory/dimension policy
 *    or a known transcode-only capability are preserved under their actual extension. Inspection
 *    rejection, arbitrary decoder failure and malformed data never authorize preservation.
 *  - **Desktop**: the legacy Skia list-returning path is unchanged by the mobile admission policy.
 *  - Transcoded bands are named `page_NNNN.webp`; one input can yield several output entries.
 *
 * Construction: implementations take an `AppFileSystem` so the conventional output location can
 * be resolved without callers passing path roots through every layer.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster147.staleKdocSweep.cascade,
 * Task #603, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-sixty-eighth sibling of the cluster57-146
 * sweep — opening file of the wave-26 :platform tier cluster147 5-leaf
 * cbz batch alongside CbzReader plus DefaultCbzReader plus CbzSettings
 * plus getCbzSettings):
 *  (a) "Cross-platform-CBZ-archive-writer + A-CBZ-is-a-ZIP-archive-
 *  containing-one-image-file-per-page-in-lexicographic-order + This-
 *  SPI-encapsulates-the-platform-specific-bits-image-decode-optional-
 *  encode-format-selection-vertical-splitting-of-tall-pages-so-callers-
 *  in-:data-can-request-encode-these-N-source-paths-into-a-CBZ-at-the-
 *  conventional-location-without-knowing-whether-the-page-encoder-is-
 *  Android-s-Bitmap.compress-WEBP_LOSSY-Desktop-s-ImageIO.write-png-or-
 *  Phase-14-an-iOS-side-native-pipeline + Output-archive-lives-at-files-
 *  Dir-manga-mangaId-chapter_chapterId-chapter_chapterId.cbz-with-the-
 *  chapter-directory-created-on-demand-via-AppFileSystem.chapterDir" —
 *  LIVE-NOT-STALE plus PARTIALLY-FULFILLED-FORECAST. Verified: 2 actuals
 *  shipped (Android + Desktop) at platform/src/{android,desktop}Main/
 *  cbz/. The "iOS Phase 14 native pipeline" forecast remains UNREALIZED
 *  — IosCbzWriter.kt's createCbz + createCbzWithSplitting both throw
 *  NotImplementedError, intentionally to surface iOS callers loudly
 *  rather than producing empty archives. No rework iOS caller has yet
 *  required CBZ write (legacy iOS path also lacked CBZ write — pure
 *  cross-platform feature gap, not a regression). The Output-archive
 *  location convention is honored by both actuals via the shared
 *  AppFileSystem.chapterDir extension from cluster144.
 *  (b) "Per-platform-notes-preserved-verbatim-from-legacy + Android-
 *  page-encoder-is-Bitmap.CompressFormat.WEBP_LOSSY-on-API-≥-30-the-
 *  deprecated-WEBP-on-older-releases + Filenames-use-the-page_NNNN.
 *  webp-pattern + Desktop-page-encoder-is-PNG-ImageIO-ships-no-WebP-
 *  encoder-by-default-but-filenames-keep-the-.webp-extension-because-
 *  comic-readers-detect-format-by-magic-bytes-not-name + The-quality-
 *  parameter-is-therefore-ignored-on-Desktop + iOS-not-yet-implemented
 *  -both-entry-points-throw-NotImplementedError-so-iOS-callers-fail-
 *  loudly-rather-than-producing-empty-archives + Tracked-under-Phase-
 *  14 + Construction-implementations-take-an-AppFileSystem-so-the-
 *  conventional-output-location-can-be-resolved-without-callers-
 *  passing-path-roots-through-every-layer" — LIVE-NOT-STALE plus
 *  PARTIALLY-FULFILLED-FORECAST. Verified per-actual byte-by-byte
 *  parity vs legacy: Android WEBP_LOSSY ≥ API 30 + deprecated WEBP
 *  fallback honored; page_NNNN.webp naming convention honored;
 *  Desktop ImageIO.write("png", …) with .webp filename extension
 *  honored (magic-byte detection rationale verified — Android comic
 *  readers + KMP rework's own DefaultCbzReader use magic bytes, never
 *  filename). The Desktop "quality parameter ignored" contract
 *  remains honored — no rework consumer relies on Desktop honoring
 *  the quality argument. The iOS Phase 14 forecast remains in the
 *  same UNREALIZED state as (a).
 *  Two classifications STAND on their own merits. Original Phase
 *  5.w.4 (Task #184) :platform-relocation prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
interface CbzWriter {

    /**
     * Encode every image at [imagePaths] (in order) as a CBZ at the conventional location.
     * Source files are deleted after a successful encode to reclaim disk space — the source
     * pages exist only as a temporary intermediate during the download.
     *
     * @return the path of the produced archive.
     */
    suspend fun createCbz(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int = DEFAULT_QUALITY,
    ): Path

    /**
     * Variant of [createCbz] that emits vertical bands. On mobile, metadata-based admission reserves
     * the full decoded source plus one band's native/encoded overhead before full decode; banding
     * alone cannot bound the source allocation. Valid over-budget pages are preserved verbatim.
     *
     * @param maxHeight pixels — pages taller than this are split into chunks.
     * @param maxMemoryBytes conservative per-page transcode admission budget, not a hard native RSS
     * ceiling. Bounded encoded input/validation has its own policy and precedes this decision.
     */
    suspend fun createCbzWithSplitting(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int = DEFAULT_QUALITY,
        maxHeight: Int = DEFAULT_MAX_HEIGHT,
        maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES,
    ): Path

    companion object {
        /** Default WebP encode quality (0..100). 75 matches legacy. */
        const val DEFAULT_QUALITY: Int = 75

        /** Default max-height before vertical splitting kicks in. 10_000 matches legacy. */
        const val DEFAULT_MAX_HEIGHT: Int = 10_000

        /** Default mobile transcode admission budget: 100,000,000 decimal bytes (not 100 MiB). */
        const val DEFAULT_MAX_MEMORY_BYTES: Long = 100_000_000L
    }
}
