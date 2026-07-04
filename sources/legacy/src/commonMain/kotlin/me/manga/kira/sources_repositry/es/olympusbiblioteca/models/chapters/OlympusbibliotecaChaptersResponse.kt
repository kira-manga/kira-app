package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.chapters

import kotlinx.serialization.Serializable


@Serializable
data class OlympusbibliotecaChaptersResponse(
    val `data`: List<Data?>? = listOf(),
    val links: Links? = Links(),
    val meta: Meta? = Meta()
)