package me.manga.kira.sources_repositry.ar.mangamello.models.info

import kotlinx.serialization.Serializable

@Serializable
data class MelloInfo(
    val `data`: DataIn? = DataIn()
)