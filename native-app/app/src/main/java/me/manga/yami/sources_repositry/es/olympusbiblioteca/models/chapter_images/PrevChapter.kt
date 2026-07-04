package me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.chapter_images
import kotlinx.serialization.Serializable


@Serializable
data class PrevChapter(
    val id: Int? = 0,
    val name: String? = ""
)