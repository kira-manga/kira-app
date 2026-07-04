package me.manga.kira.platform.storage

import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

/**
 * Desktop (JVM) actual: backs every store with
 * `java.util.prefs.Preferences.userRoot().node(...)`, scoped under the app's package so
 * concurrent apps don't collide in the user prefs root.
 *
 * `PreferencesSettings` implements `ObservableSettings`
 * (via `Preferences.addPreferenceChangeListener`), so the same implementation satisfies both
 * `create()` and `createObservable()` callers.
 *
 * Node namespacing (`"me.manga.kira.$name"`) matches legacy `:shared` exactly so the same
 * underlying Preferences node is reachable after the rework cut-over.
 */
class DesktopSettingsFactory : SettingsFactory {

    override fun create(name: String): Settings =
        PreferencesSettings(Preferences.userRoot().node("$NODE_PREFIX$name"))

    override fun createObservable(name: String): ObservableSettings =
        PreferencesSettings(Preferences.userRoot().node("$NODE_PREFIX$name"))

    private companion object {
        const val NODE_PREFIX = "me.manga.kira."
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster253.staleKdocSweep.cascade, Task #709, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster253 leaf 2 of 5 — :platform desktopMain storage DesktopSettingsFactory,
 * sibling 538 of 5-LEAF-DESKTOPMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER-CLOSER sweep.
 * Cumulative section-253-postscript count = 262 leaves with this commit.
 *
 * File-shape note: 31-line file (pre-postscript) — file-level KDoc (11
 * lines) preserved verbatim. 1 top-level class (DesktopSettingsFactory)
 * implementing SettingsFactory with 2 overrides (create + createObservable).
 * 4 imports (PreferencesSettings + ObservableSettings + Settings + Preferences).
 * 1 private companion (NODE_PREFIX). NO constructor params.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - SETTINGSFACTORY-DESKTOP-ACTUAL-LIVE — class implements SettingsFactory
 *     with 2 overrides. 3-AGREE-WITH-cluster251-LEAF-2-AndroidSettingsFactory
 *     + cluster252-LEAF-2-IosSettingsFactory (same 2-method shape across all
 *     three platforms). PRESERVE.
 *
 *   - PREFERENCESSETTINGS-JVM-BACKEND-LIVE — uses
 *     com.russhwolf.settings.PreferencesSettings. The PreferencesSettings
 *     backend IS load-bearing because (a) IS the russhwolf SPI's JVM-
 *     specific concrete impl, (b) backs onto java.util.prefs.Preferences,
 *     (c) NOT the same as iOS NSUserDefaultsSettings (different backing
 *     store, same SPI). PRESERVE — MATCHES-cluster253-PREDICTION (predicted
 *     "PreferencesSettings via java.util.prefs").
 *
 *   - USERROOT-NODE-NAMESPACE-LIVE — `Preferences.userRoot().node(
 *     "$NODE_PREFIX$name")`. The userRoot()-namespacing IS load-bearing
 *     because (a) user-scoped (vs system-scoped) avoids requiring root/
 *     admin for writes, (b) "me.manga.kira." prefix avoids collision
 *     with other JVM apps in the same user prefs root, (c) per-suite
 *     `name` allows multiple isolated logical stores. PRESERVE.
 *
 *   - NODE-PREFIX-PACKAGE-NAMING-CONVENTION-LIVE — `NODE_PREFIX =
 *     "me.manga.kira."`. The package-style prefix IS load-bearing
 *     because (a) IS the java.util.prefs canonical-namespacing convention,
 *     (b) MATCHES the app's main package name, (c) provides reverse-DNS
 *     isolation from any other Yami-related app in the same user root.
 *     PRESERVE.
 *
 *   - PREFERENCES-CHANGE-LISTENER-VIA-OBSERVABLE-LIVE — both create() and
 *     createObservable() return PreferencesSettings, which implements
 *     ObservableSettings via Preferences.addPreferenceChangeListener.
 *     The dual-impl IS load-bearing because the SAME instance satisfies
 *     both type contracts. 2-AGREE-WITH-cluster252-LEAF-2-IosSettings
 *     Factory (same dual-impl pattern, different KVO mechanism).
 *     PRESERVE-AS-DOCUMENTED — KDoc explicitly cites this property.
 *
 *   - RUSSHWOLF-MULTIPLATFORM-SETTINGS-BACKEND-LIVE — uses
 *     com.russhwolf.settings.PreferencesSettings. 3-AGREE-WITH-cluster251-
 *     LEAF-2-AndroidSettingsFactory + cluster252-LEAF-2-IosSettingsFactory
 *     (same russhwolf SPI, platform-specific concrete). PRESERVE.
 *
 *   - PRIVATE-COMPANION-WITH-NODE-PREFIX-CONST-LIVE — `private companion
 *     object { const val NODE_PREFIX = "me.manga.kira." }`. The
 *     companion-extraction IS load-bearing because (a) NODE_PREFIX IS
 *     used 2× from create/createObservable, (b) hoisting to const avoids
 *     string-literal duplication AND signals intent (this string IS the
 *     namespace), (c) 1-DIVERGES-FROM-cluster251-LEAF-2-AndroidSettings
 *     Factory + cluster252-LEAF-2-IosSettingsFactory (which have NO
 *     companion). PRESERVE.
 *
 *   - LEGACY-SHARED-NAMESPACE-PARITY-CITATION-LIVE — KDoc cites "Node
 *     namespacing (`me.manga.kira.$name`) matches legacy `:shared`
 *     exactly so the same underlying Preferences node is reachable after
 *     the rework cut-over". 12-AGREE-WITH-CASCADE-OF-EARLIER-LEGACY-
 *     PARITY-CITATIONS. PRESERVE-AS-DOCUMENTED.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     2-AGREE-WITH-cluster252-LEAF-2-IosSettingsFactory (also no ctor
 *     params). 1-DIVERGES-FROM-cluster251-LEAF-2-AndroidSettingsFactory
 *     (which takes Context). The zero-param shape IS load-bearing because
 *     JVM HAS NO Context-equivalent; Preferences.userRoot() IS a global
 *     entry point. PRESERVE.
 *
 *   - DUPLICATE-CONSTRUCTOR-EXPRESSION-LIVE — both overrides use the
 *     SAME `PreferencesSettings(Preferences.userRoot().node("$NODE_PREFIX
 *     $name"))` expression inline (no extracted helper). 2-AGREE-WITH-
 *     cluster252-LEAF-2-IosSettingsFactory (same duplication pattern,
 *     same tolerable-trade-off). PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster253-LIVE — DesktopSettingsFactory IS
 *     leaf 2 of 5 of cluster253. PRESERVE.
 */

