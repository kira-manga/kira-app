package me.manga.kira.sources_repositry.ar.swatmanga.models.search


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class SwatSeriesSearchResponse(

    @SerialName("results")
    val swatSearchResults: List<SwatSearchResult?>? = listOf()
)