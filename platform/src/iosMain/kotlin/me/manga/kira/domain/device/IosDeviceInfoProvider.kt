package me.manga.kira.domain.device

import platform.UIKit.UIDevice

/**
 * iOS `DeviceInfoProvider` — pulls the same four logical keys the Android impl exposes, sourced
 * from `UIDevice.currentDevice`. There is no per-device "manufacturer" on iOS (Apple is the sole
 * vendor), so the key is filled with the literal `"Apple"` to keep the schema uniform across
 * platforms.
 *
 * Note: `UIDevice.model` returns the device family ("iPhone", "iPad", "iPod touch") rather than
 * the marketing/model identifier (e.g. "iPhone14,3"). The latter requires a `sysctlbyname` call
 * — not added here because the source Android impl reports `Build.MODEL`, which is similarly
 * coarse-grained ("Pixel 7" / "SM-G998B") rather than a fine SKU.
 */
class IosDeviceInfoProvider : DeviceInfoProvider {
    override fun getDeviceMetadata(): Map<String, Any> {
        val device = UIDevice.currentDevice
        return mapOf(
            "manufacturer" to "Apple",
            "model" to device.model,
            "osVersion" to device.systemVersion,
            "osRelease" to device.systemName,
        )
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster213b.staleKdocSweep.cascade, Task #669, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster213b leaf 2/3 — :shared/iosMain/domain/device/ iOS actual tier, sibling 401.
 * Cumulative §253-postscript count = 126 leaves with this commit.
 *
 * File-shape note: 26-line file — `IosDeviceInfoProvider` 1-method class (overrides
 * `getDeviceMetadata(): Map<String, Any>` returning a 4-key bag via UIDevice.currentDevice
 * with "Apple" manufacturer literal + device.model + device.systemVersion + device.systemName)
 * + 10-line class-level KDoc prose (lines 5-15) explaining the "Apple" manufacturer-literal
 * schema-uniformity rationale + the UIDevice.model device-family-coarseness vs. sysctlbyname
 * SKU-precision tradeoff.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE + FULFILLED-PORT — iOS actual fulfilling the cluster212 sibling 396
 *     commonMain `DeviceInfoProvider` SPI interface. Bound via shared/src/iosMain/di/
 *     PlatformModule.ios.kt Koin platform module. Twin-fulfilled by AndroidDeviceInfoProvider
 *     (sibling 400, this cluster) + DesktopDeviceInfoProvider (sibling 402, this cluster).
 *
 *   • KDOC-DESIGN-RATIONALE-LOAD-BEARING — 10-line KDoc prose documents the "Apple"-
 *     manufacturer-literal schema-uniformity design ("There is no per-device 'manufacturer'
 *     on iOS (Apple is the sole vendor), so the key is filled with the literal 'Apple' to
 *     keep the schema uniform across platforms") + the UIDevice.model device-family vs.
 *     sysctlbyname tradeoff rationale ("UIDevice.model returns the device family ('iPhone',
 *     'iPad', 'iPod touch') rather than the marketing/model identifier (e.g. 'iPhone14,3').
 *     The latter requires a sysctlbyname call — not added here because the source Android
 *     impl reports Build.MODEL, which is similarly coarse-grained"). PRESERVE — design-intent
 *     doc; load-bearing for any future iOS-DeviceInfoProvider-precision adjustment that would
 *     introduce sysctlbyname-based SKU resolution (and would require parity widening on the
 *     Android side to keep cross-platform schema uniformity).
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 1 import: platform.UIKit.UIDevice. LIVE — iOS-platform-
 *     only SPI; uses UIDevice.currentDevice.{model, systemVersion, systemName}.
 */

