package me.manga.yamiapk.sources_repositry.en.comick_io.models.chapters

import kotlinx.serialization.Serializable

@Serializable
data class Traits(
    val gravatar: String?,
    val username: String?
)