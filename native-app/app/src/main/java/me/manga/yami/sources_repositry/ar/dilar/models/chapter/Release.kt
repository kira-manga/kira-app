package me.manga.yamiapk.sources_repositry.ar.dilar.models.chapter

import kotlinx.serialization.Serializable


@Serializable
data class Release(
    val chapter: Int? = 0,
    val chapterization_id: Int? = 0,
    val chapterization_time_stamp: Int? = 0,
    val created_at: String? = "",
    val creator_id: Int? = 0,
    val has_rev_link: Boolean? = false,
    val id: Int? = 0,
    val init_team: Int? = 0,
    val link_control: Int? = 0,
    val manga_id: Int? = 0,
    val support_link: String? = "",
    val team_id: Int? = 0,
    val team_name: String? = "",
    val time_stamp: Int? = 0,
    val title: String? = "",
    val views: Int? = 0,
    val volume: Int? = 0
)