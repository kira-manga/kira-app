package me.manga.kira.sources_repositry.ar.mangamello.models.chapters

import kotlinx.serialization.Serializable

@Serializable
data class MelloChapters(
    val `data`: List<DataCh?>? = listOf(),
    val links: Links? = Links(),
    val meta: Meta? = Meta()
)