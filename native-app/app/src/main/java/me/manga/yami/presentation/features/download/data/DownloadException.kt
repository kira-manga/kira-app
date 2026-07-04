package me.manga.yamiapk.presentation.features.download.data

class DownloadException(
    message: String,
    cause: Throwable,
    val downloaded: Int,
    val total: Int
) : Exception(message, cause)