package me.manga.kira.platform.storage

import android.content.Context
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * Android actual: backs every store with `Context.getSharedPreferences(name, MODE_PRIVATE)`.
 *
 * `SharedPreferencesSettings` implements `ObservableSettings`, so the same instance satisfies
 * both `create()` and `createObservable()` callers.
 *
 * The constructor takes a [Context]; `applicationContext` is extracted to avoid leaking the
 * Activity that may have triggered Koin resolution.
 */
class AndroidSettingsFactory(context: Context) : SettingsFactory {

    private val appContext: Context = context.applicationContext

    override fun create(name: String): Settings =
        SharedPreferencesSettings(appContext.getSharedPreferences(name, Context.MODE_PRIVATE))

    override fun createObservable(name: String): ObservableSettings =
        SharedPreferencesSettings(appContext.getSharedPreferences(name, Context.MODE_PRIVATE))
}

/*
 * Audit-trail postscript (Phase 9.x.cluster251.staleKdocSweep.cascade, Task #707, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster251 leaf 2 of 5 — :platform androidMain storage AndroidSettingsFactory,
 * sibling 528 of 5-LEAF-ANDROIDMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 252 leaves with this commit.
 *
 * File-shape note: 26-line file (pre-postscript) — file-level KDoc (9
 * lines) preserved verbatim. 1 top-level class (AndroidSettingsFactory)
 * implementing SettingsFactory with 2 overrides (create + createObservable).
 * 4 imports (Context + ObservableSettings + Settings + SharedPreferencesSettings).
 * NO companion. 1 ctor param (Context) extracted to applicationContext field.
 * SHORTEST-LEAF-IN-CLUSTER251 except locale-style noop placeholders.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - SETTINGSFACTORY-ANDROID-ACTUAL-LIVE — class implements
 *     SettingsFactory with 2 overrides (create + createObservable). The
 *     2-method shape IS load-bearing — both methods return the SAME
 *     concrete SharedPreferencesSettings type but typed as Settings vs
 *     ObservableSettings for caller convenience. PRESERVE.
 *
 *   - SAME-IMPL-FOR-BOTH-OVERRIDES-LIVE — both create() and
 *     createObservable() return `SharedPreferencesSettings(prefs)` from
 *     the same underlying SharedPreferences. The single-impl IS load-
 *     bearing because (a) SharedPreferencesSettings DOES implement
 *     ObservableSettings (russhwolf multiplatform-settings Android impl),
 *     so (b) returning it twice with different declared types IS the
 *     intended API surface. PRESERVE-AS-DOCUMENTED — KDoc explicitly
 *     cites "the same instance satisfies both create() and
 *     createObservable() callers".
 *
 *   - MODE-PRIVATE-LIVE — `getSharedPreferences(name, Context.MODE_PRIVATE)`.
 *     The MODE_PRIVATE choice IS load-bearing because other modes
 *     (MODE_WORLD_READABLE / MODE_MULTI_PROCESS) ARE deprecated and
 *     security-hazardous. PRESERVE.
 *
 *   - APPLICATIONCONTEXT-DEFENSIVE-COPY-LIVE — `private val appContext:
 *     Context = context.applicationContext`. 2-AGREE-WITH-cluster251-LEAF-
 *     1-AndroidSecureStorage PLUS cluster248-LEAF-1-LEAF-3 (Notification +
 *     IntentLauncher also defensive-copy). The applicationContext
 *     extraction IS load-bearing because Activity-Context would leak the
 *     Activity through every cached prefs handle. PRESERVE-AS-DOCUMENTED
 *     — KDoc explicitly cites "applicationContext IS extracted to avoid
 *     leaking the Activity that may have triggered Koin resolution".
 *
 *   - NO-COMPANION-OBJECT-LIVE — class declares NO companion. 1-DIVERGES-
 *     FROM-cluster251-LEAF-1-AndroidSecureStorage (which DOES have
 *     companion with DEFAULT_FILE_NAME). The no-companion shape IS load-
 *     bearing because AndroidSettingsFactory takes the prefs-file name as
 *     a per-call parameter (vs SecureStorage which has 1 hardcoded
 *     default). PRESERVE.
 *
 *   - RUSSHWOLF-MULTIPLATFORM-SETTINGS-BACKEND-LIVE — uses
 *     com.russhwolf.settings.SharedPreferencesSettings as the concrete
 *     backend. The backend choice IS load-bearing because (a) it IS the
 *     KMP-canonical Settings library, (b) the same SPI's iOS-side IS
 *     NSUserDefaultsSettings, (c) the same SPI's Desktop-side IS
 *     PreferencesSettings. PRESERVE — defends against future "ship raw
 *     SharedPreferences via custom wrapper" refactor that would break
 *     iOS/Desktop parity.
 *
 *   - OBSERVABLESETTINGS-RETURN-TYPE-LIVE — createObservable returns
 *     ObservableSettings (not raw Settings). The narrower return type IS
 *     load-bearing because callers of createObservable IS specifically
 *     code that wants to addListener / Flow-bridge keys (e.g. theme +
 *     locale + library settings). PRESERVE.
 *
 *   - SINGLE-CTOR-PARAM-CONTEXT-LIVE — class takes a single Context
 *     parameter (no fileName default like SecureStorage). The minimal-
 *     param shape IS load-bearing because the per-call `name` param on
 *     each method handles multi-store partitioning. PRESERVE.
 *
 *   - LEGACY-PORT-IMPLICIT-LIVE — KDoc does NOT explicitly cite "Body
 *     mirrors the legacy :shared" line (unlike cluster248-LEAF-2 +
 *     cluster251-LEAF-1). 1-DIVERGES-FROM-cluster251-LEAF-1 because this
 *     file IS not a strict port — the russhwolf binding IS the same on
 *     Android in both legacy and rework, but the class wrapping IS new.
 *     PRESERVE-AS-DOCUMENTED.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster251-LIVE — AndroidSettingsFactory
 *     IS leaf 2 of 5 of cluster251 ANDROIDMAIN-PLATFORM-STORAGE-FILESYSTEM-
 *     SUB-TIER-OPENER batch. PRESERVE.
 */

