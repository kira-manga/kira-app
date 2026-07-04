package me.manga.kira.sources_repositry.es.manhwaweb.models.info

import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val chapter: Double? = 0.0,
    val create: Long? = 0,
    val img: List<String?>? = listOf(),
    val link: String? = ""
)