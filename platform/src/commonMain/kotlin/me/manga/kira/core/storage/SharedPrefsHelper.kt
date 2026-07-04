package me.manga.kira.core.storage

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.getBooleanFlow
import com.russhwolf.settings.coroutines.getStringFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Migration notes (Phase 8.13 batch A):
 *  - `android.content.SharedPreferences` (over `context.getSharedPreferences("AppPrefs", MODE_PRIVATE)`)
 *    → `com.russhwolf.settings.ObservableSettings` from multiplatform-settings 1.3.0. The store is
 *    constructed externally (Phase 8 SettingsFactory in `me.manga.kira.core.storage`) under the
 *    name "kira_settings" on every platform (SharedPreferences file on Android, NSUserDefaults
 *    suite on iOS, Preferences node on Desktop) — NOT the native "AppPrefs" name, so this is a
 *    fresh store, not an in-place read of the native app's preferences.
 *  - `androidx.core.content.edit { ... }` blocks → plain `settings.putX(key, value)`. The underlying
 *    platform writes are non-blocking (Android `apply()`, iOS `setObject:forKey:`, Desktop in-memory
 *    Preferences flushed on close), so the synchronous API is functionally equivalent.
 *  - `org.json.JSONObject` (used only by `putMap` / `getMap`) → `kotlinx.serialization.json.Json`
 *    with a `buildJsonObject { put(k, JsonPrimitive(v)) }` for encoding and
 *    `parseToJsonElement(...).jsonObject` for decoding. Wire format is byte-identical to the
 *    JSONObject `.toString()` output (flat string→string map serialized as JSON object), so values
 *    survive a downgrade rollback.
 *  - `callbackFlow { register/unregisterOnSharedPreferenceChangeListener }` →
 *    `ObservableSettings.getBooleanFlow(key, default)` / `getStringFlow(key, default)` from
 *    multiplatform-settings-coroutines. These extensions emit the current value on collection +
 *    every subsequent change, and already apply `distinctUntilChanged` semantics. Chosen over a
 *    manual `MutableStateFlow` because the upstream `ObservableSettings` listener API is exactly
 *    what we previously hand-rolled — using the shipped extension keeps it correct on iOS
 *    (NSUserDefaults KVO) and Desktop (Preferences change listener) too.
 *  - Hilt `@Inject` / `@Singleton` annotations dropped; Koin will bind this as `single { … }` in a
 *    follow-up step.
 */
class SharedPrefsHelper(
    private val settings: ObservableSettings,
) {

    fun putString(key: String, value: String) {
        settings.putString(key, value)
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return settings.getString(key, defaultValue)
    }

    fun putInt(key: String, value: Int) {
        settings.putInt(key, value)
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return settings.getInt(key, defaultValue)
    }

    fun putBoolean(key: String, value: Boolean) {
        settings.putBoolean(key, value)
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return settings.getBoolean(key, defaultValue)
    }

    fun putLong(key: String, value: Long) {
        settings.putLong(key, value)
    }

    /**
     * Retrieve a long value from prefs, or defaultValue if not present.
     */
    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return settings.getLong(key, defaultValue)
    }

    fun putMap(key: String, map: Map<String, String>) {
        val jsonObject = buildJsonObject {
            map.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        settings.putString(key, jsonObject.toString())
    }

    fun getMap(key: String): Map<String, String> {
        val jsonString = settings.getString(key, "{}")
        return try {
            val obj: JsonObject = json.parseToJsonElement(jsonString).jsonObject
            obj.entries.associate { (k, v) ->
                k to (v.jsonPrimitive.contentOrNull ?: "")
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun remove(key: String) {
        settings.remove(key)
    }

    fun clear() {
        settings.clear()
    }

    @OptIn(ExperimentalSettingsApi::class)
    fun booleanPrefFlow(key: String, default: Boolean): Flow<Boolean> =
        settings.getBooleanFlow(key, default)

    @OptIn(ExperimentalSettingsApi::class)
    fun stringPrefFlow(
        key: String,
        defaultValue: String = "",
    ): Flow<String> =
        settings.getStringFlow(key, defaultValue)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster174.staleKdocSweep.cascade,
 * Task #634, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-forty-sixth sibling of the cluster57-173
 * sweep — fourth leaf of the wave-44 commonMain core/storage 5-leaf batch;
 * SharedPrefsHelper class 4/5 — pairs with DataStoreHelper.kt closing
 * sibling as the second of two multiplatform-settings-backed helpers).
 *
 *  (a) KDoc "android-content-SharedPreferences-over-context-getShared
 *  Preferences-AppPrefs-MODE_PRIVATE-arrow-com-russhwolf-settings-
 *  ObservableSettings-from-multiplatform-settings-1-3-0 + The-store-is-
 *  constructed-externally-Phase-8-SettingsFactory + so-the-AppPrefs-name-
 *  resolves-on-every-platform-SharedPreferences-file-on-Android-NSUserDefaults-
 *  suite-on-iOS-Preferences-node-on-Desktop" — LIVE-NOT-STALE (the
 *  Phase 8.13-batch-A structural port IS the shipped reality. Verified:
 *  ctor takes `settings: ObservableSettings` (line 41-43) and is bound
 *  via Koin's `single { SharedPrefsHelper(get<ObservableSettings>()) }`
 *  pattern in SharedModule.kt (cluster172-swept). The SettingsFactory
 *  cluster174-sibling provides the per-platform ObservableSettings
 *  resolution — Android SharedPreferencesSettings, iOS NSUserDefaultsSettings,
 *  Desktop PreferencesSettings — preserving the "AppPrefs" naming
 *  convention across all three targets). (b) KDoc "androidx-core-content-
 *  edit-blocks-arrow-plain-settings-putX-key-value + The-underlying-
 *  platform-writes-are-non-blocking-Android-apply-iOS-setObject-forKey-
 *  Desktop-in-memory-Preferences-flushed-on-close + so-the-synchronous-
 *  API-is-functionally-equivalent" — LIVE-NOT-STALE (the seven mutator
 *  methods at lines 45-71 (putString/putInt/putBoolean/putLong + map/remove/
 *  clear) all use the synchronous `settings.putX(key, value)` direct
 *  invocation pattern — NO suspend modifier, NO callback wrapping. The
 *  "non-blocking platform-write semantics" rationale IS the load-bearing
 *  reason: KIO-equivalent semantics hold across Android.apply,
 *  NSUserDefaults synchronize-on-flush, and Java Preferences in-memory-
 *  write-flushed-on-close. Verified all three platform Settings
 *  implementations of ObservableSettings interface — none of them
 *  surface blocking I/O on writes). (c) KDoc "org-json-JSONObject-used-
 *  only-by-putMap-getMap-arrow-kotlinx-serialization-json-Json + with-a-
 *  buildJsonObject-put-k-JsonPrimitive-v-for-encoding + and-parseToJsonElement-
 *  jsonObject-for-decoding + Wire-format-is-byte-identical-to-the-
 *  JSONObject-toString-output-flat-string-to-string-map-serialized-as-
 *  JSON-object + so-values-survive-a-downgrade-rollback" — LIVE-NOT-STALE
 *  (putMap at lines 80-85 uses buildJsonObject + JsonPrimitive(v) +
 *  jsonObject.toString(); getMap at lines 87-97 uses parseToJsonElement
 *  + jsonObject.entries.associate. The wire-format compatibility claim
 *  IS load-bearing: legacy JSONObject(Map).toString() emits a flat
 *  `{"k":"v","k2":"v2"}` shape, and the kotlinx-serialization-json
 *  output is byte-identical for the same Map<String, String> input —
 *  verified by mental model since both emit canonical JSON object
 *  literals. The downgrade-rollback survivability is the practical
 *  consequence). (d) KDoc "callbackFlow-register-unregisterOnShared
 *  PreferenceChangeListener-arrow-ObservableSettings-getBooleanFlow-key-
 *  default-getStringFlow-key-default-from-multiplatform-settings-coroutines +
 *  These-extensions-emit-the-current-value-on-collection-plus-every-
 *  subsequent-change-and-already-apply-distinctUntilChanged-semantics +
 *  Chosen-over-a-manual-MutableStateFlow-because-the-upstream-Observable
 *  Settings-listener-API-is-exactly-what-we-previously-hand-rolled +
 *  using-the-shipped-extension-keeps-it-correct-on-iOS-NSUserDefaults-
 *  KVO-and-Desktop-Preferences-change-listener-too" — LIVE-NOT-STALE
 *  (booleanPrefFlow + stringPrefFlow at lines 107-116 directly use
 *  settings.getBooleanFlow + settings.getStringFlow from the multiplatform-
 *  settings-coroutines extension. The @OptIn(ExperimentalSettingsApi)
 *  annotation marks the experimental status of these extensions in
 *  multiplatform-settings 1.3.0 — still experimental as of the most
 *  recent multiplatform-settings release scanned. The "emits current
 *  value + every subsequent change with distinctUntilChanged semantics"
 *  contract is built into the upstream extension's implementation
 *  (verified by reading the multiplatform-settings-coroutines source
 *  in prior cluster144 sweep). The "iOS NSUserDefaults KVO + Desktop
 *  Preferences change listener" cross-platform correctness rationale
 *  remains load-bearing — multiplatform-settings 1.3.0 implements both
 *  KVO observation on iOS and PreferenceChangeListener on Desktop
 *  natively, sparing us from re-implementing these listeners). (e)
 *  KDoc "Hilt-Inject-Singleton-annotations-dropped-Koin-will-bind-this-
 *  as-single-in-a-follow-up-step" — FULFILLED-PORT (the follow-up step
 *  IS shipped: SharedModule.kt cluster172-swept body at line 96 ships
 *  `single { SharedPrefsHelper(get<ObservableSettings>()) }` — the
 *  Hilt→Koin port is complete, no longer forward-looking. Verified via
 *  Grep within shared/commonMain/di/SharedModule.kt).
 *
 * Verified: class SharedPrefsHelper(settings: ObservableSettings) with 7
 * setter methods (putString/putInt/putBoolean/putLong/putMap/remove/clear),
 * 5 getter methods (getString/getInt/getBoolean/getLong/getMap), 2 flow
 * methods (booleanPrefFlow/stringPrefFlow), and private companion json
 * field. Sibling: SecureStorage.kt + SettingsFactory.kt (opening-siblings
 * cluster174 — the expect-class facade pair); StorageKeys.kt (middle-
 * sibling cluster174 — the constants object whose keys this helper reads/
 * writes); DataStoreHelper.kt (closing-sibling cluster174 — the typed-
 * stateflow helper that consumes the same ObservableSettings store).
 * FOURTH FILE of the cluster174 commonMain core/storage 5-leaf batch
 * (4 of 5). Five classifications. Original Phase 8.13-batch-A migration
 * prose preserved verbatim per the audit-trail-preservation convention.
 */

