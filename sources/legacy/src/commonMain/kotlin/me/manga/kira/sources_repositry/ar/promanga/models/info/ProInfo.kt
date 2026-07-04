package me.manga.kira.sources_repositry.ar.promanga.models.info

import kotlinx.serialization.Serializable
@Serializable
data class ProInfo(
    val cdn_path: String? = "",
    val chapters: List<Chapter>? = listOf(),
    val description: String? = "",
    val id: Int? = 0,
    val is_sensitive_image: Boolean? = false,
    val likes: Int? = 0,
    val metadata: MetadataX? = MetadataX(),
    val progress: String? = "",
    val ratings_count: Int? = 0,
    val slug: String? = "",
    val status: String? = "",
    val support_popularity: Int? = 0,
    val support_total: Int? = 0,
    val supporter_count: Int? = 0,
    val thumbnail: String? = "",
    val title: String? = "",
    val type: String? = "",
    val updated_at: String? = ""
)