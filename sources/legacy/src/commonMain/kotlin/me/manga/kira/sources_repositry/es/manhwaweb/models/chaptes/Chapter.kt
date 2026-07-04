package me.manga.kira.sources_repositry.es.manhwaweb.models.chaptes

import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val img: List<String?>? = listOf(),
)