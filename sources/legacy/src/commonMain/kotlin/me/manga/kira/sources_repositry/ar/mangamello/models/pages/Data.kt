package me.manga.kira.sources_repositry.ar.mangamello.models.pages

import kotlinx.serialization.Serializable

@Serializable

data class Data(
    val chapterImages: List<ChapterImage?>? = listOf(),
    val created_at: String? = "",
    val id: Int? = 0,
    val isCommentable: Int? = 0,
    val is_new: Boolean? = false,
    val manga_id: Int? = 0,
    val order: Double? = 0.0,          // <- was Int?
    val title: String? = "",
    val updated_at: String? = "",
    val views: Int? = 0
)