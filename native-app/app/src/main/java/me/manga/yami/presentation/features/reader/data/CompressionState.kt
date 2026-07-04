package me.manga.yamiapk.presentation.features.reader.data

data class CompressionState(
    val isCompressing: Boolean = false,
    val error: String? = null,
    val isCompleted: Boolean = false
)