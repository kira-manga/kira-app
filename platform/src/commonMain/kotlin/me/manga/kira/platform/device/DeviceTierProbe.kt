package me.manga.kira.platform.device

import me.manga.kira.core.util.heap.DeviceTier

/**
 * Platform SPI that classifies the current device into a [DeviceTier] bucket.
 *
 * Each implementation queries the OS for total physical RAM and feeds it through the shared
 * `classifyByTotalRam(totalRamBytes)` helper in `:core/util/heap/DeviceTier.kt`, so every
 * platform agrees on the LOW/MID/HIGH boundaries even though the OS-side query is different per
 * platform:
 *
 *  - Android  → `ActivityManager.MemoryInfo.totalMem` (needs an application `Context`).
 *  - iOS      → `NSProcessInfo.processInfo.physicalMemory`.
 *  - Desktop  → `com.sun.management.OperatingSystemMXBean.getTotalPhysicalMemorySize` via
 *               reflection (so the build doesn't pin to com.sun.* being visible at compile
 *               time), with a `Runtime.maxMemory() × 4` heuristic fallback for runtimes that
 *               strip the sun-management surface.
 *
 * Replaces legacy `:shared/commonMain/.../core/util/heap/DeviceTier.kt`'s
 * `expect fun detectDeviceTier(): DeviceTier`. The rework converts every `expect/actual` to a
 * plain interface so the runtime probe is mockable, Koin-injectable, and doesn't force every
 * downstream caller to import a top-level function from a leaf module.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster146.staleKdocSweep.cascade,
 * Task #602, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-sixty-seventh sibling of the cluster57-145
 * sweep — fifth and closing file of the wave-26 :platform tier cluster146
 * 5-leaf image-plus-device batch alongside Base64ImageConverter plus
 * DominantColorExtractor plus ImageDecoderRegistry plus ScreenshotProvider;
 * closes cluster146):
 *  (a) "Platform-SPI-that-classifies-the-current-device-into-a-DeviceTier-
 *  bucket + Each-implementation-queries-the-OS-for-total-physical-RAM-and-
 *  feeds-it-through-the-shared-classifyByTotalRam-totalRamBytes-helper-in-
 *  :core-util-heap-DeviceTier.kt-so-every-platform-agrees-on-the-LOW-MID-
 *  HIGH-boundaries-even-though-the-OS-side-query-is-different-per-platform
 *  + Android-ActivityManager.MemoryInfo.totalMem-needs-an-application-
 *  Context + iOS-NSProcessInfo.processInfo.physicalMemory + Desktop-com.
 *  sun.management.OperatingSystemMXBean.getTotalPhysicalMemorySize-via-
 *  reflection-so-the-build-doesn-t-pin-to-com.sun.-being-visible-at-
 *  compile-time-with-a-Runtime.maxMemory-times-4-heuristic-fallback-for-
 *  runtimes-that-strip-the-sun-management-surface" — LIVE-NOT-STALE.
 *  Verified: 3 actuals shipped at platform/src/{android,ios,desktop}Main/
 *  device/. The shared classifyByTotalRam helper in :core/util/heap/
 *  DeviceTier.kt remains the single source of LOW/MID/HIGH thresholds
 *  consumed by all 3 actuals (verified: same per-byte boundaries honored
 *  on every target). The Desktop reflection guard against com.sun.*
 *  visibility-at-compile-time plus the Runtime.maxMemory()×4 fallback
 *  for stripped sun-management surface remain in place — both branches
 *  are exercised on at least one runtime (OpenJDK ships sun-management;
 *  certain hardened JREs do not).
 *  (b) "Replaces-legacy-:shared-commonMain-core-util-heap-DeviceTier.kt-
 *  expect-fun-detectDeviceTier-DeviceTier + The-rework-converts-every-
 *  expect-actual-to-a-plain-interface-so-the-runtime-probe-is-mockable-
 *  Koin-injectable-and-doesn-t-force-every-downstream-caller-to-import-
 *  a-top-level-function-from-a-leaf-module" — LIVE-NOT-STALE plus
 *  PARTIALLY-FULFILLED-FORECAST. Verified: the legacy `:shared/common-
 *  Main/.../core/util/heap/DeviceTier.kt` `expect fun detectDeviceTier`
 *  is still LIVE — cross-classified at cluster143 sibling 152 (DeviceTier-
 *  enum-in-:core) as STALE-PROSE-AS-OF-TASK-#187 specifically because
 *  the DeviceTierProbe SPI DID land (3 actuals, this file) BUT the
 *  predicted call-site migration never executed: rework :data never
 *  needed runtime DeviceTier (the heap-budget consumers are :shared-
 *  resident OptimizedCbzManager + ProMangaImageCombiner, both still
 *  invoking the legacy `detectDeviceTier()` top-level function via the
 *  legacy `expect/actual`). The rework `:platform`-side SPI is wired
 *  through Koin and ready to take over the moment a rework heap-budget
 *  consumer lands, but as of this sweep no such consumer exists. The
 *  mockable + Koin-injectable rework convention is honored — closes
 *  the cluster146 5-SPI image+device tier where every SPI followed the
 *  plain-interface rework pattern. Cross-classified at Task #422 BLOCKER
 *  on the §250 shadow-legacy-facade retire path for the legacy `expect
 *  fun detectDeviceTier` (the legacy lives until the :shared CBZ heap-
 *  budget consumers are retired or migrated; deferred indefinitely).
 *  Two classifications STAND on their own merits. Closes cluster146.
 *  Original Phase 5.w.6.5 (Task #187) :platform-relocation prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
interface DeviceTierProbe {
    /** Classify the current device. Implementations are non-suspending — the OS calls are sync. */
    fun detect(): DeviceTier
}
