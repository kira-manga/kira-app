package me.manga.kira.sources_repositry.es.manhwaweb.models.library

import kotlinx.serialization.Serializable

@Serializable
data class Data(
    val _categoris: List<Int?>? = listOf(),
    val _demografi: String? = "",
    val _erotico: String? = "",
    val _id: String? = "",
    val _imagen: String? = "",
    val _numero_cap: Double? = 0.0,
    val _plataforma: String? = "",
    val _status: String? = "",
    val _tipo: String? = "",
    val real_id: String? = "",
    val the_real_name: String? = ""
)