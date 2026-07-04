package me.manga.yamiapk.data.local.converter

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class StringListConverter {
    private val gson = Gson()
    private val type = object : TypeToken<List<String>>() {}.type

    @TypeConverter
    fun fromString(value: String): List<String> {
        return gson.fromJson(value, type)
    }

    @TypeConverter
    fun fromList(list: List<String>): String {
        return gson.toJson(list, type)
    }
} 