package me.manga.kira.sources_repositry.ar.mangamello.models.home

import kotlinx.serialization.Serializable

@Serializable
data class Link(
    val active: Boolean? = false,
    val label: String? = "",
    val url: String? = ""
)