package me.manga.kira.sources_repositry.ar.mangamello.models.search

import kotlinx.serialization.Serializable

@Serializable
data class MelloSearch(
    val `data`: List<DataSh?>? = listOf(),
    val links: Links? = Links(),
    val meta: Meta? = Meta()
)