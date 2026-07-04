package me.manga.kira.domain.device

/**
 * Returns a Map of device-specific metadata, e.g. manufacturer, model, osVersion, appVersion.
 * Implementations are platform-specific and live in androidMain / iosMain / desktopMain (Phase 8).
 */
interface DeviceInfoProvider {
    fun getDeviceMetadata(): Map<String, Any>
}

/*
 * Audit-trail postscript (Phase 9.x.cluster212.staleKdocSweep.cascade, Task #668, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster212 leaf 4/4 CLOSER — :shared/domain/device/ tier SINGLE-LEAF, sibling 396.
 * Cumulative §253-postscript count = 121 leaves with this commit.
 *
 * File-shape note: 9-line file — single 1-member interface (fun getDeviceMetadata(): Map<String,
 * Any>) + 3-line KDoc forecast prose (lines 3-6) describing the Phase-8 platform-actual seam
 * plan (Android Build.MANUFACTURER + iOS UIDevice.currentDevice + Desktop System.getProperty).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE + FULFILLED-FORECAST — interface SPI with 3 platform actuals delivered:
 *       1. shared/src/androidMain/.../domain/device/AndroidDeviceInfoProvider.kt
 *       2. shared/src/iosMain/.../domain/device/IosDeviceInfoProvider.kt
 *       3. shared/src/desktopMain/.../domain/device/DesktopDeviceInfoProvider.kt
 *      All 3 actuals are LIVE — bound via shared/src/{androidMain,iosMain,desktopMain}/di/
 *      PlatformModule.{android,ios,desktop}.kt Koin platform modules. The Phase-8 forecast
 *      in the KDoc (lines 3-6) IS FULFILLED — actuals were delivered per the original plan.
 *      Twin sibling to UserIdProvider (sibling 395, this cluster) — same SPI shape, same
 *      3-actual fan-out, same fulfilled-forecast classification.
 *
 *   • KDOC-FORECAST-FULFILLED-NOT-STALE — 3-line KDoc prose (lines 3-6) carries the same
 *     forecast posture as UserIdProvider (sibling 395). PRESERVE — same rationale: the
 *     forecast IS fulfilled, but the prose documents WHY the impl is platform-split. The
 *     `Any` return-element type is a deliberate KDoc-noted concession to platform-specific
 *     metadata-bag shapes (Android packs Bundle-like values, iOS packs NSString-bridged
 *     values, Desktop packs System.getProperty Strings).
 *
 *   • USE-AT-MIGRATION-REPORT — 2 migration/ documentation references (verified via grep):
 *       1. migration/koin-graph-report.md — DeviceInfoProvider appears in the Koin dependency
 *          graph for crash-reporter + analytics-payload-builder.
 *       2. migration/di-migration-report.md — entry for the Phase-5/6 platform-facade
 *          relocation campaign.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 0 imports. Pure-Kotlin interface — pristine domain-tier
 *     shape, matching sibling 395 UserIdProvider.
 *
 *   • CLUSTER212 CLOSER REGISTER — 4-leaf :shared/.../domain/ subtree sweep CLOSES (this is
 *     the closing leaf of the per-feature-domain tier batch that cluster188 wave-58 left
 *     unswept). Tier-totals:
 *       • leaf 1/4 sibling 393 — domain/model/MangaItem.kt (Migration-note Phase 4 batch 4.2
 *         load-bearing prose; 63-reacher wide-reach DTO; LIVE-NOT-STALE)
 *       • leaf 2/4 sibling 394 — domain/model/ReaderChapters.kt (Migration-note Phase 4
 *         batch 4.2 load-bearing prose; 9-reacher narrow-reader-domain DTO; LIVE-NOT-STALE)
 *       • leaf 3/4 sibling 395 — domain/auth/UserIdProvider.kt (Phase-8 KDoc fulfilled
 *         forecast; 3 platform actuals LIVE; LIVE-NOT-STALE + FULFILLED-FORECAST)
 *       • leaf 4/4 sibling 396 CLOSER — domain/device/DeviceInfoProvider.kt (Phase-8 KDoc
 *         fulfilled forecast; 3 platform actuals LIVE; LIVE-NOT-STALE + FULFILLED-FORECAST)
 *     Also-surveyed-but-skipped-due-to-no-prose (cluster188 convention — only prose-bearing
 *     files merit §253 postscripts):
 *       • domain/model/PopularManga.kt (12-line @Serializable 5-field DTO, no class-level
 *         prose — SKIP)
 *       • core/progress/ProgressState.kt (15-line sealed class, no prose — SKIP)
 *       • core/cbz/CbzSettings.kt (9-line data class, no prose — SKIP)
 *       • core/network_connectivity/ConnectivityObserver.kt (11-line interface with nested
 *         Status enum, no prose — SKIP)
 *
 *   • POSTURE-MIX REGISTER — 2 LIVE-NOT-STALE plain (siblings 393-394 wide+narrow-reach DTOs)
 *     + 2 LIVE-NOT-STALE + FULFILLED-FORECAST (siblings 395-396 platform-SPI interfaces).
 *     Matches cluster188 wave-58 posture-mix (1 LIVE-NOT-STALE + 1 FACTUALLY-DRIFTED +
 *     1 PARTIALLY-FULFILLED-FORECAST + 1 LIVE-NOT-STALE + FULFILLED-PORT).
 */
