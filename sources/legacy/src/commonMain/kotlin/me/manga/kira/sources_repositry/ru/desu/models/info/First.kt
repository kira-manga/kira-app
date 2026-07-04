package me.manga.kira.sources_repositry.ru.desu.models.info

import kotlinx.serialization.Serializable

@Serializable
data class First(
    val ch: String? = "",
    val date: String? = "",
    val name: String? = "",
    val vol: String? = ""
)