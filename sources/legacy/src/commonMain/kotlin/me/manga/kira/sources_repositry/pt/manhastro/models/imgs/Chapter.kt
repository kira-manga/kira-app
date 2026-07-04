package me.manga.kira.sources_repositry.pt.manhastro.models.imgs

import kotlinx.serialization.Serializable


@Serializable
data class Chapter(
    val baseUrl: String? = "",
    val `data`: List<String?>? = listOf(),
    val hash: String? = ""
)