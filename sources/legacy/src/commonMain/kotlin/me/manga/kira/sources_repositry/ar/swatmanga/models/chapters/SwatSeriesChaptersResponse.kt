package me.manga.kira.sources_repositry.ar.swatmanga.models.chapters


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class SwatSeriesChaptersResponse(

    @SerialName("results")
    val swatChaptersResults: List<SwatChaptersResult?>? = listOf()
)