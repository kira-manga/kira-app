package me.manga.yamiapk.data.local.converter

import androidx.room.TypeConverter
import java.time.LocalDate

class LocalDateConverter {
    /** Store LocalDate as the number of days since the epoch (1970-01-01). */
    @TypeConverter
    fun fromLocalDate(date: LocalDate?): Long? =
        date?.toEpochDay()

    /** Read epoch-day back into a LocalDate. */
    @TypeConverter
    fun toLocalDate(epochDay: Long?): LocalDate? =
        epochDay?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    fun fromTimestamp(value: String?): LocalDate? {
        return value?.let { LocalDate.parse(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: LocalDate?): String? {
        return date?.toString()
    }
}
