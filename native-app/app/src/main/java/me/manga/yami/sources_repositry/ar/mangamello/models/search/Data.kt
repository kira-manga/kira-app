package me.manga.yamiapk.sources_repositry.ar.mangamello.models.search

import kotlinx.serialization.Serializable

@Serializable
data class DataSh(
    val age_rating_id: Int? = 0,
    val average_rate: Double? = 0.0,
    val commentable: Boolean? = false,
    val created_at: String? = "",
    val genres: List<Genre?>? = listOf(),
    val id: Int? = 0,
    val img: String? = "",
    val is_completed: Int? = 0,
    val is_new: Boolean? = false,
    val last: Int? = 0,
    val rate: Double? = 0.0,
    val reportable: Boolean? = false,
    val status: Int? = 0,
    val summary: String? = "",
    val ten_rate: Double? = 0.0,
    val title: String? = "",
    val translation_status: Int? = 0,
    val type_id: Int? = 0,
    val updated_at: String? = "",
    val views: Int? = 0,
    val year: String? = ""
)