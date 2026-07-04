package me.manga.kira.sources_repositry.ar.swatmanga.models.home

import kotlinx.serialization.Serializable


@Serializable

data class Genre(
    val id: Int? = 0,
    val name: String? = "",
    val series_count: Int? = 0

)