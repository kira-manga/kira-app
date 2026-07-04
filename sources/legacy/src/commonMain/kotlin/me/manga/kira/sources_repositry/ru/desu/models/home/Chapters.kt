package me.manga.kira.sources_repositry.ru.desu.models.home

import kotlinx.serialization.Serializable

@Serializable
data class Chapters(
    val first: First? = First(),
    val last: Last? = Last(),
    val updated: Updated? = Updated()
)