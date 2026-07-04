package me.manga.yamiapk.sources_repositry.ar.swatmanga.models.home

import kotlinx.serialization.Serializable


@Serializable

data class Poster(
    val medium: String? = "",
    val thumbnail: String? = ""
)