package me.manga.yamiapk.presentation.features.whatsnew.data

data class LocalizedFeature(
    val title: String,
    val description: String,
    val mediaType: String,
    val imageRes: String?,
    val imageList: List<String>,
    val imageUrl: String?,
    val videoUrl: String?,
    val isNew: Boolean,
    val version: String?
)