package me.manga.kira.sources_repositry.en.comick_io.models.homev2

import kotlinx.serialization.Serializable

@Serializable

data class MdComics(
    val content_rating: String? = "",
    val country: String? = "",
    val cover_url: String? = "",
    val created_at: String? = "",
    val demographic: Int? = 0,
    val final_chapter: String? = "",
    val genres: List<Int?>? = listOf(),
    val hid: String? = "",
    val id: Int? = 0,
    val is_english_title: Boolean? = false,
    val last_chapter: Double? = 0.0,
    val md_covers: List<MdCover?>? = listOf(),
    val md_titles: List<MdTitle?>? = listOf(),
    val slug: String? = "",
    val status: Int? = 0,
    val title: String? = "",
    val translation_completed: Boolean? = false
)