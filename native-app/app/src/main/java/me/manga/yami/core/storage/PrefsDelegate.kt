package me.manga.yamiapk.core.storage

import android.content.Context
import androidx.core.content.edit
import me.manga.yamiapk.core.storage.StorageKeys.DEFAULT_STORE_NAME
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class PrefsDelegate<T>(
    private val context: Context,
    private val key: String,
    private val defaultValue: T,
    private val prefsName: String = DEFAULT_STORE_NAME
) : ReadWriteProperty<Any?, T> {

    private val prefs by lazy {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return with(prefs) {
            when (defaultValue) {
                is String       -> getString(key, defaultValue) as T
                is Int          -> getInt(key, defaultValue) as T
                is Boolean      -> getBoolean(key, defaultValue) as T
                is Float        -> getFloat(key, defaultValue) as T
                is Long         -> getLong(key, defaultValue) as T
                is Set<*> -> getStringSet(key, defaultValue as Set<String>) as T
                else -> throw IllegalArgumentException("Unsupported type: ${defaultValue!!::class.java}")
            }
        }
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        prefs.edit {
            apply {
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is Long -> putLong(key, value)
                    is Set<*> -> putStringSet(key, value as Set<String>)
                    else -> throw    IllegalArgumentException("Unsupported type: ${value!!::class.java}")
                }
            }
        }
    }


}
