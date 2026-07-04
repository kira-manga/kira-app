package me.manga.kira.sources_repositry.ar.dilar.models

import kotlinx.serialization.Serializable
import me.manga.kira.sources_repositry.ar.dilar.models.home.Release


@Serializable
data class DilarResponse(
    val releases: List<Release?>? = listOf(),
    val totalReleases: Int? = 0
)