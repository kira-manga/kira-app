package me.manga.yamiapk.presentation.features.download.data

enum class DownloadingState {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    COMPRESSING
}