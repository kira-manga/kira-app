package me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.home

import kotlinx.serialization.Serializable


@Serializable
data class Data(
    val cover: String? = "",
    val cover_srcset: String? = "",
    val id: Int? = 0,
    val last_chapters: List<LastChapter?>? = listOf(),
    val name: String? = "",
    val slug: String? = "",
    val status: String? = "",
    val type: String? = ""
)