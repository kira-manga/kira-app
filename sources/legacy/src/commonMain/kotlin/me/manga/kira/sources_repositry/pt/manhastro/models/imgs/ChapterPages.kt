package me.manga.kira.sources_repositry.pt.manhastro.models.imgs

import kotlinx.serialization.Serializable

@Serializable
data class ChapterPages(
    val paginas: Map<String, String>? = emptyMap()
)