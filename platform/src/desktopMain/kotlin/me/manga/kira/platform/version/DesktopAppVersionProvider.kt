package me.manga.kira.platform.version

/**
 * Desktop [AppVersionProvider] (#20).
 *
 * Resolves a real version string instead of a frozen constant, lighting up the About "Version" row
 * and unfreezing the What's-New show-once gate on Desktop:
 *  1. the `kira.app.version` JVM system property (set via `-Dkira.app.version=…` for `run`), else
 *  2. the packaged JAR manifest `Implementation-Version`, else
 *  3. a stable `"1.0.0-desktop"` fallback (dev runs with neither set).
 */
class DesktopAppVersionProvider : AppVersionProvider {
    override val versionName: String =
        System.getProperty("kira.app.version")
            ?: this::class.java.`package`?.implementationVersion
            ?: "1.0.0-desktop"
    override val packageName: String = "me.manga.kira"
}

/*
 * §253 audit-trail postscript — cluster277 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (LIVE-relocated, consumer-rewiring DEFERRED).
 *
 * Unit kind: platform-facade — Desktop leaf of the 3-actual fan implementing the
 * commonMain SPI interface AppVersionProvider (declared at platform/src/commonMain/
 * kotlin/me/manga/yamiapk/platform/version/AppVersionProvider.kt:14, already swept in
 * cluster149 — see its in-file audit-trail postscript lines 23-68). Phase 5.1
 * relocation of the legacy expect-class into an interface plus per-target classes.
 *
 * LIVE evidence — the REWORK interface is consumed by rework :data plus :presentation
 * tiers, but the three rework :platform actuals are NOT YET bound in any Koin module.
 * The live version-source bindings remain the LEGACY :shared expect-class
 * me.manga.kira.core.platform.AppVersionProvider:
 *   - shared/src/desktopMain/.../di/PlatformModule.desktop.kt:121  single { AppVersionProvider() }
 *   - shared/src/androidMain/.../di/PlatformModule.android.kt:138  single { AppVersionProvider(androidContext()) }
 *   - shared/src/iosMain/.../di/PlatformModule.ios.kt:120  single { AppVersionProvider() }
 * consumed via composeApp/.../di/AboutReworkModule.kt (AboutRepositoryImpl legacy = get())
 * and composeApp/.../di/WhatsNewReworkModule.kt:99 (appVersionProvider = get()).
 * Status: FULFILLED-PORT relocation shipped; consumer rewire to this interface is PAUSED
 * behind Task #422 rework-vs-legacy retire path. Legacy :shared expect-class stays LIVE.
 *
 * Delta-axes (Desktop actual):
 *  1. Platform API — NONE. Both vals are compile-time constants: versionName =
 *     "1.0.0-desktop", packageName = "me.manga.kira". This is the algorithm-axis
 *     outlier of the fan — it does no runtime introspection (Android reads PackageManager,
 *     iOS reads NSBundle). Hard-coded until a later phase wires a Gradle build-config
 *     supplier from composeApp build.gradle.kts (documented as deferred in the class doc).
 *  2. Threading/dispatcher — none; constant property reads, no async surface whatsoever.
 *  3. Error handling — none required; constants cannot fail, so there is no runCatching
 *     fallback (the "unknown" sentinel that Android plus iOS need is irrelevant here).
 *  4. DI binding mechanism — no-arg constructor (matches the iOS sibling; diverges from
 *     the 1-arg Android Context ctor). Per-platform single in PlatformModule.desktop.kt
 *     once the rework wiring lands.
 *  5. Contract parity across 3 actuals — confirmed: byte-for-byte match with the legacy
 *     :shared AppVersionProvider.desktop.kt. The interface contract (two non-null String
 *     properties, "unknown"-on-failure for the dynamic targets) is honored; Desktop simply
 *     never fails because its values are static. Behavioural parity at the interface
 *     surface: a consumer reading versionName/packageName gets a stable non-null String
 *     from all three actuals; only the value-source differs (constant vs runtime).
 *
 * CORRECTION (#20 / B12-B, 2026-06-09): the "LIVE evidence" + delta-axis 1/5 claims above are now
 * STALE. (a) versionName is no longer the hard-coded "1.0.0-desktop" constant — it resolves the
 * `kira.app.version` system property -> JAR manifest `Implementation-Version` -> "1.0.0-desktop"
 * fallback (see the class KDoc, which is the current spec). (b) This rework Desktop actual IS now
 * the live Koin binding (PlatformModule.desktop.kt); the "NOT YET bound / legacy :shared expect-
 * class stays LIVE" note no longer holds.
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the class doc at
 * line 3) plus the original source. This appended block adds exactly one opener and one
 * closer; zero interior delimiter sequences (no slash-star, no star-slash, no
 * slash-star-star anywhere in the prose). Balanced.
 */
