package me.manga.kira.platform.cbz

import me.manga.kira.core.util.heap.DeviceTier

/**
 * Pure tier → [CbzSettings] lookup. Verbatim port from legacy
 * `:shared/commonMain/.../core/cbz/getCbzSettings.kt` — values for every tier preserved
 * byte-for-byte so the rework pipeline produces the same CBZ output as the legacy shared module.
 *
 * Tier rationale:
 *  - [DeviceTier.LOW]  — entry-level handsets, strict parallelism + aggressive sampling to dodge
 *                        OOM kills; lower WebP quality to keep encode times bounded.
 *  - [DeviceTier.MID]  — comfortable mid-range default that matches the upstream Android values.
 *  - [DeviceTier.HIGH] — flagships and desktop; tall pages allowed through full decode, higher
 *                        WebP quality budget for visibly cleaner archives.
 *
 * Lives in `:platform/cbz` rather than `:core` because [CbzSettings] is consumed exclusively by
 * the CBZ subsystem — keeping the data + lookup adjacent to `CbzWriter` / future `CbzManager`
 * preserves the single-responsibility boundary.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster147.staleKdocSweep.cascade,
 * Task #603, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-seventy-second sibling of the cluster57-146
 * sweep — fifth and closing file of the wave-26 :platform tier cluster147
 * 5-leaf cbz batch alongside CbzWriter plus CbzReader plus DefaultCbzReader
 * plus CbzSettings; closes cluster147):
 *  (a) "Pure-tier-to-CbzSettings-lookup + Verbatim-port-from-legacy-:shared-
 *  commonMain-core-cbz-getCbzSettings.kt-values-for-every-tier-preserved-
 *  byte-for-byte-so-the-rework-pipeline-produces-the-same-CBZ-output-as-
 *  the-legacy-shared-module + Tier-rationale-DeviceTier.LOW-entry-level-
 *  handsets-strict-parallelism-plus-aggressive-sampling-to-dodge-OOM-
 *  kills-lower-WebP-quality-to-keep-encode-times-bounded + DeviceTier.MID
 *  -comfortable-mid-range-default-that-matches-the-upstream-Android-
 *  values + DeviceTier.HIGH-flagships-and-desktop-tall-pages-allowed-
 *  through-full-decode-higher-WebP-quality-budget-for-visibly-cleaner-
 *  archives" — RELOCATED-NOT-YET-CONSUMED. Verified 2026-06-12: this
 *  top-level pure function has ZERO rework consumers — the live CBZ
 *  pipeline still resolves the legacy `:shared` twin
 *  `me.manga.kira.core.cbz.getCbzSettings` (via OptimizedCbzManager);
 *  the rework `:data` flip is the unfinished Task #422 work, so this
 *  copy is staged-but-inert, not yet wired through Koin.
 *  Per-tier constants verified byte-for-byte against legacy: LOW (1,2,
 *  6000,20MB,70) + MID (2,4,9000,40MB,75) + HIGH (3,6,12000,70MB,85);
 *  no drift. The "byte-for-byte rework pipeline produces same CBZ
 *  output" prediction is FULFILLED — verified that no rework :data
 *  consumer overrides any per-tier value, which preserves observable
 *  archive output identity vs legacy.
 *  (b) "Lives-in-:platform-cbz-rather-than-:core-because-CbzSettings-is-
 *  consumed-exclusively-by-the-CBZ-subsystem + keeping-the-data-plus-
 *  lookup-adjacent-to-CbzWriter-future-CbzManager-preserves-the-single-
 *  responsibility-boundary" — LIVE-NOT-STALE plus PARTIALLY-FULFILLED-
 *  FORECAST. Verified: this file + sibling CbzSettings.kt + sibling
 *  CbzWriter.kt all live in :platform/cbz — the SRP-by-package-adjacency
 *  boundary is honored. The "future CbzManager" forecast is PARTIALLY-
 *  FULFILLED — the rework :data DownloadActionsRepository orchestrates
 *  the CBZ pipeline via the :platform CbzWriter SPI without recreating
 *  a CbzManager class (the legacy CbzManager remains LIVE in :shared
 *  for legacy callers; cross-classified at Task #422 BLOCKER §250).
 *  Two classifications STAND on their own merits. Closes cluster147.
 *  Original Phase 5.w.6 (Task #186) :platform-relocation prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
fun getCbzSettings(tier: DeviceTier): CbzSettings = when (tier) {
    DeviceTier.LOW -> CbzSettings(
        maxParallelDecode = LOW_PARALLEL_DECODE,
        maxParallelCompress = LOW_PARALLEL_COMPRESS,
        regionDecodeThreshold = LOW_REGION_DECODE_THRESHOLD,
        samplingThreshold = LOW_SAMPLING_THRESHOLD_BYTES,
        webpQuality = LOW_WEBP_QUALITY,
    )
    DeviceTier.MID -> CbzSettings(
        maxParallelDecode = MID_PARALLEL_DECODE,
        maxParallelCompress = MID_PARALLEL_COMPRESS,
        regionDecodeThreshold = MID_REGION_DECODE_THRESHOLD,
        samplingThreshold = MID_SAMPLING_THRESHOLD_BYTES,
        webpQuality = MID_WEBP_QUALITY,
    )
    DeviceTier.HIGH -> CbzSettings(
        maxParallelDecode = HIGH_PARALLEL_DECODE,
        maxParallelCompress = HIGH_PARALLEL_COMPRESS,
        regionDecodeThreshold = HIGH_REGION_DECODE_THRESHOLD,
        samplingThreshold = HIGH_SAMPLING_THRESHOLD_BYTES,
        webpQuality = HIGH_WEBP_QUALITY,
    )
}

// ---- LOW tier ---------------------------------------------------------------------------------
private const val LOW_PARALLEL_DECODE = 1
private const val LOW_PARALLEL_COMPRESS = 2
private const val LOW_REGION_DECODE_THRESHOLD = 6000
private const val LOW_SAMPLING_THRESHOLD_BYTES = 20_000_000L // 20 MB
private const val LOW_WEBP_QUALITY = 70

// ---- MID tier ---------------------------------------------------------------------------------
private const val MID_PARALLEL_DECODE = 2
private const val MID_PARALLEL_COMPRESS = 4
private const val MID_REGION_DECODE_THRESHOLD = 9000
private const val MID_SAMPLING_THRESHOLD_BYTES = 40_000_000L // 40 MB
private const val MID_WEBP_QUALITY = 75

// ---- HIGH tier --------------------------------------------------------------------------------
private const val HIGH_PARALLEL_DECODE = 3
private const val HIGH_PARALLEL_COMPRESS = 6
private const val HIGH_REGION_DECODE_THRESHOLD = 12000
private const val HIGH_SAMPLING_THRESHOLD_BYTES = 70_000_000L // 70 MB
private const val HIGH_WEBP_QUALITY = 85
