package me.manga.yamiapk.data.local.converter

import androidx.room.TypeConverter
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourceState
import java.util.Date

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }


    @TypeConverter
    fun fromSourceState(state: SourceState): String = state.name
    @TypeConverter
    fun toSourceState(value: String?): SourceState {
        return if (value.isNullOrEmpty()) {
            SourceState.STOPPED // Default fallback for null/empty values
        } else {
            try {
                SourceState.valueOf(value.uppercase().trim())
            } catch (e: IllegalArgumentException) {
                // Handle unknown enum values gracefully
                SourceState.STOPPED // Fallback to safe default
            }
        }
    }
}