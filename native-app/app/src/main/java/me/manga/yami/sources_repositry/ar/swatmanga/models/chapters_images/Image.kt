package me.manga.yamiapk.sources_repositry.ar.swatmanga.models.chapters_images


import kotlinx.serialization.Serializable


@Serializable
data class Image(
    val image: String? = "",
    val order: Int? = 0
)