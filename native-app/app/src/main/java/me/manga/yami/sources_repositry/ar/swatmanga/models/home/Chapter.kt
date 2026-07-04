package me.manga.yamiapk.sources_repositry.ar.swatmanga.models.home


import kotlinx.serialization.Serializable


@Serializable
data class Chapter(
    val chapter: String? = "",
    val id: Int? = 0,
    val title: String? = ""
)