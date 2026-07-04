package me.manga.kira.platform.storage

import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.Settings

/**
 * Platform-specific factory for creating [Settings] / [ObservableSettings] instances.
 *
 * Unlike [SecureStorage], the values stored here are **non-secret** user preferences (sort
 * order, last-selected tab, language tag, etc.). The multiplatform-settings library normalises
 * three native key-value stores to a single Kotlin surface:
 *
 *  - Android  → `SharedPreferencesSettings` over `context.getSharedPreferences(name, MODE_PRIVATE)`
 *  - iOS      → `NSUserDefaultsSettings` over `NSUserDefaults(suiteName = name)` (falling back to
 *               `standardUserDefaults` when the suite cannot be created)
 *  - Desktop  → `PreferencesSettings` over `java.util.prefs.Preferences.userRoot().node(...)`
 *
 * All three backends implement [ObservableSettings] in multiplatform-settings 1.3.0, so the
 * [createObservable] overload is safe to call on every platform.
 *
 * The `name` argument is treated as a logical store identifier (Android prefs file name, iOS
 * UserDefaults suite name, Desktop Preferences node leaf). Default `"AppPrefs"` matches the
 * legacy `:shared` wiring exactly so existing per-platform stores remain reachable after the
 * eventual cut-over.
 *
 * Phase 5.v.3 relocation of legacy `:shared/.../core/storage/SettingsFactory.kt` (expect class)
 * into the clean `:platform` layer.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster145.staleKdocSweep.cascade,
 * Task #601, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-fifty-ninth sibling of the cluster57-144
 * sweep — second file of the wave-26 :platform tier cluster145 5-leaf
 * storage-plus-net-plus-notif-plus-push batch alongside SecureStorage
 * plus ConnectivityObserver plus NotificationPresenter plus
 * PushTokenProvider):
 *  (a) "Platform-specific-factory-for-creating-Settings-Observable-
 *  Settings-instances + Unlike-SecureStorage-the-values-stored-here-are-
 *  non-secret-user-preferences-sort-order-last-selected-tab-language-
 *  tag-etc + The-multiplatform-settings-library-normalises-three-
 *  native-key-value-stores-to-a-single-Kotlin-surface + Android-Shared-
 *  PreferencesSettings + iOS-NSUserDefaultsSettings-falling-back-to-
 *  standardUserDefaults + Desktop-PreferencesSettings-over-java.util.
 *  prefs.Preferences + All-three-backends-implement-ObservableSettings-
 *  in-multiplatform-settings-1.3.0-so-the-createObservable-overload-is-
 *  safe-to-call-on-every-platform + The-name-argument-is-treated-as-a-
 *  logical-store-identifier + Default-AppPrefs-matches-the-legacy-
 *  :shared-wiring-exactly" — LIVE-NOT-STALE plus FULFILLED-PREDICTION.
 *  Verified: 3 actuals shipped at platform/src/{android,ios,desktop}-
 *  Main/storage/ (Android SharedPreferencesSettings over Context.get-
 *  SharedPreferences, iOS NSUserDefaultsSettings with suite/standard
 *  fallback, Desktop PreferencesSettings over Preferences.userRoot().
 *  node()). The "ObservableSettings safe on every platform" claim
 *  holds — multiplatform-settings 1.3.0 still bundled in :platform
 *  build.gradle.kts. DEFAULT_NAME = "AppPrefs" const matches both
 *  legacy + rework code paths so a single device sees one unified
 *  preferences store across the strangler-fig transition.
 *  (b) "Phase-5.v.3-relocation-of-legacy-:shared-core-storage-Settings-
 *  Factory-expect-class-into-the-clean-:platform-layer" — LIVE-NOT-
 *  STALE plus PARTIALLY-FULFILLED-FORECAST. Verified: the legacy
 *  `:shared` SettingsFactory facade is still LIVE — referenced by
 *  :shared PlatformModule.{android,ios,desktop}.kt and consumed by
 *  legacy preference repositories + DataStoreHelper-adjacent
 *  consumers (cross-classified at Task #422 BLOCKER on the §250
 *  shadow-legacy-facade retire path). The store-identifier-as-name-
 *  argument contract preserved (Android prefs-file-name / iOS
 *  UserDefaults suiteName / Desktop Preferences node-leaf semantics
 *  remain identical to legacy).
 *  Two classifications STAND on their own merits. Original Phase 5.v
 *  (Task #173) :platform-relocation prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
interface SettingsFactory {

    /** Return a [Settings] view of the store identified by [name]. */
    fun create(name: String = DEFAULT_NAME): Settings

    /** Return an [ObservableSettings] view of the store identified by [name]. */
    fun createObservable(name: String = DEFAULT_NAME): ObservableSettings

    companion object {
        /** Default logical store identifier, matches legacy `:shared` default. */
        const val DEFAULT_NAME: String = "AppPrefs"
    }
}
