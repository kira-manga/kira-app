package me.manga.kira.platform.storage

import co.touchlab.kermit.Logger
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.getBooleanFlow
import com.russhwolf.settings.coroutines.getIntFlow
import com.russhwolf.settings.coroutines.getStringFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Typed key/value preferences facade over a multiplatform-settings [ObservableSettings].
 *
 * PC-8 Wave 1 relocation of the legacy `:shared/.../core/storage/DataStoreHelper.kt` into the
 * clean `:platform` layer. The public API surface — every flow property, every setter/getter,
 * and their exact signatures — is preserved byte-for-byte so Wave-2 consumer migration is a pure
 * import swap (`me.manga.kira.core.storage.DataStoreHelper` →
 * `me.manga.kira.platform.storage.DataStoreHelper`) with no call-site changes.
 *
 * Behavioural invariants preserved from the legacy facade:
 *  - Same [StorageKeys] string keys (mirrored verbatim into `:platform` [StorageKeys]).
 *  - Same defaults (`downloadedOnly`/`incognito`/`autoConvertToCbz` = false, `newSources`/
 *    `useCbzFormat` = true, `readingMode` = "DEFAULT", `language` = "", `readMinutes` = 0,
 *    headers map = empty).
 *  - Same suspend contract on every setter/getter (the underlying multiplatform-settings writes
 *    are synchronous, but the `suspend` modifier is retained for call-site compatibility).
 *  - Same headers-map wire format: a nested kotlinx-serialization [JsonObject] of API
 *    identifiers → header maps, serialized via `JsonObject.toString()` — identical to the legacy
 *    encoding so persisted stores round-trip across the cut-over.
 *
 * No platform-specific behaviour is required, so this is a single commonMain concrete class
 * (not expect/actual) constructed from the already-bound `:platform` [SettingsFactory]'s
 * [ObservableSettings] — exactly as the legacy facade was constructed.
 */
class DataStoreHelper(
    private val settings: ObservableSettings,
) {
    private val readMinutesMutex = Mutex()

    @OptIn(ExperimentalSettingsApi::class)
    val downloadedOnlyFlow: Flow<Boolean> =
        settings.getBooleanFlow(StorageKeys.DownloadedOnly, defaultValue = false)

    @OptIn(ExperimentalSettingsApi::class)
    val incognitoFlow: Flow<Boolean> =
        settings.getBooleanFlow(StorageKeys.Incognito, defaultValue = false)

    @OptIn(ExperimentalSettingsApi::class)
    val readingModeFlow: Flow<String> =
        settings.getStringFlow(StorageKeys.READING_MODE, defaultValue = "DEFAULT")

    @OptIn(ExperimentalSettingsApi::class)
    val newSourcesFlow: Flow<Boolean> =
        settings.getBooleanFlow(StorageKeys.NEW_SOURCES, defaultValue = true)

    @OptIn(ExperimentalSettingsApi::class)
    val languageFlow: Flow<String> =
        settings.getStringFlow(StorageKeys.SELECTED_LANGUAGE, defaultValue = "")

    /**
     * Returns the persisted language synchronously for the first UI composition.
     *
     * [ObservableSettings] is already backed by an in-process preference store on every platform,
     * so reading the current value does not require waiting for [languageFlow]'s first collection.
     * Keeping the initial value and the flow on the same key prevents a cold-start locale flash.
     */
    fun currentLanguage(): String =
        settings.getString(StorageKeys.SELECTED_LANGUAGE, defaultValue = "")

    suspend fun setDownloadedOnly(value: Boolean) {
        settings.putBoolean(StorageKeys.DownloadedOnly, value)
    }

    suspend fun setIncognito(value: Boolean) {
        settings.putBoolean(StorageKeys.Incognito, value)
    }

    suspend fun setNewSources(value: Boolean) {
        settings.putBoolean(StorageKeys.NEW_SOURCES, value)
    }

    suspend fun setReadingMode(mode: String) {
        settings.putString(StorageKeys.READING_MODE, mode)
    }

    suspend fun setLanguage(code: String) {
        settings.putString(StorageKeys.SELECTED_LANGUAGE, code)
    }

    suspend fun saveHeadersForApi(api: String, headers: Map<String, String>) {
        // #12: write ONE small JSON object PER API under a bounded hashed key, instead of re-encoding
        // the whole 41-source aggregate into a single value. On Desktop the backend is
        // java.util.prefs.Preferences, whose putString throws above an 8 KB value cap — the unbounded
        // aggregate could exceed it once enough sources accumulate large Cloudflare cookies, crashing
        // every header refresh. A single source's headers stay well under the cap. Belt-and-suspenders:
        // the putString is wrapped so even a pathological single-source >8 KB header degrades (logs +
        // skips) rather than crashing the refresh. Signature unchanged — callers under sources_repositry/
        // are untouched.
        try {
            settings.putString(keyForApi(api), encodeSingleHeaders(api, headers))
        } catch (t: Throwable) {
            Logger.withTag("Headers").e(t) {
                "[Headers] saveHeadersForApi api=$api dropped — likely exceeded the Desktop 8KB value cap"
            }
            return
        }
        Logger.withTag("Headers").i {
            "[Headers] DataStoreHelper.saveHeadersForApi api=$api count=${headers.size} keys=${headers.keys}"
        }
    }

    suspend fun getHeadersForApi(api: String): Map<String, String>? {
        // (No per-read logging — this is called on every page/image request and flooded the log.)
        // #12: prefer the per-API key; fall back to the legacy single-aggregate blob so headers saved
        // before this change keep working with no forced migration (new saves migrate naturally).
        val perKey = settings.getString(keyForApi(api), "")
        if (perKey.isNotEmpty()) {
            parseSingleHeaders(perKey, expectedApi = api)?.let { return it }
        }
        val legacy = settings.getString(StorageKeys.HEADERS_MAP_JSON, "")
        if (legacy.isEmpty()) return null
        return parseHeadersMap(legacy)[api]
    }

    @OptIn(ExperimentalSettingsApi::class)
    val useCbzFormatFlow: Flow<Boolean> =
        settings.getBooleanFlow(StorageKeys.KEY_USE_CBZ_FORMAT, defaultValue = true)

    suspend fun setUseCbzFormat(value: Boolean) {
        settings.putBoolean(StorageKeys.KEY_USE_CBZ_FORMAT, value)
    }

    @OptIn(ExperimentalSettingsApi::class)
    val autoConvertToCbzFlow: Flow<Boolean> =
        settings.getBooleanFlow(StorageKeys.KEY_AUTO_CONVERT_TO_CBZ, defaultValue = false)

    suspend fun setAutoConvertToCbz(value: Boolean) {
        settings.putBoolean(StorageKeys.KEY_AUTO_CONVERT_TO_CBZ, value)
    }

    // iOS Low Power Mode compression opt-in (default false = respect battery intent). Consumed by the
    // iOS background-download finalize gate; a false→true flip re-drives deferred CBZ work.
    @OptIn(ExperimentalSettingsApi::class)
    val allowCompressionInLowPowerFlow: Flow<Boolean> =
        settings.getBooleanFlow(StorageKeys.KEY_ALLOW_COMPRESSION_IN_LOW_POWER, defaultValue = false)

    suspend fun setAllowCompressionInLowPower(value: Boolean) {
        settings.putBoolean(StorageKeys.KEY_ALLOW_COMPRESSION_IN_LOW_POWER, value)
    }

    @OptIn(ExperimentalSettingsApi::class)
    val readMinutesFlow: Flow<Int> =
        settings.getIntFlow(StorageKeys.READ_MINUTES, defaultValue = 0)

    suspend fun getReadMinutes(): Int =
        settings.getInt(StorageKeys.READ_MINUTES, defaultValue = 0)

    suspend fun setReadMinutes(value: Int) {
        settings.putInt(StorageKeys.READ_MINUTES, value)
    }

    suspend fun addReadMinutes(delta: Int) {
        // multiplatform-settings has no atomic increment; serialize the read-modify-write so
        // concurrent session-end increments don't interleave and drop a delta.
        readMinutesMutex.withLock {
            val current = settings.getInt(StorageKeys.READ_MINUTES, defaultValue = 0)
            settings.putInt(StorageKeys.READ_MINUTES, current + delta)
        }
    }

    private fun parseHeadersMap(jsonStr: String): Map<String, Map<String, String>> {
        if (jsonStr.isEmpty()) return emptyMap()
        return try {
            val top = json.parseToJsonElement(jsonStr).jsonObject
            top.entries.associate { (apiKey, value) ->
                val inner = value.jsonObject.entries.associate { (k, v) ->
                    k to (v.jsonPrimitive.contentOrNull ?: "")
                }
                apiKey to inner
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    // #12: bounded per-API key — "headers." + base36(unsigned hashCode). <=15 chars, well under the
    // Desktop 80-char MAX_KEY_LENGTH. Mirrors ReadProgressRepositoryImpl's KEY_PREFIX + hashCode
    // .toUInt().toString(36) precedent.
    private fun keyForApi(api: String): String =
        HEADERS_KEY_PREFIX + api.hashCode().toUInt().toString(36)

    // #12: encode ONE source's headers as {"api":<api>,"h":{...}}. The `api` is stored so a read can
    // verify it (hash-collision guard — two apis hashing to the same key return null for the wrong one,
    // exactly like ReadProgressRepositoryImpl's storedUrl != chapterUrl guard).
    private fun encodeSingleHeaders(api: String, headers: Map<String, String>): String =
        buildJsonObject {
            put("api", JsonPrimitive(api))
            put("h", buildJsonObject { headers.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
        }.toString()

    private fun parseSingleHeaders(jsonStr: String, expectedApi: String): Map<String, String>? {
        return try {
            val top = json.parseToJsonElement(jsonStr).jsonObject
            if (top["api"]?.jsonPrimitive?.contentOrNull != expectedApi) return null
            top["h"]?.jsonObject?.entries?.associate { (k, v) ->
                k to (v.jsonPrimitive.contentOrNull ?: "")
            } ?: emptyMap()
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val HEADERS_KEY_PREFIX = "headers."
    }
}
