package me.manga.kira.platform.version

import platform.Foundation.NSBundle

/**
 * iOS [AppVersionProvider] — reads `CFBundleShortVersionString` + `bundleIdentifier` from
 * `NSBundle.mainBundle`. Matches the legacy `:shared` `AppVersionProvider.ios.kt` byte-for-byte.
 */
class IosAppVersionProvider : AppVersionProvider {

    override val versionName: String =
        NSBundle.mainBundle.infoDictionary
            ?.get("CFBundleShortVersionString") as? String
            ?: "unknown"

    override val packageName: String =
        NSBundle.mainBundle.bundleIdentifier ?: "me.manga.kira"
}

/*
 * §253 audit-trail postscript — cluster277 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (LIVE-relocated, consumer-rewiring DEFERRED).
 *
 * Unit kind: platform-facade — iOS leaf of the 3-actual fan implementing the commonMain
 * SPI interface AppVersionProvider (declared at platform/src/commonMain/kotlin/me/manga/
 * yamiapk/platform/version/AppVersionProvider.kt:14, already swept in cluster149 — see
 * its in-file audit-trail postscript lines 23-68). Phase 5.1 relocation of the legacy
 * expect-class into an interface plus per-target classes.
 *
 * LIVE evidence — the REWORK interface is consumed by rework :data plus :presentation
 * tiers, but the three rework :platform actuals are NOT YET bound in any Koin module.
 * The live version-source bindings remain the LEGACY :shared expect-class
 * me.manga.kira.core.platform.AppVersionProvider:
 *   - shared/src/iosMain/.../di/PlatformModule.ios.kt:120  single { AppVersionProvider() }
 *   - shared/src/androidMain/.../di/PlatformModule.android.kt:138  single { AppVersionProvider(androidContext()) }
 *   - shared/src/desktopMain/.../di/PlatformModule.desktop.kt:121  single { AppVersionProvider() }
 * consumed via composeApp/.../di/AboutReworkModule.kt (AboutRepositoryImpl legacy = get())
 * and composeApp/.../di/WhatsNewReworkModule.kt:99 (appVersionProvider = get()).
 * Status: FULFILLED-PORT relocation shipped; consumer rewire to this interface is PAUSED
 * behind Task #422 rework-vs-legacy retire path. Legacy :shared expect-class stays LIVE.
 *
 * Delta-axes (iOS actual):
 *  1. Platform API — platform.Foundation.NSBundle. versionName reads
 *     NSBundle.mainBundle.infoDictionary["CFBundleShortVersionString"] cast to String;
 *     packageName reads NSBundle.mainBundle.bundleIdentifier. Runtime introspection of
 *     the app bundle's Info.plist — the iOS analogue of Android's PackageManager read.
 *  2. Threading/dispatcher — none; synchronous-at-init property reads resolved once at
 *     Koin binding time. No suspend, no coroutine, no dispatcher hop. NSBundle.mainBundle
 *     access is main-thread-safe and cheap (no I/O).
 *  3. Error handling — null-coalescing fallbacks rather than runCatching: the
 *     infoDictionary lookup uses safe-cast (as? String) ?: "unknown"; bundleIdentifier
 *     uses ?: "me.manga.kira". No exception surface (NSBundle returns nullable, not
 *     throwing), so the "unknown" sentinel is reached via null-coalesce, not catch.
 *     Matches the interface graceful-degradation contract; no log emission.
 *  4. DI binding mechanism — no-arg constructor (matches the Desktop sibling; diverges
 *     from the 1-arg Android Context ctor — iOS reaches NSBundle via a global singleton,
 *     no injected platform handle needed). Per-platform single in PlatformModule.ios.kt
 *     once the rework wiring lands.
 *  5. Contract parity across 3 actuals — confirmed: byte-for-byte match with the legacy
 *     :shared AppVersionProvider.ios.kt. Behavioural parity at the interface surface:
 *     iOS plus Android both perform runtime metadata introspection (NSBundle vs
 *     PackageManager) and return "unknown"-on-miss; Desktop returns hard-coded constants
 *     (algorithm-axis outlier). The packageName fallback constant ("me.manga.kira")
 *     equals the Desktop hard-coded packageName — same reverse-DNS identity across targets.
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the class doc at
 * line 5) plus the original source. This appended block adds exactly one opener and one
 * closer; zero interior delimiter sequences (no slash-star, no star-slash, no
 * slash-star-star anywhere in the prose). Balanced.
 */
