package me.manga.yamiapk.core.progress

sealed class ProgressState {
    data object Idle : ProgressState()

    data class Loading(
        val percent: Int,
        val bytesRead: Long,
        val totalBytes: Long
    ) : ProgressState()

    data object Completed : ProgressState()

    data class Failed(val errorMessage: String? = null) : ProgressState()
}
