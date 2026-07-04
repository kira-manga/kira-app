package me.manga.kira.sources_repositry.ru.desu.models.home

import kotlinx.serialization.Serializable

@Serializable
data class Image(
    val original: String? = "",
    val preview: String? = "",
    val x120: String? = "",
    val x225: String? = "",
    val x32: String? = "",
    val x48: String? = ""
)