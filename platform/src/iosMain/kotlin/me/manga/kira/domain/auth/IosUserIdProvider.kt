package me.manga.kira.domain.auth

import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice

/**
 * iOS `UserIdProvider` — returns `UIDevice.identifierForVendor.UUIDString`. This is the closest
 * iOS analog to Android's ANDROID_ID: stable per-app-vendor for the lifetime of the install on a
 * given device, reset only when the user uninstalls every app from the vendor.
 *
 * Apple may return a nil IDFV very early in launch (before the device is unlocked the first time).
 * Rather than returning an empty string in that window — which would merge complaint ownership
 * across every affected user — we synthesize and persist a UUID under `NSUserDefaults` on first
 * use, mirroring the Desktop actual's `~/.kira-manga/device-id` posture, and reuse it on later
 * launches whenever IDFV is still nil.
 */
class IosUserIdProvider : UserIdProvider {
    override fun getUserId(): String =
        UIDevice.currentDevice.identifierForVendor?.UUIDString ?: persistedFallbackId()

    private fun persistedFallbackId(): String {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.stringForKey(FALLBACK_ID_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = NSUUID().UUIDString
        defaults.setObject(id, forKey = FALLBACK_ID_KEY)
        return id
    }

    private companion object {
        private const val FALLBACK_ID_KEY = "yami_user_id_fallback"
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster213a.staleKdocSweep.cascade, Task #669, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster213a leaf 2/3 — :shared/iosMain/domain/auth/ iOS actual tier, sibling 398. Cumulative
 * §253-postscript count = 123 leaves with this commit.
 *
 * File-shape note: 17-line file — `IosUserIdProvider` 1-method class (overrides `getUserId():
 * String` via UIDevice.currentDevice.identifierForVendor?.UUIDString with empty-string nil
 * fallback) + 8-line class-level KDoc prose (lines 5-12) explaining the Android-ANDROID_ID-to-
 * iOS-IDFV semantic mapping + nil-IDFV early-launch defensive fallback rationale.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE + FULFILLED-PORT — iOS actual fulfilling the cluster212 sibling 395
 *     commonMain `UserIdProvider` SPI interface. Bound via shared/src/iosMain/di/
 *     PlatformModule.ios.kt Koin platform module. Twin-fulfilled by AndroidUserIdProvider
 *     (sibling 397, this cluster) + DesktopUserIdProvider (sibling 399, this cluster).
 *
 *   • KDOC-DESIGN-RATIONALE-LOAD-BEARING — 8-line KDoc prose documents the IDFV-to-ANDROID_ID
 *     semantic equivalence ("closest iOS analog to Android's ANDROID_ID: stable per-app-vendor
 *     for the lifetime of the install on a given device, reset only when the user uninstalls
 *     every app from the vendor") + the early-launch nil-IDFV defensive contract ("Apple may
 *     return a nil IDFV very early in launch (before the device is unlocked the first time) —
 *     we fall back to an empty string in that case rather than crashing"). PRESERVE — design-
 *     intent doc; load-bearing for any future IDFV-fallback adjustment.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 1 import: platform.UIKit.UIDevice. LIVE — iOS-platform-
 *     only SPI.
 */
