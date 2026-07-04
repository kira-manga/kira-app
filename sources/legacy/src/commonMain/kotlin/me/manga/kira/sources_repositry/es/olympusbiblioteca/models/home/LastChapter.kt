package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.home

import kotlinx.serialization.Serializable


@Serializable
data class LastChapter(
    val id: Int? = 0,
    val name: String? = "",
    val published_at: String? = ""
)