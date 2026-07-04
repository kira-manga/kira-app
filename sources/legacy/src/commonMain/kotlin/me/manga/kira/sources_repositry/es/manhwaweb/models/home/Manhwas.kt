package me.manga.kira.sources_repositry.es.manhwaweb.models.home

import kotlinx.serialization.Serializable

@Serializable
data class Manhwas(
    val _manhwas: List<Manhwa?>? = listOf(),
    val manhwas_esp: List<ManhwasEsp?>? = listOf(),
    val manhwas_raw: List<ManhwasRaw?>? = listOf()
)