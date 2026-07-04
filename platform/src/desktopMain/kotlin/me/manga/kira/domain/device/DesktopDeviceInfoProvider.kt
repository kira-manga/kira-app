package me.manga.kira.domain.device

/**
 * Desktop `DeviceInfoProvider` — reports JVM system properties. There is no "manufacturer" or
 * "model" concept on a generic JVM, so the schema maps to the closest equivalents:
 *  - manufacturer = "Desktop" (literal — replaces Android's `Build.MANUFACTURER`)
 *  - model = `os.arch` (e.g. "amd64", "aarch64") — closest analog to a hardware identifier
 *  - osVersion = `os.version` (e.g. "10.0", "13.4")
 *  - osRelease = `os.name` (e.g. "Windows 11", "Mac OS X", "Linux")
 *
 * Returns the literal string `"unknown"` when a system property is unexpectedly absent, mirroring
 * the defensive non-null contract of `DeviceInfoProvider.getDeviceMetadata()`.
 */
class DesktopDeviceInfoProvider : DeviceInfoProvider {
    override fun getDeviceMetadata(): Map<String, Any> =
        mapOf(
            "manufacturer" to "Desktop",
            "model" to (System.getProperty("os.arch") ?: "unknown"),
            "osVersion" to (System.getProperty("os.version") ?: "unknown"),
            "osRelease" to (System.getProperty("os.name") ?: "unknown"),
        )
}

/*
 * Audit-trail postscript (Phase 9.x.cluster213b.staleKdocSweep.cascade, Task #669, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster213b leaf 3/3 CLOSER — :shared/desktopMain/domain/device/ Desktop actual tier,
 * sibling 402. Cumulative §253-postscript count = 127 leaves with this commit.
 *
 * File-shape note: 22-line file — `DesktopDeviceInfoProvider` 1-method class (overrides
 * `getDeviceMetadata(): Map<String, Any>` returning a 4-key bag via System.getProperty with
 * "Desktop" manufacturer literal + os.arch model + os.version + os.name with "unknown"
 * fallbacks on each non-manufacturer field) + 10-line class-level KDoc prose (lines 3-13)
 * explaining the JVM-has-no-native-device-concept schema mapping + the defensive non-null
 * "unknown"-fallback contract rationale.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE + FULFILLED-PORT — Desktop actual fulfilling the cluster212 sibling 396
 *     commonMain `DeviceInfoProvider` SPI interface. Bound via shared/src/desktopMain/di/
 *     PlatformModule.desktop.kt Koin platform module. Twin-fulfilled by
 *     AndroidDeviceInfoProvider (sibling 400, this cluster) + IosDeviceInfoProvider (sibling
 *     401, this cluster).
 *
 *   • KDOC-DESIGN-RATIONALE-LOAD-BEARING — 10-line KDoc prose documents the JVM-no-native-
 *     device-concept schema-mapping design ("There is no 'manufacturer' or 'model' concept
 *     on a generic JVM, so the schema maps to the closest equivalents: manufacturer =
 *     'Desktop' literal / model = os.arch / osVersion = os.version / osRelease = os.name")
 *     + the defensive-non-null "unknown"-fallback contract ("Returns the literal string
 *     'unknown' when a system property is unexpectedly absent, mirroring the defensive non-
 *     null contract of DeviceInfoProvider.getDeviceMetadata()"). PRESERVE — design-intent
 *     doc; load-bearing for any future JVM-DeviceInfoProvider-refinement that would split
 *     into per-OS desktop sub-actuals (Windows / macOS / Linux) or migrate to a richer
 *     OSHI-based hardware-detection backend.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 0 explicit imports (uses java.lang.System.getProperty
 *     directly, implicitly-imported via kotlin.jvm). LIVE — JVM-only SPI; uses JVM stdlib only.
 *
 *   • CLUSTER213B CLOSER REGISTER — 3-leaf platform-actual fan-out for cluster212 sibling 396
 *     commonMain `DeviceInfoProvider` SPI interface CLOSES. Posture-mix register: 3 LIVE-NOT-
 *     STALE + FULFILLED-PORT (all 3 actuals deliver the same SPI contract — a uniform
 *     Map<String, Any> with the 4 keys manufacturer / model / osVersion / osRelease — via 3
 *     different platform-native mechanisms: Android Build.* statics, iOS UIDevice.currentDevice
 *     instance properties, Desktop System.getProperty + literal fallbacks). Cluster214+ will
 *     scout the remaining :shared/{androidMain,iosMain,desktopMain}/core/ legacy-port files
 *     that were flagged via Grep but not yet sampled.
 */

