package me.manga.kira.sources_repositry.ar.swatmanga.models.home

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class SwatSeriesHomeResponse(
    @SerialName("results")
    val swatResults: List<SwatResult?>? = listOf()
)



