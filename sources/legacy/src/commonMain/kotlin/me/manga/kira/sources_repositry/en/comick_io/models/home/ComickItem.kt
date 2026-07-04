package me.manga.kira.sources_repositry.en.comick_io.models.home

import kotlinx.serialization.Serializable


@Serializable
data class ComickItem(
    val cover_url: String?,
    val desc: String?,
    val genres: List<Int?>,
    val hid: String?,
    val id: Int?,
    val is_english_title: Boolean?,
    val last_chapter: Double?,
    val md_covers: List<MdCover?>,
    val md_titles: List<MdTitle?>,
    val mu_comics: MuComics?,
    val slug: String?,
    val status: Int?,
    val title: String?,
    val uploaded_at: String?,

    )