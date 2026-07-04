package me.manga.kira.sources_repositry.en.comick_io.models.info

import kotlinx.serialization.Serializable


@Serializable
data class Author(
    val name: String? = null,
    val slug: String? = null
)