package me.manga.yamiapk.sources_repositry.ar.promanga.models.info
import kotlinx.serialization.Serializable
@Serializable
data class Chapter(
    val bypass_token: String? = "",
    val cdn_path: String? = "",
    val chapter_number: String? = "",
    val coins_required: Int? = 0,
    val comments_count: String? = "",
    val content_id: Int? = 0,
    val created_at: String? = "",
    val id: Int? = 0,
    val language: String? = "",
    val likes: Int? = 0,
    val metadata: Metadata? = Metadata(),
    val published_at: String? = "",
    val reactions_count: String? = "",
    val shortlink_url: String? = "",
    val status: String? = "",
    val title: String? = "",
    val translator: String? = "",
    val uploader_id: Int? = 0,
    val uploader_nickname: String? = "",
    val uploader_username: String? = "",
    val views: Int? = 0
)