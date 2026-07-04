package me.manga.yamiapk.core.storage

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.manga.yamiapk.presentation.features.reader.data.ReadingMode
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val SETTINGS_NAME = "settings_prefs"

// 1️⃣ Define the extension once here:
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = SETTINGS_NAME,
    corruptionHandler =
    ReplaceFileCorruptionHandler(
    // if corruption is detected, give back an empty Preferences instance
    produceNewData = { emptyPreferences() }
))

@Singleton
class DataStoreHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // 2️⃣ Expose the DataStore<Preferences> instance
     val dataStore = context.dataStore

    // 3️⃣ All of your Flows:
    val downloadedOnlyFlow: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[StorageKeys.DownloadedOnly] ?: false }

    val incognitoFlow: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[StorageKeys.Incognito] ?: false }

    val readingModeFlow: Flow<String> = dataStore.data
        .map { prefs -> prefs[StorageKeys.READING_MODE] ?: ReadingMode.DEFAULT.name }


    val newSourcesFlow: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[StorageKeys.NEW_SOURCES] ?: true }

    // New: Flow for selected language, default to system locale
    val languageFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[StorageKeys.SELECTED_LANGUAGE] ?: Locale.getDefault().language
    }
    // 4️⃣ All of your suspend‑updaters:
    suspend fun setDownloadedOnly(value: Boolean) {
        dataStore.edit { prefs -> prefs[StorageKeys.DownloadedOnly] = value }
    }

    suspend fun setIncognito(value: Boolean) {
        dataStore.edit { prefs -> prefs[StorageKeys.Incognito] = value }
    }
    suspend fun setNewSources(value: Boolean) {
        dataStore.edit { prefs -> prefs[StorageKeys.NEW_SOURCES] = value }
    }

    suspend fun setReadingMode(mode: String) {
        dataStore.edit { prefs: MutablePreferences ->
            prefs[StorageKeys.READING_MODE] = mode
        }
    }


    // New: set language
    suspend fun setLanguage(code: String) {
        dataStore.edit { prefs: MutablePreferences ->
            prefs[StorageKeys.SELECTED_LANGUAGE] = code
        }
    }



    // live time v

    val hasShownRemoveBookMark: Flow<Boolean> = dataStore.data
        .map { prefs ->
            prefs[StorageKeys.HAS_SHOWN_ADD_LIBRARY_PROMPT] ?: false
        }
    suspend fun setShownRemoveBookMark(value: Boolean) {
        dataStore.edit { prefs -> prefs[StorageKeys.HAS_SHOWN_ADD_LIBRARY_PROMPT] = value }
    }





    private object HeaderKeys {
        /** Stores a JSON object mapping each API string to another JSON object of headers. */
        val HEADERS_MAP_JSON = stringPreferencesKey("headers_map_json")
    }

    /**
     * Flow of all saved headers per API.
     * Emits a Map where keys are API identifiers, values are header maps.
     */
    val headersMapFlow: Flow<Map<String, Map<String, String>>> = dataStore.data.map { prefs ->
        prefs[HeaderKeys.HEADERS_MAP_JSON]?.let { jsonStr ->
            val top = JSONObject(jsonStr)
            top.keys().asSequence().associateWith { apiKey ->
                top.getJSONObject(apiKey).let { hdrJson ->
                    hdrJson.keys().asSequence().associateWith { key -> hdrJson.getString(key) }
                }
            }
        } ?: emptyMap()
    }

    /**
     * Save or update the headers for a specific API.
     * Merges into the existing map, keyed by API string.
     */
    suspend fun saveHeadersForApi(api: String, headers: Map<String, String>) {
        // Load existing map or start fresh
        val existingJson = dataStore.data.first()[HeaderKeys.HEADERS_MAP_JSON]?.let { JSONObject(it) } ?: JSONObject()
        // Put/update this API's headers
        existingJson.put(api, JSONObject(headers as Map<*, *>))
        // Persist back
        dataStore.edit { prefs -> prefs[HeaderKeys.HEADERS_MAP_JSON] = existingJson.toString() }
    }

    /**
     * Retrieve the stored headers for a given API, or null if none.
     */
    suspend fun getHeadersForApi(api: String): Map<String, String>? {
        val jsonStr = dataStore.data.first()[HeaderKeys.HEADERS_MAP_JSON] ?: return null
        val top = JSONObject(jsonStr)
        return top.optJSONObject(api)?.let { hdrJson ->
            hdrJson.keys().asSequence().associateWith { key -> hdrJson.getString(key) }
        }
    }


    val useCbzFormatFlow: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[booleanPreferencesKey(StorageKeys.KEY_USE_CBZ_FORMAT)] ?: true }

    suspend fun setUseCbzFormat(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey(StorageKeys.KEY_USE_CBZ_FORMAT)] = value
        }
    }

    val autoConvertToCbzFlow: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[booleanPreferencesKey(StorageKeys.KEY_AUTO_CONVERT_TO_CBZ)] ?: false }

    suspend fun setAutoConvertToCbz(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey(StorageKeys.KEY_AUTO_CONVERT_TO_CBZ)] = value
        }
    }
}


