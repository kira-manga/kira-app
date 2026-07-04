package me.manga.yamiapk.sources_repositry.es.manhwaweb.models.chaptes

import kotlinx.serialization.Serializable

@Serializable
data class ChaptersResponse(
    val _id: String? = "",
    val chapter: Chapter? = Chapter(),
    val erotico: String? = "",
    val name: String? = "",
    val roto: String? = ""
)