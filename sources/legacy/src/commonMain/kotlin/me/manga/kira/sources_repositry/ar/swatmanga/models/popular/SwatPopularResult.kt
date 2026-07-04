package me.manga.kira.sources_repositry.ar.swatmanga.models.popular

import kotlinx.serialization.Serializable


@Serializable
data class SwatPopularResult(
    val chapter: String? = "",
    val created_at: String? = "",
    val created_at_humanized: String? = "",
    val id: Int? = 0,
    val is_read: Boolean? = false,
    val serie: Serie? = Serie(),
    val slug: String? = "",
    val title: String? = "",
    val views_count: Int? = 0
)