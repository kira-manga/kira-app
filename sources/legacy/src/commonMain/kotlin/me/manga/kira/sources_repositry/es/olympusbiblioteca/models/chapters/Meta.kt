package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.chapters

import kotlinx.serialization.Serializable


@Serializable
data class Meta(
    val current_page: Int? = 0,
    val from: Int? = 0,
    val last_page: Int? = 0,
    val links: List<Link?>? = listOf(),
    val path: String? = "",
    val per_page: Int? = 0,
    val to: Int? = 0,
    val total: Int? = 0
)