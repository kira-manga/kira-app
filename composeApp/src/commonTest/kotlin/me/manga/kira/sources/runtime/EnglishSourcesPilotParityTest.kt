package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.engine.GenericSourceClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Parity proofs for the English pilots, against REAL HTML captured live (2026-06-05):
 *  - Demonicscans: all five verbs generic (featured uses the `a@title` override to dodge the buggy
 *    carousel title concatenation).
 *  - Mangabuddy: all five verbs generic via the clean api.mangak.io JSON (the site was rebuilt to a
 *    Next.js SPA); only the reader pages still come from the __NEXT_DATA__ island.
 *  - Zazamanga: all five verbs generic (Madara; historical page-header expectations are scoped below).
 *
 * Also exercises advanced Ksoup selectors the configs rely on: `:not([src*=…])` (Demonicscans page ad
 * filter), `:has(li:contains(…)) li:nth-child(2)` (Demonicscans author/status), `ownText` (clean chapter
 * number where the date span is nested in the anchor), and `>` child combinator (Zazamanga genres).
 * Header/URI cases follow the shared security contract; test-only CDN trust is not live parity proof.
 */
class EnglishSourcesPilotParityTest {

    private fun config(api: String): SourceConfig {
        val doc = (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value
        return doc.sources.first { it.api == api }
    }

    private fun client(
        api: String,
        responses: Map<String, String> = EN_RESPONSES,
        captured: Map<String, String> = emptyMap(),
        source: SourceConfig = config(api),
    ) = GenericSourceClient(source, MapFakeHttp(responses), NoopHeaderStore(captured))

    private fun <T> AppResult<T>.valueOrFail(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> fail("expected success, got $error")
    }

    // --- Demonicscans -----------------------------------------------------------------------------

    @Test
    fun demonicscans_home_maps_items_and_recent_chapters() = runTest {
        val home = client("Demonicscans").home(1).valueOrFail()
        assertTrue(home.isNotEmpty())
        assertEquals("Mr Devourer, Please Act Like a Final ...", home[0].title)
        assertEquals("https://demonicscans.org/manga/Mr-Devourer%252C-Please-Act-Like-a-Final-Boss", home[0].url)
        assertEquals("https://readermc.org/images/thumbnails/Mr Devourer, Please Act Like a Final Boss.webp", home[0].coverUrl)
        assertEquals("129", home[0].recentChapters[0].number) // a.ownText "Chapter 129" -> substring-after -> "129"
        assertEquals("https://demonicscans.org/chaptered.php?manga=12094&chapter=129", home[0].recentChapters[0].url)
    }

    @Test
    fun demonicscans_search_reads_anchor_self_href() = runTest {
        val results = client("Demonicscans").search("solo", 1).valueOrFail()
        assertTrue(results.isNotEmpty())
        assertEquals("Solo Leveling", results[0].title) // div.flex.flex-col div (fallback)
        assertEquals("https://demonicscans.org/manga/Solo-Leveling", results[0].url) // the matched <a>'s own href
        assertEquals("https://readermc.org/images/thumbnails/Solo Leveling.webp", results[0].coverUrl)
    }

    @Test
    fun demonicscans_details_uses_labeled_stats_and_owntext_chapters() = runTest {
        val manga = Manga("Demonicscans", "(EN)", "x", "https://demonicscans.org/manga/Solo-Leveling", "", null, emptyList())
        val d = client("Demonicscans").details(manga).valueOrFail()
        assertEquals("Solo Leveling", d.title)
        assertEquals("9.85", d.rating) // #R-V-B .RVB selectFirst
        assertEquals("Completed", d.status) // :has(li:contains(Status)) li:nth-child(2)
        assertEquals("Chugong,H-goon,KI Soryeong", d.author) // :has(li:contains(Author)) li:nth-child(2)
        assertEquals(listOf("Shounen", "Fantasy", "Action"), d.genres)
        assertEquals(2, d.chapters.size)
        assertEquals("200.5", d.chapters[0].number) // ownText drops the nested date span
        assertEquals("https://demonicscans.org/chaptered.php?manga=6&chapter=200.5", d.chapters[0].url)
        assertEquals(LocalDate(2024, 7, 13), d.chapters[0].date)
    }

    @Test
    fun demonicscans_pages_filter_ad_image_via_not_selector() = runTest {
        val manga = Manga("Demonicscans", "(EN)", "x", "https://demonicscans.org/manga/Solo-Leveling", "", null, emptyList())
        val chapter = Chapter("1", "Chapter 1", "https://demonicscans.org/chaptered.php?manga=6&chapter=1", null, false, false)
        // Preserve the historical raw-space input: unsafe image references are not repaired.
        assertTrue(client("Demonicscans").pages(manga, chapter).first().valueOrFail().isEmpty())
        // Separate encoded-reference variant tests extraction/filtering, not a production URL rewrite.
        val encoded = DS_PAGES.replace("/Solo Leveling/", "/Solo%20Leveling/")
        val pages = client("Demonicscans", responses = EN_RESPONSES + (chapter.url to encoded))
            .pages(manga, chapter).first().valueOrFail()
        assertEquals(
            listOf(
                "https://mangareadon.org/Solo%20Leveling/1/1.jpg",
                "https://mangareadon.org/Solo%20Leveling/1/2.jpg",
            ),
            pages.map { it.url }, // the leading ad is still excluded by the :not selector
        )
        assertTrue(pages.all { it.headers.isEmpty() })
    }

    @Test
    fun demonicscans_pages_confine_captured_headers_to_declared_download_hosts() = runTest {
        val captured = mapOf("Cookie" to "cf_clearance=abc123", "User-Agent" to "Mozilla/5.0 (captured)")
        val manga = Manga("Demonicscans", "(EN)", "x", "https://demonicscans.org/manga/Solo-Leveling", "", null, emptyList())
        val chapter = Chapter("1", "Chapter 1", "https://demonicscans.org/chaptered.php?manga=6&chapter=1", null, false, false)
        // In-memory encoded test variant; the original raw-space fixture remains the negative above.
        val responses = EN_RESPONSES + (chapter.url to DS_PAGES.replace("/Solo Leveling/", "/Solo%20Leveling/"))
        val pages = client("Demonicscans", responses, captured).pages(manga, chapter).first().valueOrFail()
        assertEquals(2, pages.size)
        assertTrue(pages.all { it.headers.isEmpty() }) // The actual bundle does not authorize this CDN.
        // Explicit test-only declaration: never enlarge the real bundled source's credential authority.
        val declared = config("Demonicscans").copy(trustedHosts = listOf("mangareadon.org"))
        val authorized = client("Demonicscans", responses, captured, declared).pages(manga, chapter).first().valueOrFail()
        assertEquals(pages.map { it.url }, authorized.map { it.url })
        assertTrue(authorized.all { it.headers == captured })
    }

    @Test
    fun demonicscans_featured_uses_clean_carousel_title() = runTest {
        val feat = client("Demonicscans").featured(1).valueOrFail()
        assertTrue(feat.isNotEmpty())
        // featured.item.* overrides: title from a@title (clean), NOT the h1 text "…5090054" concatenation
        assertEquals("Reformation Of The Deadbeat Noble", feat[0].title)
        assertEquals("https://demonicscans.org/manga/Reformation-Of-The-Deadbeat-Noble", feat[0].url)
        assertEquals("https://readermc.org/images/thumbnails/The lazy prince becomes a genius.webp", feat[0].coverUrl)
    }

    // --- Mangabuddy -------------------------------------------------------------------------------

    // Mangabuddy was rebuilt to mangak.io (Next.js SPA). All verbs go through the clean api.mangak.io JSON
    // (home/featured = /titles/home latest+popular sections, search, details /titles/{id}, chapters
    // /titles/{id}/chapters) keyed by the internal id; only the reader pages use the __NEXT_DATA__ island.
    @Test
    fun mangabuddy_home_and_featured_from_api_sections() = runTest {
        val home = client("Mangabuddy").home(1).valueOrFail()
        assertEquals("Shoot Through a Different World", home[0].title)
        assertEquals("https://api.mangak.io/titles/p2qOKyBY", home[0].url) // item.url = api id url
        assertEquals("https://rx.resmk.org/covers/10.webp", home[0].coverUrl)

        val feat = client("Mangabuddy").featured(1).valueOrFail() // same endpoint, root data.popular (a bare list)
        assertEquals("Naruto", feat[0].title)
        assertEquals("https://api.mangak.io/titles/VYPXkPYz", feat[0].url)
    }

    @Test
    fun mangabuddy_search_details_chapters_pages() = runTest {
        val results = client("Mangabuddy").search("naruto", 1).valueOrFail()
        assertEquals("Naruto", results[0].title)
        assertEquals("https://api.mangak.io/titles/VYPXkPYz", results[0].url)

        val manga = Manga("Mangabuddy", "(EN)", "x", "https://api.mangak.io/titles/VYPXkPYz", "", null, emptyList())
        val d = client("Mangabuddy").details(manga).valueOrFail()
        assertEquals("Naruto", d.title)
        assertEquals("Completed", d.status)
        assertEquals(listOf("Action", "Adventure"), d.genres)
        // chapters from the separate api /titles/{id}/chapters (full list)
        assertEquals(2, d.chapters.size)
        assertEquals("748", d.chapters[0].number)
        assertEquals("Chapter 700.5", d.chapters[0].name)
        assertEquals("https://mangak.io/naruto/chapter-700-5", d.chapters[0].url) // reader page url
        assertEquals(LocalDate(2019, 8, 25), d.chapters[0].date)

        val chapter = Chapter("748", "Chapter 700.5", "https://mangak.io/naruto/chapter-700-5", null, false, false)
        val pages = client("Mangabuddy").pages(manga, chapter).first().valueOrFail().map { it.url }
        assertEquals(listOf("https://rx.qvzrd.org/a.webp", "https://rx.qvzrd.org/b.webp"), pages) // __NEXT_DATA__
    }

    @Test
    fun mangabuddy_pages_confine_referer_to_declared_download_hosts() = runTest {
        val manga = Manga("Mangabuddy", "(EN)", "x", "https://api.mangak.io/titles/VYPXkPYz", "", null, emptyList())
        val chapter = Chapter("748", "Chapter 700.5", "https://mangak.io/naruto/chapter-700-5", null, false, false)
        val pages = client("Mangabuddy").pages(manga, chapter).first().valueOrFail()
        assertTrue(pages.isNotEmpty())
        assertTrue(pages.all { it.headers.isEmpty() })
        val declared = config("Mangabuddy").copy(trustedHosts = listOf("rx.qvzrd.org")) // Test-only trust.
        val authorized = client("Mangabuddy", source = declared).pages(manga, chapter).first().valueOrFail()
        assertEquals(pages.map { it.url }, authorized.map { it.url })
        assertTrue(authorized.all { it.headers == mapOf("Referer" to "https://mangak.io/") })
    }

    // --- Zazamanga --------------------------------------------------------------------------------

    @Test
    fun zazamanga_home_maps_items_genres_and_chips() = runTest {
        val home = client("Zazamanga").home(1).valueOrFail()
        assertTrue(home.isNotEmpty())
        assertEquals("The Berserker’s Second Playthrough", home[0].title)
        assertEquals("https://www.zazamanga.com/manga/the-berserkers-second-playthrough", home[0].url)
        assertEquals("https://cdn4.zinmanga1.com/thumb/the-berserkers-second-playthrough.webp", home[0].coverUrl)
        assertEquals(listOf("Action", "Adventure", "Drama", "Fantasy", "Seinen"), home[0].genres)
        assertEquals("Chapter 30", home[0].recentChapters[0].number)
    }

    @Test
    fun zazamanga_home_drops_blacklisted_genre_items() = runTest {
        // page 2 fixture has a "Yaoi"-genre item (blacklisted) + a clean one; only the clean one survives.
        val home = client("Zazamanga").home(2).valueOrFail()
        assertEquals(listOf("Clean Series"), home.map { it.title })
    }

    @Test
    fun zazamanga_details_uses_data_backup_cover_and_child_combinator_genres() = runTest {
        val manga = Manga("Zazamanga", "(EN)", "x", "https://www.zazamanga.com/manga/the-berserkers-second-playthrough", "", null, emptyList())
        val d = client("Zazamanga").details(manga).valueOrFail()
        assertEquals("The Berserker’s Second Playthrough", d.title)
        assertEquals("https://cdn4.zinmanga1.com/thumb/the-berserkers-second-playthrough.webp", d.coverUrl) // abs:data-backup, not the /image/dflazy.jpg placeholder
        assertEquals("3.5", d.rating)
        assertEquals(listOf("Action", "Adventure", "Drama", "Fantasy", "Seinen"), d.genres)
        assertEquals(2, d.chapters.size)
        assertEquals("Chapter 30", d.chapters[0].name)
        assertEquals("https://www.zazamanga.com/manga/the-berserkers-second-playthrough/chapter-30", d.chapters[0].url)
    }

    @Test
    fun zazamanga_pages_confine_referer_to_declared_download_hosts() = runTest {
        val manga = Manga("Zazamanga", "(EN)", "x", "https://www.zazamanga.com/manga/the-berserkers-second-playthrough", "", null, emptyList())
        val chapter = Chapter("30", "Chapter 30", "https://www.zazamanga.com/manga/the-berserkers-second-playthrough/chapter-30", null, false, false)
        val pages = client("Zazamanga").pages(manga, chapter).first().valueOrFail()
        assertEquals(
            listOf(
                "https://cdn4.zinmanga1.com/the-berserkers-second-playthrough/30/0.webp",
                "https://cdn4.zinmanga1.com/the-berserkers-second-playthrough/30/1.webp",
            ),
            pages.map { it.url },
        )
        assertTrue(pages.all { it.headers.isEmpty() })
        val declared = config("Zazamanga").copy(trustedHosts = listOf("cdn4.zinmanga1.com")) // Test-only trust.
        val authorized = client("Zazamanga", source = declared).pages(manga, chapter).first().valueOrFail()
        assertEquals(pages.map { it.url }, authorized.map { it.url })
        assertTrue(authorized.all { it.headers == mapOf("Referer" to "https://www.zazamanga.com/") })
    }
}
