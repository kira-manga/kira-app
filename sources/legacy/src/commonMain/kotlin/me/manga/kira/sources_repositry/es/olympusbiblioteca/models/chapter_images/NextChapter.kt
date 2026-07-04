package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.chapter_images
import kotlinx.serialization.Serializable


@Serializable
data class NextChapter(
    val id: Int? = 0,
    val name: String? = ""
)