package me.manga.yamiapk.sources_repositry.ar.dilar.models.chapter

import kotlinx.serialization.Serializable


@Serializable
data class Team(
    val id: Int? = 0,
    val name: String? = "",
    val rating: Int? = 0,
)