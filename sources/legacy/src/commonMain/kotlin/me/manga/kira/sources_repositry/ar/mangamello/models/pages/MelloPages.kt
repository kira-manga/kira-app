package me.manga.kira.sources_repositry.ar.mangamello.models.pages

import kotlinx.serialization.Serializable

@Serializable
data class MelloPages(
    val `data`: Data? = Data()
)