package me.manga.kira.sources_repositry.en.comick_io.models.info

import kotlinx.serialization.Serializable

@Serializable
data class MuPublishers(
    val slug: String?,
    val title: String?
)