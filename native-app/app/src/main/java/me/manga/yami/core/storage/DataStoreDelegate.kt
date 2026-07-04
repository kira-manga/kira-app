package me.manga.yamiapk.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import me.manga.yamiapk.core.storage.StorageKeys.DEFAULT_STORE_NAME


val Context.dataStoreD: DataStore<Preferences> by preferencesDataStore(name = DEFAULT_STORE_NAME,
    corruptionHandler =
        ReplaceFileCorruptionHandler(
            // if corruption is detected, give back an empty Preferences instance
            produceNewData = { emptyPreferences() }
        ))

class DataStoreDelegate<T : Any>(
    private val dataStore: DataStore<Preferences>,
    private val prefsKey: Preferences.Key<T>,
    private val defaultValue: T
) {

    /** Returns a Flow of the preference value */
    val flow: Flow<T> = dataStore.data
        .map { prefs -> prefs[prefsKey] ?: defaultValue }

    /** Synchronously read the current value (not recommended on main thread) */
    fun getValueBlocking(): T = runBlocking { flow.first() }

    /** Suspend setter */
    suspend fun set(value: T) {
        dataStore.edit { prefs ->
            prefs[prefsKey] = value
        }
    }
}

// Factory functions for each type
fun Context.dataStoreString(key: String, default: String) =
    DataStoreDelegate(this.dataStoreD, stringPreferencesKey(key), default)

fun Context.dataStoreInt(key: String, default: Int) =
    DataStoreDelegate(this.dataStoreD, intPreferencesKey(key), default)

fun Context.dataStoreBoolean(key: String, default: Boolean) =
    DataStoreDelegate(this.dataStoreD, booleanPreferencesKey(key), default)

fun Context.dataStoreFloat(key: String, default: Float) =
    DataStoreDelegate(this.dataStoreD, floatPreferencesKey(key), default)

fun Context.dataStoreLong(key: String, default: Long) =
    DataStoreDelegate(this.dataStoreD, longPreferencesKey(key), default)

fun Context.dataStoreStringSet(key: String, default: Set<String>) =
    DataStoreDelegate(this.dataStoreD, stringSetPreferencesKey(key), default)
