package me.manga.kira.sources_repositry.ru.desu.models.home

import kotlinx.serialization.Serializable

@Serializable
data class Response(
    val age_limit: String? = "",
    val aired_on: Int? = 0,
    val authors: String? = "",
    val chapters: Chapters? = Chapters(),
    val checked: Int? = 0,
    val cover_date: Int? = 0,
    val description: String? = "",
    val genres: String? = "",
    val id: Int? = 0,
    val image: Image? = Image(),
    val kind: String? = "",
    val licensed: Int? = 0,
    val mangadex_id: String? = "",
    val myanimelist_id: Int? = 0,
    val name: String? = "",
    val reading: String? = "",
    val released_on: Int? = 0,
    val russian: String? = "",
    val score: Double? = 0.0,
    val score_users: Int? = 0,
    val shikimori_id: Int? = 0,
    val status: String? = "",
    val synonyms: String? = "",
    val thread_id: Int? = 0,
    val trans_status: String? = "",
    val updated: Int? = 0,
    val url: String? = "",
    val views: Int? = 0
)