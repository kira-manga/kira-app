package me.manga.yamiapk.presentation.features.download.data

import me.manga.yamiapk.data.local.entity.ChapterNotification
import me.manga.yamiapk.data.local.entity.SavedChapterEntity

sealed class DownloadRequest {
    data class Chapter(val chapter: SavedChapterEntity)    : DownloadRequest()
    data class Notification(val notification: ChapterNotification)     : DownloadRequest()
}