package me.manga.kira.sources_repositry.ar.dilar.models.home

import kotlinx.serialization.Serializable




@Serializable
data class Manga(
    val banned: Boolean? = false,
    val banner: String? = "",
    val categories: List<Category?>? = listOf(),
    val chaps: Int? = 0,
    val commentable: Boolean? = false,
    val cover: String? = "",
    val cover_pos: Int? = 0,
    val genre_id: Int? = 0,
    val id: Int? = 0,
    val is_novel: Boolean? = false,
    val is_oneshot: Boolean? = false,
    val latest_chapterization_id: Int? = 0,
    val manga_type_id: Int? = 0,
    val mobile_exclusive: Boolean? = false,
    val rates_count: Int? = 0,
    val rating: String? = "",
    val reading_direction: String? = "",
    val rectangle_cover_pos: Int? = 0,
    val reviewed: Boolean? = false,
    val show_comments: Boolean? = false,
    val story_status: Int? = 0,
    val summary: String? = "",
    val time_stamp: Int? = 0,
    val title: String? = "",
    val translation_status: Int? = 0,
    val type: Type? = Type(),
    val uniq_visitors_count: Int? = 0,
    val vols: Int? = 0
)