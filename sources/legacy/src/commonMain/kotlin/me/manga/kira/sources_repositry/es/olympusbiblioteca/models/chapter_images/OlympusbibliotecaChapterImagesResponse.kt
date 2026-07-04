package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.chapter_images

import kotlinx.serialization.Serializable


@Serializable
data class OlympusbibliotecaChapterImagesResponse(
    val chapter: Chapter? = Chapter(),
    val comic: Comic? = Comic(),
    val next_chapter: NextChapter? = NextChapter(),
    val prev_chapter: PrevChapter? = PrevChapter(),
    val series_id: Int? = 0
)