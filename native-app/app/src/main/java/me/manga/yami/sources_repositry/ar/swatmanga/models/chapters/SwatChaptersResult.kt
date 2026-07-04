package me.manga.yamiapk.sources_repositry.ar.swatmanga.models.chapters

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class SwatChaptersResult(
    val chapter: String? = "",
    val created_at: String? = "",
    val created_at_humanized: String? = "",
    @SerialName("id")
    val id: Double? = 0.0,
    val is_read: Boolean? = false,
    val serie: Int? = 0,
    val slug: String? = "",
    val title: String? = "",
    val updated_at: String? = "",
    val views_count: Int? = 0
)