package me.manga.yamiapk.sources_repositry.pt.manhastro.models.imgs

import kotlinx.serialization.Serializable

@Serializable
data class ChaptersPagesv2(
    val `data`: Data? = Data(),
    val success: Boolean? = false
)