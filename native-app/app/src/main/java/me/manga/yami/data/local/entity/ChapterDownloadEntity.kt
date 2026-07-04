package me.manga.yamiapk.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.manga.yamiapk.presentation.features.download.data.DownloadingState

@Entity(tableName = "chapter_downloads",
    indices = [ Index(value = ["chapterId"], unique = true) ]
)
data class ChapterDownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val number : String,
    val chapterId: Long,
    val mangaId: Long,
    val api: String,
    val mangaTitle : String? = null,
    val url : String,
    val state: DownloadingState,      // QUEUED, RUNNING, SUCCESS, FAILED
    val progress: Int,             // 0–100
    val errorMsg: String? = null
)
