package me.manga.kira.platform.cbz

/**
 * Tunable parameters consumed by the CBZ pipeline (`CbzWriter` + downstream Android-only
 * `CbzManager`). Picked per device tier by [getCbzSettings] — see that file for the per-tier values
 * and the rationale behind each threshold.
 *
 * Verbatim port from legacy `:shared/commonMain/.../core/cbz/CbzSettings.kt` — field names and
 * types are preserved so call sites remain wire-compatible across the rework boundary.
 *
 *  - [maxParallelDecode]      Max concurrent bitmap decodes when reading raw image input.
 *  - [maxParallelCompress]    Max concurrent WebP encodes when writing the archive.
 *  - [regionDecodeThreshold]  Pixel height above which a page is region-decoded rather than
 *                             loaded whole-image. Lower on low-RAM devices to dodge OOM.
 *  - [samplingThreshold]      Decoded-byte size above which an image is downsampled before encode.
 *  - [webpQuality]            Quality argument passed to `Bitmap.compress(WEBP_LOSSY, quality, …)`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster147.staleKdocSweep.cascade,
 * Task #603, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-seventy-first sibling of the cluster57-146
 * sweep — fourth file of the wave-26 :platform tier cluster147 5-leaf
 * cbz batch alongside CbzWriter plus CbzReader plus DefaultCbzReader
 * plus getCbzSettings):
 *  (a) "Tunable-parameters-consumed-by-the-CBZ-pipeline-CbzWriter-plus-
 *  downstream-Android-only-CbzManager + Picked-per-device-tier-by-
 *  getCbzSettings-see-that-file-for-the-per-tier-values-and-the-
 *  rationale-behind-each-threshold + Verbatim-port-from-legacy-:shared-
 *  commonMain-core-cbz-CbzSettings.kt-field-names-and-types-are-
 *  preserved-so-call-sites-remain-wire-compatible-across-the-rework-
 *  boundary" — LIVE-NOT-STALE plus PARTIALLY-FULFILLED-FORECAST.
 *  Verified: this data class is pure commonMain (no per-platform
 *  actual). The 5 field shapes (maxParallelDecode + maxParallelCompress
 *  + regionDecodeThreshold + samplingThreshold + webpQuality) match
 *  legacy byte-for-byte. The "downstream Android-only CbzManager"
 *  reference: the legacy CbzManager (shared/.../core/cbz/CbzManager.kt)
 *  is STILL LIVE — it consumes legacy CbzSettings, not this :platform
 *  copy. Rework :data has not yet flipped the CbzManager consumer to
 *  :platform CbzSettings (cross-classified at Task #422 BLOCKER §250
 *  shadow-legacy-facade retire path); the wire-compatible field shape
 *  preservation makes the future flip a near-zero-risk re-import.
 *  (b) Per-field bullet-list explanation block. LIVE-NOT-STALE.
 *  Verified: every field has a downstream consumer in the legacy
 *  OptimizedCbzManager + ProMangaImageCombiner pipeline that takes
 *  the corresponding tunable parameter at the documented semantics
 *  (region-decode threshold in pixels, sampling threshold in bytes,
 *  WebP quality in 0-100). No drift detected between bullet text
 *  and field semantics.
 *  Two classifications STAND on their own merits. Original Phase
 *  5.w.6 (Task #186) :platform-relocation prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
data class CbzSettings(
    val maxParallelDecode: Int,
    val maxParallelCompress: Int,
    val regionDecodeThreshold: Int,
    val samplingThreshold: Long,
    val webpQuality: Int,
)
