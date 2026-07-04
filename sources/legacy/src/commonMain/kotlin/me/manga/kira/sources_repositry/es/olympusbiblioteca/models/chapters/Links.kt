package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.chapters

import kotlinx.serialization.Serializable


@Serializable
data class Links(
    val first: String? = "",
    val last: String? = "",
    val next: String? = "",
)