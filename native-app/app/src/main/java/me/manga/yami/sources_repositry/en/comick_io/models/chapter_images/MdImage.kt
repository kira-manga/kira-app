package me.manga.yamiapk.sources_repositry.en.comick_io.models.chapter_images

import kotlinx.serialization.Serializable

@Serializable
data class MdImage(
    val b2key: String?,
    val h: Int?,
    val name: String?,
    val s: Int?,
    val w: Int?
)