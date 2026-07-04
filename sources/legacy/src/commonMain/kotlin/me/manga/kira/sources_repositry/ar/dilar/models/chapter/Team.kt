package me.manga.kira.sources_repositry.ar.dilar.models.chapter

import kotlinx.serialization.Serializable


@Serializable
data class Team(
    val id: Int? = 0,
    val name: String? = "",
    val rating: Int? = 0,
)