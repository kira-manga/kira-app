package me.manga.yamiapk.presentation.features.download.data

sealed class DownloadState {
    data class InProgress(
        val totalImages: Int,
        val downloadedImages: Int,
        val currentImageUrl: String
    ) : DownloadState()
    data class Compressing(
        val totalImages: Int
    ) : DownloadState()
    data class Complete(val localPaths: List<String>) : DownloadState()

    data class Error(
        val exception: Throwable,
        val downloadedImages: Int,
        val totalImages: Int
    ) : DownloadState()
}