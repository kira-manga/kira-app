package me.manga.yamiapk.sources_repositry.ar.mangamello.models.home

import kotlinx.serialization.Serializable

@Serializable
data class Links(
    val first: String? = "",
    val last: String? = "",
    val next: String? = "",
)