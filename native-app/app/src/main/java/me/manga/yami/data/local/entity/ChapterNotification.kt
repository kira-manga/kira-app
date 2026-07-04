package me.manga.yamiapk.data.local.entity

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import java.time.LocalDate

@Entity(tableName = "notifications")
@Parcelize
data class ChapterNotification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val api: String,
    val language: String,
    val mangaId: Long,
    val mangaTitle: String,
    val mangaImageUrl: String,
    val mangaUrl: String,
    val chapterId: Long,
    val chapterNumber: String,
    val chapterUrl: String,
    val notificationDate: LocalDate = LocalDate.now(),
    val isRead: Boolean = false,
    val isDownloaded: Boolean = false,
    val localImagePaths: List<String> = emptyList() // Store local paths of downloaded images
): Parcelable
