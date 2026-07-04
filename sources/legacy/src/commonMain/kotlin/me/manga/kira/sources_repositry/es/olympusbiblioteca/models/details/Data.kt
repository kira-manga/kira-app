package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.details

import kotlinx.serialization.Serializable


@Serializable
data class Data(
    val bookmark_count: Int? = 0,
    val bookmarked: Boolean? = false,
    val chapter_count: Int? = 0,
    val cover: String? = "",
    val created_at: String? = "",
    val first_chapter: FirstChapter? = FirstChapter(),
    val gallery: List<String?>? = listOf(),
    val genres: List<Genre?>? = listOf(),
    val id: Int? = 0,
    val like_count: Int? = 0,
    val liked: Boolean? = false,
    val name: String? = "",
    val rating: Int? = 0,
    val slug: String? = "",
    val status: Status? = Status(),
    val summary: String? = "",
    val type: String? = "",
    val view_count: Int? = 0
)