package me.manga.yamiapk.data.local.converter

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class LocalDateTimeConverter {
    /** Persist as epoch milli */
    @TypeConverter
    fun fromLocalDateTime(dateTime: LocalDateTime?): Long? =
        dateTime?.toInstant(ZoneOffset.UTC)?.toEpochMilli()

    /** Read epoch milli back into a LocalDateTime */
    @TypeConverter
    fun toLocalDateTime(millis: Long?): LocalDateTime? =
        millis?.let {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC)
        }
}
