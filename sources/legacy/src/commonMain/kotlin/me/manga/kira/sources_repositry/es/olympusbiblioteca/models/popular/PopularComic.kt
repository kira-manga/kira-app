package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.popular

import kotlinx.serialization.Serializable

@Serializable
data class PopularComic(
    val id: Int? = 0,
    val name: String? = "",
    val slug: String? = "",
    val status: Status? = Status(),
    val cover: String? = "",
    val cover_srcset: String? = "",
    val type: String? = ""
)

@Serializable
data class Status(
    val id: Int? = 0,
    val name: String? = "",
    val created_at: String? = "",
    val updated_at: String? = ""
)