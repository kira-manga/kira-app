package me.manga.kira.sources_repositry.es.manhwaweb.models.info

import kotlinx.serialization.Serializable


@Serializable
data class InfoResponse(
    val __v: Int? = 0,
    val _categoris: List<Map<String, String>?>? = emptyList(),
    val _creation: String? = "",
    val _demografi: String? = "",
    val _erotico: String? = "",
    val _id: String? = "",
    val _imagen: String? = "",
    val _name: String? = "",
    val _sinopsis: String? = "",
    val _status: String? = "",
    val chapters: List<Chapter?>? = listOf(),
    val name_esp: String? = "",
    val name_raw: String? = "",
    val real_id: String? = "",
    val the_real_name: String? = ""
)