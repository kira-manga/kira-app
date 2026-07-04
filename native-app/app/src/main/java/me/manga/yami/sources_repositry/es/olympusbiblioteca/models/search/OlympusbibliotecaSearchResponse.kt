package me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.search

import kotlinx.serialization.Serializable


@Serializable
data class OlympusbibliotecaSearchResponse(
    val `data`: List<Data?>? = listOf()
)