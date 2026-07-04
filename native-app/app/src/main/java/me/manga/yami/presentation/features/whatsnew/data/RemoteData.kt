package me.manga.yamiapk.presentation.features.whatsnew.data

import kotlinx.serialization.Serializable

@kotlinx.serialization.Serializable
data class RemoteWhatsNewFeature(
    val title: Map<String, String>,
    val description: Map<String, String>,
    val mediaType: String,
    val imageRes: String? = null,
    val imageResList: List<String> = emptyList(),
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val isNew: Boolean = false,
    val version: String? = null
)

@Serializable
data class WhatsNewResponse(
    val features: List<RemoteWhatsNewFeature>,
)