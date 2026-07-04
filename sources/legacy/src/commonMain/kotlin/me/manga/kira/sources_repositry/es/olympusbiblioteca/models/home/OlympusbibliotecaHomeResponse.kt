package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.home

import kotlinx.serialization.Serializable


@Serializable
data class OlympusbibliotecaHomeResponse(
    val `data`: List<Data?>? = listOf(),

)