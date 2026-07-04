package me.manga.kira.sources_repositry.ar.swatmanga.models.popular

import me.manga.kira.sources_repositry.ar.swatmanga.models.home.Genre
import me.manga.kira.sources_repositry.ar.swatmanga.models.home.Poster
import me.manga.kira.sources_repositry.ar.swatmanga.models.home.Status
import me.manga.kira.sources_repositry.ar.swatmanga.models.home.Type

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class Serie(
    val genres: List<Genre>? = listOf(),
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