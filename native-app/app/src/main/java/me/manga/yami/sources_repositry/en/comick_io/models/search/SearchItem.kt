package me.manga.yamiapk.sources_repositry.en.comick_io.models.search

import kotlinx.serialization.Serializable
import me.manga.yamiapk.sources_repositry.en.comick_io.models.info.FirstChap

@Serializable
data class SearchItem(
    val bayesian_rating: String?               = null,
    val content_rating: String?                = null,
    val country: String?                       = null,
    val cover_url: String?                     = null,
    val created_at: String?                    = null,
    val demographic: Int?                      = null,
    val desc: String?                          = null,
    val follow_count: Int?                     = null,
    val genres: List<Int?>?                    = null,
    val hid: String?                           = null,

    // ← Make highlight optional by giving a default
    val highlight: String?                     = null,

    val id: Int?                               = null,
    val is_english_title: Boolean?             = null,
    val last_chapter: Double?                  = null,
    val md_covers: List<MdCover?>?             = null,
    val md_titles: List<MdTitle?>?             = null,
    val mu_comics: MuComics?                   = null,
    val rating: String?                        = null,
    val rating_count: Int?                     = null,
    val slug: String?                          = null,
    val status: Int?                           = null,
    val title: String?                         = null,
    val translation_completed: Boolean?        = null,
    val uploaded_at: String?                   = null,
    val user_follow_count: Int?                = null,
    val view_count: Int?                       = null,
    val year: Int?                             = null,
    val firstChap: FirstChap?             = null
)
