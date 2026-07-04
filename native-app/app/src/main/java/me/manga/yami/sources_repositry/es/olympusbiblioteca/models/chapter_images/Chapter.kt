package me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.chapter_images
import kotlinx.serialization.Serializable


@Serializable
data class Chapter(
    val id: Int? = 0,
    val name: String? = "",
    val pages: List<String?>? = listOf(),
    val published_at: String? = "",
    val type: String? = "",
    val view_count: Int? = 0
)