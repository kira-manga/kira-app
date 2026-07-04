package me.manga.yamiapk.data.local.converter

import androidx.room.TypeConverter
import me.manga.yamiapk.presentation.features.download.data.DownloadingState

class DownloadingStateConverter {

    @TypeConverter
    fun fromDownloadState(state: DownloadingState): String =
        state.name

    @TypeConverter
    fun toDownloadState(name: String): DownloadingState =
        DownloadingState.valueOf(name)
}