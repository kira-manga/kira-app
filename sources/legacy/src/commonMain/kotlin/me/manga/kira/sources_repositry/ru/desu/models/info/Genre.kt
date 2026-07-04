package me.manga.kira.sources_repositry.ru.desu.models.info

import kotlinx.serialization.Serializable

@Serializable
data class Genre(
    val id: Int? = 0,
    val kind: String? = "",
    val russian: String? = "",
    val text: String? = ""
)