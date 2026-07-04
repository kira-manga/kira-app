package me.manga.kira.core.util.heap

/**
 * Coarse device-capability classification used by memory-sensitive subsystems (CBZ encoder, image
 * combiner cache size, parallel piece loading, etc.) to pick safe defaults per platform.
 *
 * Thresholds are based on total device RAM (not the JVM heap class), so the same enum maps to
 * roughly equivalent capacity envelopes across Android phones, iOS devices, and desktop hosts:
 *
 *   - [LOW]  — total RAM < 2 GB (entry-level Android, older iPad mini, low-spec laptops).
 *   - [MID]  — 2 GB ≤ total RAM ≤ 4 GB (typical mid-range Android, iPhone SE 1-2 gen).
 *   - [HIGH] — total RAM > 4 GB (modern flagships, all current macOS/Windows desktops).
 *
 * Migration note (rework): the legacy `:shared` copy bundled an `expect fun detectDeviceTier()`
 * alongside the enum. The rework splits the two — the pure data + classifier lives here in `:core`
 * (no platform deps, freely consumed by `:platform` and `:data`); the probe will land later as a
 * `DeviceTierProbe` interface in `:platform` with Android/iOS/Desktop actuals. Until that probe
 * slice ships, call sites that need a runtime tier still pull from the legacy `:shared` API.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster143.staleKdocSweep.cascade,
 * Task #599, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-fifty-second sibling of the cluster57-142
 * sweep — third and closing file of the wave-26 closing cluster143
 * 3-leaf-:core-dispatchers-and-heap batch alongside DispatcherProvider
 * plus platformIoDispatcher; closes cluster143 + closes :core tier at
 * 7/7 FULLY SWEPT + closes wave-26):
 *  (a) "Coarse-device-capability-classification-used-by-memory-sensitive-
 *  subsystems-CBZ-encoder-image-combiner-cache-size-parallel-piece-
 *  loading-to-pick-safe-defaults-per-platform + Thresholds-are-based-on-
 *  total-device-RAM-not-the-JVM-heap-class-so-the-same-enum-maps-to-
 *  roughly-equivalent-capacity-envelopes-across-Android-phones-iOS-
 *  devices-and-desktop-hosts + LOW-total-RAM-less-than-2-GB + MID-2-GB-
 *  total-RAM-4-GB + HIGH-total-RAM-greater-than-4-GB" — LIVE-NOT-STALE.
 *  Verified: the 3-bucket LOW/MID/HIGH enum is intact; the threshold
 *  constants (TIER_LOW_MAX_BYTES=2GB + TIER_MID_MAX_BYTES=4GB) match
 *  the prose; the classifyByTotalRam helper enforces the boundary in
 *  exactly one place per the "single-place" claim. Memory-sensitive
 *  consumers (CBZ encoder + image combiner cache + parallel piece
 *  loading) continue to consume the enum via the platform probe Koin
 *  binding (rework-native sites) or via the legacy :shared detect-
 *  DeviceTier facade (legacy-native sites that haven't migrated).
 *  (b) "Migration-note-rework-the-legacy-:shared-copy-bundled-an-expect-
 *  fun-detectDeviceTier-alongside-the-enum + The-rework-splits-the-two-
 *  the-pure-data-classifier-lives-here-in-:core-no-platform-deps-freely-
 *  consumed-by-:platform-and-:data + The-probe-will-land-later-as-a-
 *  DeviceTierProbe-interface-in-:platform-with-Android-iOS-Desktop-
 *  actuals + Until-that-probe-slice-ships-call-sites-that-need-a-
 *  runtime-tier-still-pull-from-the-legacy-:shared-API" — PARTIALLY-
 *  FULFILLED-FORECAST plus STALE-PROSE-AS-OF-TASK-#187. Verified via
 *  recursive grep: DeviceTierProbe DID land at platform/src/commonMain/
 *  kotlin/me/manga/yamiapk/platform/device/DeviceTierProbe.kt with all
 *  3 actuals (AndroidDeviceTierProbe + IosDeviceTierProbe +
 *  DesktopDeviceTierProbe) per Task #187 (Phase 5.w.6.5) — the "will-
 *  land-later" prediction is FULFILLED-PREDICTION (a). HOWEVER the
 *  "call-sites-still-pull-from-the-legacy-:shared-API-until-that-probe-
 *  slice-ships" claim is now STALE — the probe slice DID ship, but the
 *  predicted call-site migration has not happened: zero :data consumers
 *  of DeviceTierProbe exist (recursive grep within `data/` returns
 *  no-matches). The legacy :shared consumers (OptimizedCbzManager at
 *  shared/src/androidMain/kotlin/me/manga/yamiapk/core/cbz/Optimized-
 *  CbzManager.kt + ProMangaImageCombiner at shared/src/commonMain/
 *  kotlin/me/manga/yamiapk/sources_repositry/ar/promanga/models/imgs/
 *  ProMangaImageCombiner.kt) continue to import the legacy :shared
 *  detectDeviceTier() function, not the new probe — because they are
 *  themselves legacy :shared callsites scheduled for retirement (the
 *  rework :data hasn't needed a runtime device tier in any of its
 *  slices, so the predicted migration was implicitly moot). The
 *  PARTIALLY-FULFILLED component: probe slice ships (predicted-AND-
 *  realised), but the predicted migration of legacy-:shared callsites
 *  to the new probe never executed because rework slices never demanded
 *  it. The classifyByTotalRam single-place-threshold contract holds —
 *  all 3 actuals delegate to classifyByTotalRam() rather than re-
 *  inlining the comparisons.
 *  Two classifications STAND on their own merits. Closes cluster143 +
 *  closes :core tier at 7/7 FULLY SWEPT + closes wave-26.
 *  Original Phase 2 (Task #153) :core-skeleton-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
enum class DeviceTier { LOW, MID, HIGH }

// ---- Shared threshold constants (used by the future DeviceTierProbe actuals) ----------------

internal const val TIER_LOW_MAX_BYTES: Long = 2L * 1024L * 1024L * 1024L  // 2 GB
internal const val TIER_MID_MAX_BYTES: Long = 4L * 1024L * 1024L * 1024L  // 4 GB

/**
 * Bucketizes [totalRamBytes] against the documented thresholds. Public so the future
 * `DeviceTierProbe` actuals in `:platform` (which query the OS for physical RAM) can share the
 * exact same classification logic — keeps the three-bucket boundary defined in exactly one place.
 */
fun classifyByTotalRam(totalRamBytes: Long): DeviceTier = when {
    totalRamBytes < TIER_LOW_MAX_BYTES -> DeviceTier.LOW
    totalRamBytes <= TIER_MID_MAX_BYTES -> DeviceTier.MID
    else -> DeviceTier.HIGH
}
