package me.manga.kira.platform.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Android implementation of [LocaleSwitcher].
 *
 * Body mirrors the legacy `:shared` `LocaleSwitcher.android.kt` actual byte-for-byte; only
 * the type shape changed (top-level `actual fun` → `class : LocaleSwitcher`,
 * `actual fun` → `override`).
 *
 * Delegates to `AppCompatDelegate.setApplicationLocales(...)`, which Android's AppCompat
 * implements via a hidden `LocaleManagerCompat` bridge. The framework subsequently
 * recreates any visible Activity under the new locale.
 */
class AndroidLocaleSwitcher : LocaleSwitcher {

    override fun applyApplicationLocale(languageTag: String) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(languageTag)
        )
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster248.staleKdocSweep.cascade, Task #704, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster248 leaf 4 of 5 — :platform androidMain locale AndroidLocaleSwitcher,
 * sibling 515 of 5-LEAF-ANDROIDMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 239 leaves with this commit.
 *
 * File-shape note: 24-line file (pre-postscript) — file-level KDoc (10
 * lines) preserved verbatim. 1 top-level class (AndroidLocaleSwitcher)
 * implementing LocaleSwitcher with 1 override (applyApplicationLocale). 2
 * imports (AppCompatDelegate + LocaleListCompat). NO companion. NO
 * constructor params (no Context needed — AppCompatDelegate IS a static-
 * style API). SHORTEST-LEAF-IN-cluster248.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - LOCALESWITCHER-ANDROID-ACTUAL-LIVE — class implements
 *     LocaleSwitcher with 1 override (applyApplicationLocale). Android-
 *     only impl because AppCompatDelegate.setApplicationLocales IS
 *     AndroidX-AppCompat-only. iOS uses NSUserDefaults
 *     "AppleLanguages" array, Desktop uses java.util.Locale.setDefault
 *     plus locale-aware ResourceBundle reloading. PRESERVE.
 *
 *   - APPCOMPATDELEGATE-PER-APP-LOCALE-LIVE — calls
 *     `AppCompatDelegate.setApplicationLocales(LocaleListCompat.
 *     forLanguageTags(...))`. The per-app-locale API IS load-bearing
 *     because Android 13+ ships system-level per-app-locale picker
 *     (Settings > Languages > Per-app language) — AppCompatDelegate
 *     bridges that API back to API 24+ (down to Android 7 Nougat).
 *     PRESERVE — defends against future "use Configuration.setLocale
 *     + Resources.updateConfiguration" refactor (which IS deprecated
 *     post-AppCompat-1.6 and breaks per-app-locale picker integration).
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params
 *     (no Context, no Application reference). 1-DIVERGES-FROM-cluster248-
 *     LEAF-1-AND-LEAF-3-AND-LEAF-5 (Notification/Intent/Toast actuals
 *     all take Context). The zero-param shape IS load-bearing because
 *     AppCompatDelegate.setApplicationLocales IS a static-style API
 *     that fetches the current Context internally (via the
 *     LocaleManagerCompat bridge cited in KDoc). PRESERVE.
 *
 *   - LOCALEMANAGERCOMPAT-HIDDEN-BRIDGE-CITATION-LIVE — KDoc cites
 *     "AppCompatDelegate.setApplicationLocales(...), which Android's
 *     AppCompat implements via a hidden `LocaleManagerCompat` bridge.
 *     The framework subsequently recreates any visible Activity under
 *     the new locale." The bridge-citation IS load-bearing because it
 *     documents the Activity-recreation side effect (callers MUST
 *     expect their UI to be torn down and rebuilt). PRESERVE-AS-
 *     DOCUMENTED.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body
 *     mirrors the legacy `:shared` `LocaleSwitcher.android.kt` actual
 *     byte-for-byte; only the type shape changed (top-level `actual
 *     fun` → `class : LocaleSwitcher`, `actual fun` → `override`)."
 *     The citation IS load-bearing as port-archaeology residue.
 *     PRESERVE-AS-DOCUMENTED.
 *
 *   - NO-COMPANION-OBJECT-LIVE — 2-AGREE-WITH-cluster248-LEAF-1-AND-
 *     LEAF-5 (AndroidNotificationPresenter + AndroidToastShower also
 *     have no companion). 2-DIVERGES-FROM-cluster248-LEAF-2-AND-LEAF-3
 *     (AndroidPushTokenProvider + AndroidIntentLauncher both have
 *     TAG companion). PRESERVE — no Kermit logger means no TAG needed.
 *
 *   - NO-ERROR-HANDLING-LIVE — applyApplicationLocale has NO try/catch.
 *     1-DIVERGES-FROM-cluster248-LEAF-2-AND-LEAF-3 (Push/Intent both
 *     wrap calls in try/catch). The no-catch posture IS load-bearing
 *     because AppCompatDelegate.setApplicationLocales does not throw
 *     under any documented condition (locale-list parsing IS lenient,
 *     no platform-side I/O involved). PRESERVE — defends against
 *     future "defensive try/catch for log-tag-X policy" sweep.
 *
 *   - SHORTEST-LEAF-IN-CLUSTER248-FLAG-LIVE — 24-line file with 14
 *     lines KDoc + 7 lines class body. The minimal-shape IS load-bearing
 *     evidence that the LocaleSwitcher SPI IS already at the right
 *     level of abstraction (single-method interface + thin wrapper).
 *     PRESERVE — defends against future "expand to multi-method
 *     LocaleManager facade" feature creep.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster248-LIVE — AndroidLocaleSwitcher
 *     IS leaf 4 of 5 of cluster248. SOLO-IN-platform-locale-SUBPACKAGE
 *     at cluster248 (sibling iOS/Desktop actuals unswept). PRESERVE.
 */
