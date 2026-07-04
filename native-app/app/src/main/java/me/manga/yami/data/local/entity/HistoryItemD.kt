package me.manga.yamiapk.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(tableName = "history_items")
data class HistoryItemD(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val api: String,
    val language: String,
    val mangaId: Long = 0,
    val mangaUrl: String,
    val mangaTitle: String,
    val mangaImageUrl: String,
    val chapterUrl: String,
    val chapterTitle: String,
    val isDownloaded :Boolean,
    val localImagePaths :List<String> = listOf(),
    val lastReadDate: LocalDateTime = LocalDateTime.now(),
    val lastReadPage: Int = 0,
    val totalPages: Int = 0
) 
