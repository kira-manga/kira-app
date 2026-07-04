package me.manga.yamiapk.presentation.features.library.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import me.manga.yamiapk.data.local.entity.SavedMangaEntity

data class SavedMangaWithMetrics(
    @Embedded val manga: SavedMangaEntity,
    @ColumnInfo(name = "totalChapters") val totalChapters: Int,
    @ColumnInfo(name = "readCount") val readCount: Int,
    @ColumnInfo(name = "downloadedCount") val downloadedCount: Int,
    @ColumnInfo(name = "bookmarkedCount") val bookmarkedCount: Int,
    @ColumnInfo(name = "lastReadTs") val lastReadTs: Long? , // null if no chapters
)