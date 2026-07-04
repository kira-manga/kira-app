package me.manga.yamiapk.sources_repositry.ar.swatmanga.models.home

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class SwatResult(
    val chapters: List<Chapter?>? = listOf(),
    val genres: List<Genre?>? = listOf(),
    @SerialName("is_hot")
    val isHot: Boolean? = false,
    @SerialName("latest_chapter_updated_at")
    val latestChapterUpdatedAt: String? = "",
    val poster: Poster? = Poster(),
    val rating: String? = "",
    @SerialName("serie_id")
    val serieId: Int? = 0,
    val slug: String? = "",
    val status: Status? = Status(),
    val title: String? = "",
    val type: Type? = Type(),
    @SerialName("views_count")
    val viewsCount: Int? = 0
)