package me.manga.kira.sources_repositry.en.comick_io.models.homev2

import kotlinx.serialization.Serializable

@Serializable

data class MdCover(
    val b2key: String? = "",
    val h: Int? = 0,
    val w: Int? = 0
)