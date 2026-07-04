package me.manga.kira.sources_repositry.ar.swatmanga.models.chapters_images


import kotlinx.serialization.Serializable


@Serializable
data class SwatSeriesImagesResponse(
    val chapter: String? = "",
    val created_at: String? = "",
    val id: Int? = 0,
    val images: List<Image?>? = listOf(),
    val slug: String? = "",
    val title: String? = "",
    val updated_at: String? = "",
    val views_count: Int? = 0
)