package me.manga.yamiapk.sources_repositry.ar.mangamello.models.chapters

import kotlinx.serialization.Serializable

@Serializable
data class Link(
    val active: Boolean? = false,
    val label: String? = "",
    val url: String? = ""
)