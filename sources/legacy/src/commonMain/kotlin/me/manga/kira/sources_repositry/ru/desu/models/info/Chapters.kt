package me.manga.kira.sources_repositry.ru.desu.models.info

import kotlinx.serialization.Serializable

@Serializable
data class Chapters(
    val count: Int? = 0,
    val first: First? = First(),
    val last: Last? = Last(),
    val list: List<Item0?>? = listOf(),
    val updated: Updated? = Updated()
)