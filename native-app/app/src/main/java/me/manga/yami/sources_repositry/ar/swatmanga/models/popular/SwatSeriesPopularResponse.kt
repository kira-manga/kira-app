package me.manga.yamiapk.sources_repositry.ar.swatmanga.models.popular

import kotlinx.serialization.SerialName

import kotlinx.serialization.Serializable


@Serializable
data class SwatSeriesPopularResponse(
    @SerialName("results")
    val swatPopularResults: List<SwatPopularResult>? = listOf()
)