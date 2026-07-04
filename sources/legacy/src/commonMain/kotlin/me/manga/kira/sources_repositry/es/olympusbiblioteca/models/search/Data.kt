package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.search

import kotlinx.serialization.Serializable


@Serializable
data class Data(
    val cover: String? = "",
    val id: Int? = 0,
    val name: String? = "",
    val slug: String? = "",
    val type: String? = ""
)