package me.manga.kira.navigation.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Unit tests for [PushPayloadParser] — the pure Map→[PushDestination] boundary that every push /
 * in-app-message deep link flows through. Covers the happy paths, the strict "missing required key
 * → null" fallback (a malformed push must never land the user on a broken screen), and input
 * hygiene (trim / case / extra keys).
 */
class PushPayloadParserTest {

    // ---- manga ----

    @Test
    fun manga_valid_parsesToMangaDetail() {
        val result = PushPayloadParser.parse(
            mapOf("screen" to "manga", "api" to "azora", "url" to "https://azora/m/1"),
        )
        assertEquals(PushDestination.MangaDetail(api = "azora", url = "https://azora/m/1"), result)
    }

    @Test
    fun manga_aliases_parseToMangaDetail() {
        for (alias in listOf("manga_details", "details")) {
            val result = PushPayloadParser.parse(
                mapOf("screen" to alias, "api" to "azora", "url" to "u"),
            )
            assertIs<PushDestination.MangaDetail>(result, "alias=$alias")
        }
    }

    @Test
    fun manga_missingApi_returnsNull() {
        assertNull(PushPayloadParser.parse(mapOf("screen" to "manga", "url" to "u")))
    }

    @Test
    fun manga_missingUrl_returnsNull() {
        assertNull(PushPayloadParser.parse(mapOf("screen" to "manga", "api" to "azora")))
    }

    @Test
    fun manga_blankApi_returnsNull() {
        assertNull(PushPayloadParser.parse(mapOf("screen" to "manga", "api" to "   ", "url" to "u")))
    }

    // ---- reader ----

    @Test
    fun reader_valid_parsesAllFields() {
        val result = PushPayloadParser.parse(
            mapOf(
                "screen" to "reader",
                "api" to "azora",
                "language" to "ar",
                "url" to "https://azora/m/1",
                "chapterUrl" to "https://azora/m/1/c/5",
                "chapterNumber" to "5",
                "title" to "My Manga",
                "coverUrl" to "https://azora/cover.jpg",
                "chapterName" to "The Fight",
            ),
        )
        assertEquals(
            PushDestination.Reader(
                api = "azora",
                language = "ar",
                mangaUrl = "https://azora/m/1",
                chapterUrl = "https://azora/m/1/c/5",
                chapterNumber = "5",
                title = "My Manga",
                coverUrl = "https://azora/cover.jpg",
                chapterName = "The Fight",
            ),
            result,
        )
    }

    @Test
    fun reader_chapterName_isOptional_defaultsEmpty() {
        // Only chapterName is cosmetic; title + coverUrl are required (below).
        val result = PushPayloadParser.parse(
            mapOf(
                "screen" to "chapter", // alias
                "api" to "azora",
                "language" to "ar",
                "url" to "u",
                "chapterUrl" to "cu",
                "chapterNumber" to "5",
                "title" to "My Manga",
                "coverUrl" to "cover",
            ),
        )
        val reader = assertIs<PushDestination.Reader>(result)
        assertEquals("My Manga", reader.title)
        assertEquals("cover", reader.coverUrl)
        assertEquals("", reader.chapterName)
    }

    @Test
    fun reader_missingRequiredKey_returnsNull() {
        // title + coverUrl are required alongside the functional keys (identity + history, #4).
        val base = mapOf(
            "screen" to "reader",
            "api" to "azora",
            "language" to "ar",
            "url" to "u",
            "chapterUrl" to "cu",
            "chapterNumber" to "5",
            "title" to "t",
            "coverUrl" to "c",
        )
        assertIs<PushDestination.Reader>(PushPayloadParser.parse(base)) // full set parses
        for (missing in listOf("api", "language", "url", "chapterUrl", "chapterNumber", "title", "coverUrl")) {
            assertNull(PushPayloadParser.parse(base - missing), "missing=$missing")
        }
    }

    // ---- objects ----

    @Test
    fun updates_and_home_parse() {
        assertEquals(PushDestination.Updates, PushPayloadParser.parse(mapOf("screen" to "updates")))
        assertEquals(PushDestination.Home, PushPayloadParser.parse(mapOf("screen" to "home")))
    }

    // ---- fallback / hygiene ----

    @Test
    fun unknownScreen_returnsNull() {
        assertNull(PushPayloadParser.parse(mapOf("screen" to "settings", "api" to "a", "url" to "u")))
    }

    @Test
    fun missingScreen_returnsNull() {
        assertNull(PushPayloadParser.parse(mapOf("api" to "a", "url" to "u")))
    }

    @Test
    fun blankScreen_returnsNull() {
        assertNull(PushPayloadParser.parse(mapOf("screen" to "   ")))
    }

    @Test
    fun emptyPayload_returnsNull() {
        assertNull(PushPayloadParser.parse(emptyMap()))
    }

    @Test
    fun screen_isTrimmedAndCaseInsensitive() {
        val result = PushPayloadParser.parse(
            mapOf("screen" to "  MANGA  ", "api" to "azora", "url" to "u"),
        )
        assertIs<PushDestination.MangaDetail>(result)
    }

    @Test
    fun extraUnknownKeys_areIgnored() {
        val result = PushPayloadParser.parse(
            mapOf(
                "screen" to "manga",
                "api" to "azora",
                "url" to "u",
                "google.sent_time" to "123",
                "collapse_key" to "x",
                "random" to "y",
            ),
        )
        assertEquals(PushDestination.MangaDetail(api = "azora", url = "u"), result)
    }
}
