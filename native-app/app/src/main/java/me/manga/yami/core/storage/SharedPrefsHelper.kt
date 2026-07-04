package me.manga.yamiapk.core.storage


import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject

class SharedPrefsHelper(context: Context) {
    val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

    fun putString(key: String, value: String) {
        sharedPreferences.edit() { putString(key, value) }
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return sharedPreferences.getString(key, defaultValue) ?: defaultValue
    }

    fun putInt(key: String, value: Int) {
        sharedPreferences.edit() { putInt(key, value) }
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return sharedPreferences.getInt(key, defaultValue)
    }

    fun putBoolean(key: String, value: Boolean) {
        sharedPreferences.edit() { putBoolean(key, value) }
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }


    fun putLong(key: String, value: Long) {
        sharedPreferences.edit { putLong(key, value) }
    }

    /**
     * Retrieve a long value from prefs, or defaultValue if not present.
     */
    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return sharedPreferences.getLong(key, defaultValue)
    }

    fun putMap(key: String, map: Map<String, String>) {
        val jsonObject = JSONObject(map)
        sharedPreferences.edit() { putString(key, jsonObject.toString()) }
    }

    fun getMap(key: String): Map<String, String> {
        val jsonString = sharedPreferences.getString(key, "{}") ?: "{}"
        val jsonObject = JSONObject(jsonString)
        val map = mutableMapOf<String, String>()
        jsonObject.keys().forEach { k ->
            map[k] = jsonObject.getString(k)
        }
        return map
    }

    fun remove(key: String) {
        sharedPreferences.edit() { remove(key) }
    }

    fun clear() {
        sharedPreferences.edit() { clear() }
    }

    fun booleanPrefFlow(key: String, default: Boolean): Flow<Boolean> = callbackFlow {
        trySend(getBoolean(key, default))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) {
                trySend(getBoolean(key, default))
            }
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()


    fun stringPrefFlow(
        key: String,
        defaultValue: String = ""
    ): Flow<String> = callbackFlow {
        // send initial value
        trySend(getString(key, defaultValue))
        // listener for updates
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) {
                trySend(getString(key, defaultValue))
            }
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        .flowOn(Dispatchers.IO).distinctUntilChanged()


}

