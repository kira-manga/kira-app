package me.manga.yamiapk.sources_repositry.ar.mangamello.models.pages

import kotlinx.serialization.Serializable

@Serializable

data class ChapterImage(
    val chapter_id: Int? = 0,
    val created_at: String? = "",
    val id: Int? = 0,
    val order: Int? = 0,
    val originalSrc: String? = "",
    val src: String? = "",
    val updated_at: String? = ""
)