package me.manga.kira.data.mapper

import me.manga.kira.data.local.entity.SavedMangaEntity
import kotlin.test.Test
import kotlin.test.assertEquals

/** Guards the Room-to-Details offline projection against dropping saved source metadata. */
class SavedMangaDetailsMetadataTest {
    @Test
    fun saved_entity_restores_every_displayed_metadata_field() {
        val entity = SavedMangaEntity(
            api = "azora",
            language = "ar",
            url = "https://example/manga",
            imageUrl = "https://example/cover.jpg",
            title = "Complete manga",
            description = "Complete description",
            author = "Complete author",
            status = "Ongoing",
            rating = "4.8 / 5",
            genres = listOf("action", "fantasy"),
        )

        val details = entity.toDomainDetails(chapters = emptyList())

        assertEquals(entity.api, details.api)
        assertEquals(entity.language, details.language)
        assertEquals(entity.url, details.url)
        assertEquals(entity.imageUrl, details.coverUrl)
        assertEquals(entity.title, details.title)
        assertEquals(entity.description, details.description)
        assertEquals(entity.author, details.author)
        assertEquals(entity.status, details.status)
        assertEquals(entity.rating, details.rating)
        assertEquals(entity.genres, details.genres)
    }
}
