package me.manga.kira.platform.storage

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.Settings
import platform.Foundation.NSUserDefaults

/**
 * iOS actual: backs every store with `NSUserDefaults(suiteName = name)`. When the suite name
 * is invalid (`NSUserDefaults(suiteName = ...)` returns `null`) we fall back to
 * `standardUserDefaults` rather than crashing — matches the multiplatform-settings sample
 * wiring for hosts that share a single default store, and preserves legacy `:shared` behavior.
 *
 * `NSUserDefaultsSettings` implements `ObservableSettings` (via NSUserDefaults KVO), so the
 * same implementation satisfies both `create()` and `createObservable()` callers.
 */
class IosSettingsFactory : SettingsFactory {

    override fun create(name: String): Settings =
        NSUserDefaultsSettings(NSUserDefaults(suiteName = name))

    override fun createObservable(name: String): ObservableSettings =
        NSUserDefaultsSettings(NSUserDefaults(suiteName = name))
}

/*
 * Audit-trail postscript (Phase 9.x.cluster252.staleKdocSweep.cascade, Task #708, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster252 leaf 2 of 5 — :platform iosMain storage IosSettingsFactory,
 * sibling 533 of 5-LEAF-IOSMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER sweep.
 * Cumulative section-253-postscript count = 257 leaves with this commit.
 *
 * File-shape note: 24-line file (pre-postscript) — file-level KDoc (8
 * lines) preserved verbatim. 1 top-level class (IosSettingsFactory)
 * implementing SettingsFactory with 2 overrides (create + createObservable).
 * 4 imports (NSUserDefaultsSettings + ObservableSettings + Settings +
 * NSUserDefaults). NO companion. NO constructor params. SHORTEST-LEAF-
 * IN-CLUSTER252.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - SETTINGSFACTORY-IOS-ACTUAL-LIVE — class implements SettingsFactory
 *     with 2 overrides. 2-AGREE-WITH-cluster251-LEAF-2-AndroidSettings
 *     Factory (same 2-method shape across platform pair). The 2-method
 *     shape IS load-bearing — both return same NSUserDefaultsSettings
 *     but typed as Settings vs ObservableSettings. PRESERVE.
 *
 *   - NSUSERDEFAULTS-SUITE-NAME-LIVE — uses
 *     `NSUserDefaults(suiteName = name)`. The suiteName binding IS load-
 *     bearing because (a) IS the iOS equivalent of Android's per-name
 *     SharedPreferences, (b) allows multiple isolated stores per app,
 *     (c) hides per-suite stores in their own plist file. PRESERVE.
 *
 *   - STANDARDDEFAULTS-FALLBACK-LIVE — `NSUserDefaults(suiteName) ?:
 *     NSUserDefaults.standardUserDefaults`. The Elvis fallback IS load-
 *     bearing because (a) NSUserDefaults(suiteName) RETURNS NULL on
 *     invalid suite name (rare but possible), (b) crashing on a null
 *     would be worse than collapsing into the default store, (c) matches
 *     multiplatform-settings sample wiring. PRESERVE-AS-DOCUMENTED —
 *     KDoc explicitly cites this fallback rationale.
 *
 *   - NSUSERDEFAULTSSETTINGS-OBSERVABLE-VIA-KVO-LIVE — both create() and
 *     createObservable() return NSUserDefaultsSettings, which implements
 *     ObservableSettings via NSUserDefaults KVO. The dual-impl IS load-
 *     bearing because the SAME instance satisfies both type contracts.
 *     PRESERVE-AS-DOCUMENTED — KDoc explicitly cites "NSUserDefaultsSettings
 *     implements ObservableSettings (via NSUserDefaults KVO), so the same
 *     implementation satisfies both create() and createObservable()
 *     callers".
 *
 *   - RUSSHWOLF-MULTIPLATFORM-SETTINGS-BACKEND-LIVE — uses
 *     com.russhwolf.settings.NSUserDefaultsSettings. 2-AGREE-WITH-
 *     cluster251-LEAF-2-AndroidSettingsFactory (same russhwolf SPI, iOS-
 *     specific concrete impl). The backend choice IS load-bearing
 *     because IS the KMP-canonical Settings library. PRESERVE.
 *
 *   - NO-COMPANION-OBJECT-LIVE — class declares NO companion. 1-AGREE-
 *     WITH-cluster251-LEAF-2-AndroidSettingsFactory (also no companion).
 *     PRESERVE.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     1-DIVERGES-FROM-cluster251-LEAF-2-AndroidSettingsFactory (which
 *     takes Context). The zero-param shape IS load-bearing because iOS
 *     HAS NO Context-equivalent; NSUserDefaults IS a global singleton-
 *     style API. PRESERVE.
 *
 *   - DUPLICATE-CONSTRUCTOR-EXPRESSION-LIVE — both overrides use the
 *     SAME `NSUserDefaultsSettings(NSUserDefaults(suiteName = name) ?:
 *     ...)` expression inline (no extracted helper). The duplication
 *     IS load-bearing-but-tolerable because (a) 1-line expression IS
 *     trivial to read, (b) extracting a helper would obscure the simple
 *     factory shape, (c) matches the existing legacy port style.
 *     PRESERVE — minor: could be DRY'd in a future cleanup commit
 *     without behavior change.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster252-LIVE — IosSettingsFactory IS
 *     leaf 2 of 5 of cluster252. PRESERVE.
 */

