package me.manga.kira.platform.version

import android.content.Context

/**
 * Android [AppVersionProvider] — reads from `PackageManager.getPackageInfo`. Matches the legacy
 * `:shared` `AppVersionProvider.android.kt` byte-for-byte; only the type shape changed from
 * `actual class` to `class implements interface`.
 */
class AndroidAppVersionProvider(
    private val context: Context,
) : AppVersionProvider {

    override val packageName: String = context.applicationContext.packageName

    override val versionName: String = runCatching {
        val app = context.applicationContext
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
}

/*
 * §253 audit-trail postscript — cluster277 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (LIVE-relocated, consumer-rewiring DEFERRED).
 *
 * Unit kind: platform-facade — Android leaf of the 3-actual fan implementing the
 * commonMain SPI interface AppVersionProvider (declared at platform/src/commonMain/
 * kotlin/me/manga/yamiapk/platform/version/AppVersionProvider.kt:14, already swept in
 * cluster149 — see its in-file audit-trail postscript lines 23-68). Phase 5.1
 * relocation: the legacy expect-class shape moved to a plain interface plus three
 * per-target classes bound per-platform.
 *
 * LIVE evidence — the REWORK interface is shipped and exercised by rework :data plus
 * :presentation tiers, but the three rework :platform actuals (this file, the Desktop
 * leaf, the iOS leaf) are NOT YET bound in any Koin module. The live version-source
 * bindings remain the LEGACY :shared expect-class me.manga.kira.core.platform.
 * AppVersionProvider:
 *   - shared/src/androidMain/.../di/PlatformModule.android.kt:138
 *       single { AppVersionProvider(androidContext()) }
 *   - shared/src/iosMain/.../di/PlatformModule.ios.kt:120  single { AppVersionProvider() }
 *   - shared/src/desktopMain/.../di/PlatformModule.desktop.kt:121  single { AppVersionProvider() }
 * consumed via composeApp/.../di/AboutReworkModule.kt (AboutRepositoryImpl legacy = get())
 * and composeApp/.../di/WhatsNewReworkModule.kt:99 (appVersionProvider = get()).
 * Status: FULFILLED-PORT relocation shipped; the Phase 6 plus consumer rewire from the
 * legacy SPI to this interface is PAUSED behind Task #422 rework-vs-legacy retire path.
 * The legacy :shared expect-class stays LIVE meanwhile (NOT orphaned).
 *
 * Delta-axes (Android actual):
 *  1. Platform API — android.content.Context plus PackageManager.getPackageInfo(name, 0)
 *     for versionName; context.applicationContext.packageName for packageName. Runtime
 *     introspection of install-time manifest metadata.
 *  2. Threading/dispatcher — none; both vals are synchronous-at-init reads resolved once
 *     at Koin binding time, then cached. No suspend, no flow, no dispatcher hop.
 *  3. Error handling — runCatching {}.getOrNull() ?: "unknown" graceful-degradation
 *     idiom on versionName; no log emission (fail-silently, "unknown" sentinel is the
 *     consumer-side diagnostic). packageName cannot fail (Android guarantees applicationId).
 *  4. DI binding mechanism — constructor-injected Context (1-arg ctor, the Android
 *     outlier vs the no-arg Desktop plus iOS siblings); per-platform single in
 *     PlatformModule.android.kt once the rework wiring lands.
 *  5. Contract parity across 3 actuals — confirmed: byte-for-byte match with the legacy
 *     :shared AppVersionProvider.android.kt (deref applicationContext, @Suppress
 *     DEPRECATION on getPackageInfo, runCatching fallback). Android plus iOS both read
 *     runtime metadata; Desktop hard-codes — Desktop is the algorithm-axis outlier.
 *     This actual diverges only on the Context constructor dep (Android system APIs need it).
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the class doc at
 * line 5) plus the original source. This appended block adds exactly one opener and one
 * closer; zero interior delimiter sequences (no slash-star, no star-slash, no
 * slash-star-star anywhere in the prose). Balanced.
 */
