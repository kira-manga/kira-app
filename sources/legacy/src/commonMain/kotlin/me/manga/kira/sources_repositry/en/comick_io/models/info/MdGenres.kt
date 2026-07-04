package me.manga.kira.sources_repositry.en.comick_io.models.info

import kotlinx.serialization.Serializable

@Serializable
data class MdGenres(
    val group: String?,
    val name: String?,
    val slug: String?,
    val type: String?
)