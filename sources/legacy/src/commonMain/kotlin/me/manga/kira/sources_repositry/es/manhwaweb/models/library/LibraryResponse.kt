package me.manga.kira.sources_repositry.es.manhwaweb.models.library

import kotlinx.serialization.Serializable

@Serializable
data class LibraryResponse(
    val `data`: List<Data?>? = listOf(),
    val next: Boolean? = false
)