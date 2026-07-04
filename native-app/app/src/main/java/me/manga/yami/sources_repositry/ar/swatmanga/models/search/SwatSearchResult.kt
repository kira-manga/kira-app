package me.manga.yamiapk.sources_repositry.ar.swatmanga.models.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.manga.yamiapk.sources_repositry.ar.swatmanga.models.home.Genre
import me.manga.yamiapk.sources_repositry.ar.swatmanga.models.home.Poster
import me.manga.yamiapk.sources_repositry.ar.swatmanga.models.home.Status
import me.manga.yamiapk.sources_repositry.ar.swatmanga.models.home.Type


@Serializable
data class SwatSearchResult(
    val genres: List<Genre?>? = listOf(),
    @SerialName("id")
    val id: Int? = 0,
    val is_favorite: Boolean? = false,
    val is_followed: Boolean? = false,
    val is_hot: Boolean? = false,
    val poster: Poster? = Poster(),
    val rating: String? = "",
    val slug: String? = "",
    val status: Status? = Status(),
    val status_id: Int? = 0,
    val title: String? = "",
    val type: Type? = Type(),
    val type_id: Int? = 0,
    val views_count: Int? = 0
)