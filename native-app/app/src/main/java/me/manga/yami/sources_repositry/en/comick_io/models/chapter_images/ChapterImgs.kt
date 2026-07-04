package me.manga.yamiapk.sources_repositry.en.comick_io.models.chapter_images

import kotlinx.serialization.Serializable

@Serializable
data class ChapterImgs(
    val canonical: String?,
    val chapTitle: String?,
    val chapter: Chapter,


)