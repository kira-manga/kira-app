package me.manga.yamiapk.sources_repositry.ar.swatmanga.models.details


import kotlinx.serialization.Serializable


@Serializable
data class Cover(
    val medium: String? = "",
    val thumbnail: String? = ""
)