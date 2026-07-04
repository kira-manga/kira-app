package me.manga.kira.sources_repositry.pt.manhastro.models.imgs

import kotlinx.serialization.Serializable


@Serializable
data class Data(
    val chapter: Chapter? = Chapter(),
    val text: Boolean? = false
)