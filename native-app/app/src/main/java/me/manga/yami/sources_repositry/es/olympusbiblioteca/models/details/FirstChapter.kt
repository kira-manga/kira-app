package me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.details

import kotlinx.serialization.Serializable


@Serializable
data class FirstChapter(
    val id: Int? = 0,
    val name: String? = ""
)