package me.manga.kira.sources_repositry.pt.manhastro.models.imgs

import kotlinx.serialization.Serializable

@Serializable
data class ChaptersPagesv2(
    val `data`: Data? = Data(),
    val success: Boolean? = false
)