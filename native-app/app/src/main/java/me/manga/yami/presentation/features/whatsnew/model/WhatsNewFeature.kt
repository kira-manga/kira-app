package me.manga.yamiapk.presentation.features.whatsnew.model

import androidx.annotation.DrawableRes

data class WhatsNewFeature(
    val title: String,
    val description: String,
    val mediaType: MediaType,
    val imageRes: Int?  = null, // Single image (for backward compatibility)
    val imageResList: List<Int> = emptyList(), // Multiple images
    val imageUrl: String? = null, // Single image URL
    val imageUrlList: List<String> = emptyList(), // Multiple image URLs
    val videoUrl: String? = null,
    val isNew: Boolean = false,
    val version: String? = null
)
