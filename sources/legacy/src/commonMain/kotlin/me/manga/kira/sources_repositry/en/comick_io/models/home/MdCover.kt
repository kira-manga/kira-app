package me.manga.kira.sources_repositry.en.comick_io.models.home

import kotlinx.serialization.Serializable

@Serializable
data class MdCover(
    val b2key: String,
    val h: Int,
    val w: Int
)