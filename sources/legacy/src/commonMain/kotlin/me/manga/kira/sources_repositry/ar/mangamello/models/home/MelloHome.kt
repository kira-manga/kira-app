package me.manga.kira.sources_repositry.ar.mangamello.models.home

import kotlinx.serialization.Serializable

@Serializable
data class MelloHome(
    val `data`: List<Data?>? = listOf(),
    val links: Links? = Links(),
    val meta: Meta? = Meta()
)