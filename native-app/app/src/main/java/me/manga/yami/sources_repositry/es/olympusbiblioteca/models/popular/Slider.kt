package me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.popular

import kotlinx.serialization.Serializable


@Serializable
data class Slider(
    val banner: String? = "",
    val banner_mini_srcset: String? = "",
    val banner_srcset: String? = "",
    val description: String? = "",
    val title: String? = "",
    val url: String? = ""
)