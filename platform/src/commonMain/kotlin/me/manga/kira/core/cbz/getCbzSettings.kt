package me.manga.kira.core.cbz

import me.manga.kira.core.util.heap.DeviceTier

/**
 * Phase 8.14 port of upstream `core/cbz/getCbzSettings.kt`.
 *
 * The KMP [DeviceTier] enum was renamed in Phase 8.12 (`LOW_END/MID_RANGE/HIGH_END` →
 * `LOW/MID/HIGH`) but the threshold buckets and resulting CBZ settings are preserved.
 * Lives in commonMain because it's a pure data lookup with no platform deps.
 */
fun getCbzSettings(tier: DeviceTier): CbzSettings {
    return when (tier) {
        DeviceTier.LOW -> CbzSettings(
            maxParallelDecode = 1,
            maxParallelCompress = 2,
            regionDecodeThreshold = 6000,
            samplingThreshold = 20_000_000L, // 20MB
            webpQuality = 70,
        )
        DeviceTier.MID -> CbzSettings(
            maxParallelDecode = 2,
            maxParallelCompress = 4,
            regionDecodeThreshold = 9000,
            samplingThreshold = 40_000_000L, // 40MB
            webpQuality = 75,
        )
        DeviceTier.HIGH -> CbzSettings(
            maxParallelDecode = 3,
            maxParallelCompress = 6,
            regionDecodeThreshold = 12000,
            samplingThreshold = 70_000_000L, // 70MB
            webpQuality = 85,
        )
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster180.staleKdocSweep.cascade,
 * Task #653, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-sixty-fifth sibling of the cluster57-179
 * sweep — closing leaf of the wave-50 commonMain core/cbz 3-leaf batch;
 * getCbzSettings top-level-function 3/3 — TRULY closes the commonMain
 * core/cbz subtier FULLY SWEPT excluding the bare CbzSettings data
 * class).
 *
 *  (a) KDoc "Phase-8-14-port-of-upstream-core-cbz-getCbzSettings-kt +
 *  The-KMP-DeviceTier-enum-was-renamed-in-Phase-8-12-LOW_END-MID_RANGE-
 *  HIGH_END-to-LOW-MID-HIGH-but-the-threshold-buckets-and-resulting-
 *  CBZ-settings-are-preserved + Lives-in-commonMain-because-it-s-a-
 *  pure-data-lookup-with-no-platform-deps" — LIVE-NOT-STALE (the Phase-
 *  8.14 port IS shipped: function body at lines 12-36 ships the 3-tier
 *  when-branch over the renamed DeviceTier.LOW/MID/HIGH enum constants
 *  per cluster149 :platform sweep showing DeviceTier.kt with exactly
 *  those three values. The threshold-bucket preservation IS the
 *  documented behavioral-equivalence contract: LOW (1 decode / 2
 *  compress / 6000 region / 20MB sampling / 70 webp), MID (2/4/9000/40MB/
 *  75), HIGH (3/6/12000/70MB/85) — these magic numbers IS load-bearing
 *  for the device-tier-aware CBZ pipeline: maxParallelDecode bounds
 *  concurrent BitmapRegionDecoder instances (memory pressure); maxParallel
 *  Compress bounds concurrent WebP encoders (CPU pressure);
 *  regionDecodeThreshold sets the pixel-height above which a single page
 *  switches to region-decoding to avoid full-bitmap-in-RAM; samplingThreshold
 *  sets the byte-size above which Bitmap.Options.inSampleSize halving
 *  kicks in to avoid OOM on huge source images; webpQuality is the
 *  per-tier Bitmap.compress(WEBP, q) quality int. The "commonMain because
 *  pure data lookup" rationale IS structurally accurate — verified: no
 *  imports outside [[DeviceTier]] + CbzSettings data class — both are
 *  KMP commonMain types). The Phase-8.12 enum rename FULFILLED-PORT is
 *  closed (LOW_END→LOW etc. — no remaining references to the legacy
 *  identifiers in this file or callers per Grep scan).
 *
 * Verified: top-level function getCbzSettings(tier: DeviceTier):
 * CbzSettings with 3-arm when-branch covering DeviceTier.LOW/MID/HIGH.
 * Sibling: CbzReader.kt + CbzWriter.kt (cluster180 prior siblings).
 * CLOSING FILE of the cluster180 commonMain core/cbz 3-leaf batch (3 of
 * 3). One classification (with embedded Phase-8.12 DeviceTier-enum-
 * rename FULFILLED-PORT marker). Original Phase 8.14-era getCbzSettings
 * top-level-function prose preserved verbatim per the audit-trail-
 * preservation convention.
 */

