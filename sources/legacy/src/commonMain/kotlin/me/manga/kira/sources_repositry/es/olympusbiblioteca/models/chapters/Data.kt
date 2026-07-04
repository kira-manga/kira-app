package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.chapters

import kotlinx.serialization.Serializable


@Serializable
data class Data(
    val id: Int? = 0,
    val name: String? = "",
    val published_at: String? = "",
)