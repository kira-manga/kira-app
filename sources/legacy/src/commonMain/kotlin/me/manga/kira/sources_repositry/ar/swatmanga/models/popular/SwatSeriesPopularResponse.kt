package me.manga.kira.sources_repositry.ar.swatmanga.models.popular

import kotlinx.serialization.SerialName

import kotlinx.serialization.Serializable


@Serializable
data class SwatSeriesPopularResponse(
    @SerialName("results")
    val swatPopularResults: List<SwatPopularResult>? = listOf()
)