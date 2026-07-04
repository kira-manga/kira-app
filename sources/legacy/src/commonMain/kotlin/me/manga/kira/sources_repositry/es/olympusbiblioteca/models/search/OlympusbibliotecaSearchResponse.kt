package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.search

import kotlinx.serialization.Serializable


@Serializable
data class OlympusbibliotecaSearchResponse(
    val `data`: List<Data?>? = listOf()
)