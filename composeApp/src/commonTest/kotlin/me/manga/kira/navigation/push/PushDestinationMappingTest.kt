package me.manga.kira.navigation.push

import me.manga.kira.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for `PushDestination.toScreen()` — the destination→route mapping, including the
 * empty-string defaults for cosmetic reader fields.
 */
class PushDestinationMappingTest {

    @Test
    fun mangaDetail_mapsToMangaDetailsRoute() {
        val screen = PushDestination.MangaDetail(api = "azora", url = "https://azora/m/1").toScreen()
        assertEquals(Screen.MangaDetails(mangaUrl = "https://azora/m/1", api = "azora"), screen)
    }

    @Test
    fun reader_mapsToChapterImagesReworkRoute() {
        val screen = PushDestination.Reader(
            api = "azora",
            language = "ar",
            mangaUrl = "https://azora/m/1",
            chapterUrl = "https://azora/m/1/c/5",
            chapterNumber = "5",
            title = "My Manga",
            coverUrl = "cover",
            chapterName = "Fight",
        ).toScreen()
        assertEquals(
            Screen.ChapterImagesRework(
                api = "azora",
                language = "ar",
                title = "My Manga",
                mangaUrl = "https://azora/m/1",
                coverUrl = "cover",
                chapterNumber = "5",
                chapterName = "Fight",
                chapterUrl = "https://azora/m/1/c/5",
            ),
            screen,
        )
    }

    @Test
    fun updates_and_home_mapToTabs() {
        assertIs<Screen.Updates>(PushDestination.Updates.toScreen())
        assertIs<Screen.Home>(PushDestination.Home.toScreen())
    }
}
