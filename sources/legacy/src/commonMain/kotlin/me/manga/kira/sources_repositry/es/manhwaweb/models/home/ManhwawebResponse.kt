package me.manga.kira.sources_repositry.es.manhwaweb.models.home

import kotlinx.serialization.Serializable


@Serializable
data class ManhwawebResponse(
    val manhwas: Manhwas? = Manhwas()
)