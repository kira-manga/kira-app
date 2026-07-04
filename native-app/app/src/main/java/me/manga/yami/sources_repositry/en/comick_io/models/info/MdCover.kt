package me.manga.yamiapk.sources_repositry.en.comick_io.models.info

import kotlinx.serialization.Serializable

@Serializable
data class MdCover(
    val b2key: String?,
    val h: Int?,
    val vol: String?,
    val w: Int?
)