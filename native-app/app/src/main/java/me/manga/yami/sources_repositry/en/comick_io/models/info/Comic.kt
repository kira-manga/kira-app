package me.manga.yamiapk.sources_repositry.en.comick_io.models.info

import kotlinx.serialization.Serializable

@Serializable
data class Comic(
    val adsense: Boolean? = null,
    val bayesian_rating: String? = null,
    val chapter_count: Int? = null,
    val chapter_numbers_reset_on_new_volume_manual: Boolean? = null,
    val content_rating: String? = null,
    val country: String? = null,
    val cover_url: String? = null,
    val demographic: Int? = null,
    val desc: String? = null,

    val follow_count: Int? = null,
    val follow_rank: Int? = null,
    val hid: String? = null,
    val id: Int? = null,
    val iso639_1: String? = null,

    // <-- Change this from Int? to Double? so "50.5" will deserialize correctly
    val last_chapter: Double? = null,

    val login_required: Boolean? = null,
    val md_comic_md_genres: List<MdComicMdGenre?>? = null,
    val md_covers: List<MdCover?>? = null,
    val md_titles: List<MdTitle?>? = null,
    val mu_comics: MuComics? = null,
    val noindex: Boolean? = null,
    val parsed: String? = null,
    val rating_count: Int? = null,
    val slug: String? = null,
    val status: Int? = null,
    val title: String? = null,
    val translation_completed: Boolean? = null,
    val user_follow_count: Int? = null,
    val year: Int? = null
)
