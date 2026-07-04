package me.manga.yamiapk.sources_repositry.es.manhwaweb.models.home

import kotlinx.serialization.Serializable

@Serializable
data class ManhwasRaw(
    val _demografi: String? = "",
    val _plataforma: String? = "",
    val _tipo: String? = "",
    val chapter: Double? = 0.0,
    val create: Long? = 0,
    val gru_name: String? = "",
    val id_manhwa: String? = "",
    val id_rel: String? = "",
    val img: String? = "",
    val lgbt: String? = "",
    val name_manhwa: String? = ""
)