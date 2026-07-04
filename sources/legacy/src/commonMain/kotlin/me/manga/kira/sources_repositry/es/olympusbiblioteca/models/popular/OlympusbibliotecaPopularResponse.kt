package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.popular

import kotlinx.serialization.Serializable


@Serializable
data class OlympusbibliotecaPopularResponse(
    val `data`: Data? = Data(),
    val success: Boolean? = false
)