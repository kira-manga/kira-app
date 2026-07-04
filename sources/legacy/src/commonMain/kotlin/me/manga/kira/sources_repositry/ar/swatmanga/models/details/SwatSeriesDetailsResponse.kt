package me.manga.kira.sources_repositry.ar.swatmanga.models.details

import me.manga.kira.sources_repositry.ar.swatmanga.models.home.Genre
import me.manga.kira.sources_repositry.ar.swatmanga.models.home.Poster
import me.manga.kira.sources_repositry.ar.swatmanga.models.home.Status
import me.manga.kira.sources_repositry.ar.swatmanga.models.home.Type


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class SwatSeriesDetailsResponse(
    val allow_comments: Boolean? = false,
    val chapters_count: Int? = 0,
    val cover: Cover? = Cover(),
    val created_at: String? = "",
    val created_at_humanized: String? = "",
    val donations_count: Int? = 0,
    val favorites_count: Int? = 0,
    val followers_count: Int? = 0,
    val genres: List<Genre?>? = listOf(),
    val id: Int? = 0,
    val is_favorite: Boolean? = false,
    val is_followed: Boolean? = false,
    val is_hot: Boolean? = false,
    val letter: String? = "",
    val my_rating: Int? = 0,
    val poster: Poster? = Poster(),
    val published: String? = "",
    val rating: String? = "",
    val ratings_count: Int? = 0,
    val slug: String? = "",
    val status: Status? = Status(),
    val story: String? = "",
    val title: String? = "",
    val type: Type? = Type(),
    val updated_at: String? = "",
    val updated_at_humanized: String? = "",
    val views_count: Int? = 0
)