package me.manga.kira.sources_repositry.ru.desu.models.info

import kotlinx.serialization.Serializable

@Serializable
data class Translator(
    val id: Int? = 0,
    val name: String? = "",
    val site: String? = ""
)