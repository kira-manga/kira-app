package me.manga.yamiapk.sources_repositry.ar.mangamello.models.search

import kotlinx.serialization.Serializable

@Serializable
data class Genre(
    val created_at: String? = "",
    val id: Int? = 0,
    val name: String? = "",
    val updated_at: String? = ""
)