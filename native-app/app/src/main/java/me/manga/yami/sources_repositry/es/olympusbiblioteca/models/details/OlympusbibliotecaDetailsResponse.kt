package me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.details

import kotlinx.serialization.Serializable


@Serializable
data class OlympusbibliotecaDetailsResponse(
    val `data`: Data? = Data()
)