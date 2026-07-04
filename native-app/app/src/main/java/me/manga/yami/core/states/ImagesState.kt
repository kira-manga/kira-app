package me.manga.yamiapk.core.states

sealed class ImagesState {
    object Loading: ImagesState()
    data class Success(val url: String): ImagesState()
    data class Error(val url: String, val throwable: Throwable): ImagesState()
}