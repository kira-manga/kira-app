package me.manga.kira.sources_repositry.ar.dilar.models.home

import kotlinx.serialization.Serializable


@Serializable
data class Type(
    val id: Int? = 0,
    val name: String? = "",
    val reading_direction: String? = "",
    val title: String? = ""
)