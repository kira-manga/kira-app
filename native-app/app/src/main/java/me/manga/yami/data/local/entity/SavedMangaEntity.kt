package me.manga.yamiapk.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import me.manga.yamiapk.data.local.converter.StringListConverter

@Entity(
    tableName = "saved_manga",
            indices = [ Index(value = ["url"], unique = true) ]

)

@TypeConverters(StringListConverter::class)
data class SavedMangaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val api: String,
    val language: String,
    val url: String,
    val imageUrl: String,
    val title: String,
    val description: String,
    val status: String,
    val rating: String?,
    val genres: List<String>,
    val savedTimestamp: Long = System.currentTimeMillis(),
    val lastOpenTimestamp : Long = System.currentTimeMillis(),
    val isLiked: Boolean = false,
    val isWatchingNow: Boolean = false
)