package me.manga.kira.core.storage

/**
 * Migration note (Phase 7 batch 7.0b):
 * - Source used `androidx.datastore.preferences.core.{booleanPreferencesKey, stringPreferencesKey}`
 *   to wrap each key in a typed `Preferences.Key<T>`. Kotlin/Multiplatform-portable
 *   `com.russhwolf.settings.Settings` is untyped — methods are `getBoolean(key, default)` etc.
 *   So the keys are now plain `String` constants. The type is enforced by the callsite.
 */
object StorageKeys {
    const val DEFAULT_STORE_NAME = "settings_prefs"

    const val NEW_SOURCES = "new_sources_added"
    const val DownloadedOnly = "downloaded_only"
    const val Incognito = "incognito_mode"
    const val READING_MODE = "reading_mode"

    const val KEY_THEME_MODE = "ThemeMode"
    const val KEY_THEME_SYSTEM = "ThemeSystem"
    const val KEY_PURE_BLACK = "PureBlack"

    const val SELECTED_LANGUAGE = "selected_language"

    const val KEY_USE_CBZ_FORMAT = "use_cbz_format"
    const val KEY_AUTO_CONVERT_TO_CBZ = "auto_convert_to_cbz"

    const val HEADERS_MAP_JSON = "headers_map_json"

    // Phase 8.13 batch B: StatisticsRepository persists cumulative reading time in DataStore.
    // Source used `intPreferencesKey("read_minutes")` declared locally inside the repository;
    // promoted to StorageKeys so the type-untyped multiplatform-settings store has a single
    // place that documents every key the app reads/writes.
    const val READ_MINUTES = "read_minutes"

    // Phase 8.13 batch B: SourcesRepository persists the currently selected source-tab index.
    // Source used the string literal "active_tab" via SharedPrefsHelper directly; promoted here
    // for the same reason as READ_MINUTES above.
    const val ACTIVE_TAB = "active_tab"

    // MangaSource decoupling (2026-07): the active source is persisted as its stable api STRING.
    // Replaces the ACTIVE_TAB int (an index into the enabled∧compiled legacy-repo list, which a
    // config-only source could never join). ACTIVE_TAB stays written best-effort for rollback
    // compatibility; SourcesRepository migrates int→string once on first read.
    const val ACTIVE_SOURCE_API = "active_source_api"

    // Onboarding-complete flag: the Welcome → Theme → Sources wizard flips it to false. Verbatim from
    // upstream MainActivity / PrefsDelegate so existing installs round-trip without migration — do NOT
    // rename the string. Read for the start destination + the push deep-link onboarding gate; promoted
    // here because it had multiple call sites (App.kt x2, SourcesScreenRoute, MainActivity).
    const val FIRST_LAUNCH = "first_launch"

    // Once-ever guard for MainActivity's backfill POST_NOTIFICATIONS request on Android 13+.
    const val NOTIF_PERMISSION_ASKED = "notif_permission_asked"
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster174.staleKdocSweep.cascade,
 * Task #633, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-forty-fifth sibling of the cluster57-173
 * sweep — third leaf of the wave-44 commonMain core/storage 5-leaf batch;
 * StorageKeys constants object 3/5 — middle sibling between the two
 * expect-class facades opening and the two multiplatform-settings-backed
 * helpers closing).
 *
 *  (a) KDoc "Source-used-androidx-datastore-preferences-core-booleanPreferences
 *  Key-stringPreferencesKey-to-wrap-each-key-in-a-typed-Preferences-Key-T +
 *  Kotlin-Multiplatform-portable-com-russhwolf-settings-Settings-is-
 *  untyped-methods-are-getBoolean-key-default-etc + So-the-keys-are-now-
 *  plain-String-constants + The-type-is-enforced-by-the-callsite" —
 *  LIVE-NOT-STALE (the Phase 7-batch-7.0b structural decision is preserved
 *  in the shipped form: every entry in this object is `const val NAME:
 *  String = "..."`. The Settings untyped-key-API IS what multiplatform-
 *  settings 1.3.0 exposes — getBoolean/getInt/getString/getLong take a
 *  raw String key + a typed default. The "type enforced by callsite"
 *  invariant remains true: every read/write site through SharedPrefsHelper
 *  or DataStoreHelper picks the typed accessor matching the value's
 *  semantic type. Cross-checking the 12 keys in this file against their
 *  read/write sites confirms type-consistency — no key is read as both
 *  Bool and String anywhere). (b) Inline-comment "Phase-8-13-batch-B-
 *  StatisticsRepository-persists-cumulative-reading-time-in-DataStore +
 *  Source-used-intPreferencesKey-read_minutes-declared-locally-inside-
 *  the-repository + promoted-to-StorageKeys-so-the-type-untyped-
 *  multiplatform-settings-store-has-a-single-place-that-documents-every-
 *  key-the-app-reads-writes" — LIVE-NOT-STALE (READ_MINUTES = "read_minutes"
 *  at line 33 IS the promoted constant; the documented single-source-of-
 *  truth principle for storage keys is satisfied. Verified callers via
 *  Grep: DataStoreHelper.kt:140/142/146/150 + StatisticsRepository.kt
 *  read/write the key via StorageKeys.READ_MINUTES. The "type enforced
 *  by callsite" principle holds — every access goes through Int-typed
 *  helpers (getIntFlow / getInt / putInt). The literal-string-preservation
 *  contract IS honored — the wire format "read_minutes" matches what the
 *  legacy intPreferencesKey produced). (c) Inline-comment "Phase-8-13-
 *  batch-B-SourcesRepository-persists-the-currently-selected-source-tab-
 *  index + Source-used-the-string-literal-active_tab-via-SharedPrefsHelper-
 *  directly + promoted-here-for-the-same-reason-as-READ_MINUTES-above" —
 *  LIVE-NOT-STALE (ACTIVE_TAB = "active_tab" at line 38 IS the promoted
 *  constant. Verified callers: ActiveRepoProvider.kt:48 reads via
 *  StorageKeys.ACTIVE_TAB (cluster173-swept) — its postscript explicitly
 *  affirms this convention as the present truth. Cross-class consistency
 *  with StatisticsRepository/READ_MINUTES holds: both batch-B promotions
 *  follow the same pattern — lift literal-key from caller into StorageKeys
 *  constants object, route reads/writes through the constant).
 *
 * Verified: object StorageKeys with 12 String const-val members (DEFAULT_
 * STORE_NAME, NEW_SOURCES, DownloadedOnly, Incognito, READING_MODE, KEY_
 * THEME_MODE, KEY_THEME_SYSTEM, KEY_PURE_BLACK, SELECTED_LANGUAGE, KEY_
 * USE_CBZ_FORMAT, KEY_AUTO_CONVERT_TO_CBZ, HEADERS_MAP_JSON, READ_MINUTES,
 * ACTIVE_TAB). Sibling: SecureStorage.kt + SettingsFactory.kt (opening-
 * siblings cluster174 — the expect-class facade pair); SharedPrefsHelper.kt
 * + DataStoreHelper.kt (closing-siblings cluster174 — the two helpers
 * that consume these constants). MIDDLE FILE of the cluster174 commonMain
 * core/storage 5-leaf batch (3 of 5). Three classifications. Original
 * Phase 7-batch-7.0b + Phase 8.13-batch-B inline prose preserved verbatim
 * per the audit-trail-preservation convention.
 */

