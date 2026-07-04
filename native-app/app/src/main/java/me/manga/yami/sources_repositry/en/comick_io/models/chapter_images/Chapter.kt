package me.manga.yamiapk.sources_repositry.en.comick_io.models.chapter_images

import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val adsense: Boolean?,
    val chap: String?,
    val md_images: List<MdImage>,

)