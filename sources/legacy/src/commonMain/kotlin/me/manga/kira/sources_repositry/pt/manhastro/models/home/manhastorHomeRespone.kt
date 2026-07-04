package me.manga.kira.sources_repositry.pt.manhastro.models.home

import kotlinx.serialization.Serializable


@Serializable
data class manhastorHomeRespone(
    val `data`: List<Data?>? = listOf(),
    val success: Boolean? = false
)