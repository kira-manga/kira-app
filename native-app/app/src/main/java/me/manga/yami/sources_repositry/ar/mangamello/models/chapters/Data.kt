package me.manga.yamiapk.sources_repositry.ar.mangamello.models.chapters

import kotlinx.serialization.Serializable

@Serializable
data class DataCh(
    val created_at: String? = "",
    val id: Int? = 0,
    val isCommentable: Int? = 0,
    val is_new: Boolean? = false,
    val manga_id: Int? = 0,
    val order: Double? = 0.0,
    val title: String? = "",
    val updated_at: String? = "",
    val views: Int? = 0
)