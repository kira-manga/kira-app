package me.manga.yamiapk.sources_repositry.ar.dilar.models

import kotlinx.serialization.Serializable
import me.manga.yamiapk.sources_repositry.ar.dilar.models.home.Release


@Serializable
data class DilarResponse(
    val releases: List<Release?>? = listOf(),
    val totalReleases: Int? = 0
)