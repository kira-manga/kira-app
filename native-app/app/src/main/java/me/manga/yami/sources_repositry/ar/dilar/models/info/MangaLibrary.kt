package me.manga.yamiapk.sources_repositry.ar.dilar.models.info

import kotlinx.serialization.Serializable

@Serializable
data class MangaLibrary(
    val completed: Int? = 0,
    val dropped: Int? = 0,
    val favorite: Int? = 0,
    val on_hold: Int? = 0,
    val plan_to_read: Int? = 0,
    val planning: Int? = 0,
    val reading: Int? = 0,
    val suggestion: Int? = 0
)