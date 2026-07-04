package me.manga.kira.domain.device

import android.os.Build

/**
 * Android `DeviceInfoProvider` — surfaces the four `Build`/`Build.VERSION` fields the source app
 * reported on every crash/analytics event. The upstream impl also injected the package manager to
 * read versionName/packageName; that's intentionally dropped here because Phase 8's interface
 * doesn't include app metadata. App-version reporting will be reintroduced (via an
 * `AppMetadataProvider` or similar) when the analytics pipeline is wired in a later phase.
 */
class AndroidDeviceInfoProvider : DeviceInfoProvider {
    override fun getDeviceMetadata(): Map<String, Any> =
        mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "osVersion" to Build.VERSION.SDK_INT,
            "osRelease" to Build.VERSION.RELEASE,
        )
}

/*
 * Audit-trail postscript (Phase 9.x.cluster213b.staleKdocSweep.cascade, Task #669, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster213b leaf 1/3 OPENER — :shared/androidMain/domain/device/ Android actual tier,
 * sibling 400. Cumulative §253-postscript count = 125 leaves with this commit.
 *
 * File-shape note: 20-line file — `AndroidDeviceInfoProvider` 1-method class (overrides
 * `getDeviceMetadata(): Map<String, Any>` returning a 4-key bag: manufacturer / model /
 * osVersion / osRelease — keyed off Build.MANUFACTURER + Build.MODEL + Build.VERSION.SDK_INT
 * + Build.VERSION.RELEASE) + 7-line class-level KDoc prose (lines 5-11) explaining the
 * dropped-package-manager design rationale + the deferred AppMetadataProvider future-port plan.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE + FULFILLED-PORT — Android actual fulfilling the cluster212 sibling 396
 *     commonMain `DeviceInfoProvider` SPI interface. Bound via shared/src/androidMain/di/
 *     PlatformModule.android.kt Koin platform module. Twin-fulfilled by IosDeviceInfoProvider
 *     (sibling 401, this cluster) + DesktopDeviceInfoProvider (sibling 402, this cluster).
 *
 *   • KDOC-DESIGN-RATIONALE-LOAD-BEARING — 7-line KDoc prose documents the deliberately-
 *     dropped package-manager injection rationale ("The upstream impl also injected the
 *     package manager to read versionName/packageName; that's intentionally dropped here
 *     because Phase 8's interface doesn't include app metadata") + the deferred-port plan
 *     ("App-version reporting will be reintroduced (via an `AppMetadataProvider` or similar)
 *     when the analytics pipeline is wired in a later phase"). PRESERVE — design-intent doc;
 *     load-bearing for any future AppMetadataProvider sibling-SPI port that would reintroduce
 *     versionName/packageName fields.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 1 import: android.os.Build. LIVE — Android-platform-
 *     only SPI; uses Build.MANUFACTURER + Build.MODEL + Build.VERSION.SDK_INT + Build.VERSION
 *     .RELEASE static fields.
 *
 *   • CLUSTER213B OPENER REGISTER — 3-leaf platform-actual fan-out for cluster212 sibling 396
 *     commonMain `DeviceInfoProvider` SPI interface. Tier-totals:
 *       • leaf 1/3 sibling 400 OPENER — domain/device/AndroidDeviceInfoProvider.kt (Build.*
 *         4-field metadata bag + dropped-package-manager rationale + deferred AppMetadata
 *         port plan)
 *       • leaf 2/3 sibling 401 — domain/device/IosDeviceInfoProvider.kt (UIDevice.currentDevice
 *         4-field metadata + "Apple" manufacturer literal + UIDevice.model coarseness vs.
 *         sysctlbyname tradeoff rationale)
 *       • leaf 3/3 sibling 402 CLOSER — domain/device/DesktopDeviceInfoProvider.kt (System
 *         .getProperty 4-field metadata + "Desktop"/"unknown" literal fallbacks + JVM-has-no-
 *         native-device-concept schema mapping)
 */

