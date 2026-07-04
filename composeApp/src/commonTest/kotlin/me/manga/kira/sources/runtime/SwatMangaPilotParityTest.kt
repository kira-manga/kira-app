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
 * Parity proof for the SwatManga pilot (appswat.com Django REST). Fixtures mirror the LIVE API shapes
 * (verified 2026-06-05); expected values are derived field-for-field from the legacy
 * `SwatMangaRepository` mapping.
 *
 * Exercises two engine features at once: the two-request "separated details" (series scalars at
 * `/series/{id}/`, chapter list at `/series/{id}/chapters/`) AND the comma-fallback template-var
 * locators — the series id lives at `serie_id` on home, `serie.id` on featured, and `id` on search,
 * all resolved by one shared `item.url` (`{id}` = `serie_id, serie.id, id`).
 */
class SwatMangaPilotParityTest {

    private val lang = "(AR)"
    private val base = "https://appswat.com/v2/api/v1"

    private fun config(): SourceConfig {
        val doc = (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value
        return doc.sources.first { it.api == "SwatManga" }
    }

    private fun client() = GenericSourceClient(config(), MapFakeHttp(SWAT_RESPONSES), NoopHeaderStore())

    private fun <T> AppResult<T>.valueOrFail(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> fail("expected success, got $error")
    }

    @Test
    fun home_uses_serie_id_for_url() = runTest {
        val home = client().home(1).valueOrFail()
        assertEquals(1, home.size)
        assertEquals("Raising Villains", home[0].title)
        assertEquals("$base/1702399", home[0].url) // id from serie_id
        assertEquals("https://appswat.com/v2/media/op.webp", home[0].coverUrl)
        assertEquals(listOf("شونين"), home[0].genres)
        assertEquals(9, home[0].rating) // "9.8" -> 9 (benign: legacy toIntOrNull?:0 collapses decimals to 0)
    }

    @Test
    fun search_uses_flat_id_for_url() = runTest {
        val results = client().search("one piece", 1).valueOrFail()
        assertEquals(1, results.size)
        assertEquals("$base/1702399", results[0].url) // serie_id/serie.id absent -> falls back to id
        assertEquals(listOf("شونين"), results[0].genres)
    }

    @Test
    fun featured_uses_nested_serie_id_not_the_chapter_id() = runTest {
        val feat = client().featured(1).valueOrFail()
        assertEquals(1, feat.size)
        assertEquals("Raising Villains", feat[0].title) // serie.title preferred over the chapter's title
        // the row's flat `id` is the CHAPTER id (555); the series id is serie.id (1702399) — fallback
        // order serie_id,serie.id,id must pick serie.id, not the chapter id.
        assertEquals("$base/1702399", feat[0].url)
        assertEquals("https://appswat.com/v2/media/op.webp", feat[0].coverUrl)
    }

    @Test
    fun details_merges_scalars_and_separate_chapters() = runTest {
        val manga = Manga("SwatManga", lang, "x", "$base/1702399", "", null, emptyList())
        val d = client().details(manga).valueOrFail()
        assertEquals("Raising Villains the Right Way", d.title)
        assertEquals("https://appswat.com/v2/media/op.webp", d.coverUrl)
        assertEquals("9.8", d.rating) // raw string (legacy rating ?: "")
        assertEquals("A story.", d.description)
        assertEquals("Ongoing", d.status) // status.name "ongoing" -> enum-map -> "Ongoing"
        assertEquals("", d.author) // legacy hardcodes author = ""
        assertEquals(listOf("شونين", "أكشن"), d.genres)

        // chapters paginate across DRF pages via lastPageLocator="next": page1 (38,37; next!=null) +
        // page2 (36; next==null) concatenated — proving the >200-chapter tail is no longer dropped.
        assertEquals(3, d.chapters.size)
        assertEquals(listOf("38", "37", "36"), d.chapters.map { it.number })
        val c0 = d.chapters[0]
        assertEquals("38", c0.number) // raw chapter string
        assertEquals("38 مقابلة الملكة", c0.name)
        assertEquals("$base/chapters/1746475/", c0.url) // int chapter id (format-number drops any .0)
        assertEquals(LocalDate(2026, 6, 4), c0.date)
        assertEquals("$base/chapters/1745500/", d.chapters[2].url) // page-2 chapter included (was dropped before)
    }

    @Test
    fun pages_map_image_in_array_order() = runTest {
        val manga = Manga("SwatManga", lang, "x", "$base/1702399", "", null, emptyList())
        val chapter = Chapter("38", "38", "$base/chapters/1746475/", null, false, false)
        val pages = client().pages(manga, chapter).first().valueOrFail().map { it.url }
        assertEquals(
            listOf("https://appswat.com/v2/media/p1.webp", "https://appswat.com/v2/media/p2.webp"),
            pages,
        )
        assertTrue(pages.all { it.isNotBlank() })
    }

    @Test
    fun pages_carry_no_download_headers() = runTest {
        // Download parity (Phase 4): SwatManga is usesCapturedHeaders=false with no static `headers`,
        // so the page image GETs carry NO headers (the appswat CDN is header-free) — matching legacy.
        val manga = Manga("SwatManga", lang, "x", "$base/1702399", "", null, emptyList())
        val chapter = Chapter("38", "38", "$base/chapters/1746475/", null, false, false)
        val pages = client().pages(manga, chapter).first().valueOrFail()
        assertTrue(pages.all { it.headers.isEmpty() })
    }
}

private const val SWAT_HOME = """
{ "count": 1, "results": [
  { "serie_id": 1702399, "title": "Raising Villains", "poster": { "medium": "https://appswat.com/v2/media/op.webp" },
    "rating": "9.8", "genres": [ { "id": 1, "name": "شونين" } ] }
] }
"""

private const val SWAT_SEARCH = """
{ "results": [
  { "id": 1702399, "title": "Raising Villains", "poster": { "medium": "https://appswat.com/v2/media/op.webp" },
    "rating": "9.8", "genres": [ { "id": 1, "name": "شونين" } ] }
] }
"""

private const val SWAT_FEATURED = """
{ "results": [
  { "id": 555, "title": "ch title", "serie": { "id": 1702399, "title": "Raising Villains",
    "poster": { "medium": "https://appswat.com/v2/media/op.webp" } } }
] }
"""

private const val SWAT_INFO = """
{ "title": "Raising Villains the Right Way", "poster": { "medium": "https://appswat.com/v2/media/op.webp" },
  "rating": "9.8", "story": "A story.", "status": { "id": 79, "name": "ongoing" },
  "genres": [ { "id": 1, "name": "شونين" }, { "id": 2, "name": "أكشن" } ] }
"""

// Page 1 — DRF count/next/results envelope. next != null → engine must fetch page 2 and concatenate.
private const val SWAT_CHAPTERS_P1 = """
{ "count": 3, "next": "https://appswat.com/v2/api/v1/series/1702399/chapters/?page=2&page_size=200", "previous": null, "results": [
  { "chapter": "38", "title": "38 مقابلة الملكة", "id": 1746475, "created_at": "2026-06-04T19:20:04.696831Z" },
  { "chapter": "37", "title": "37",               "id": 1746000, "created_at": "2026-06-01T10:00:00Z" }
] }
"""

// Page 2 — next == null → stop. This is the tail the OLD single-fetch config silently dropped (server
// caps page_size at 200, so a >200-chapter series lost everything past chapter 200 until this fix).
private const val SWAT_CHAPTERS_P2 = """
{ "count": 3, "next": null, "previous": "https://appswat.com/v2/api/v1/series/1702399/chapters/?page=1&page_size=200", "results": [
  { "chapter": "36", "title": "36", "id": 1745500, "created_at": "2026-05-28T10:00:00Z" }
] }
"""

private const val SWAT_PAGES = """
{ "images": [
  { "image": "https://appswat.com/v2/media/p1.webp", "order": 1 },
  { "image": "https://appswat.com/v2/media/p2.webp", "order": 2 }
] }
"""

private val SWAT_RESPONSES: Map<String, String> = mapOf(
    "https://appswat.com/v2/api/v1/series/releases/?page=1&page_size=20" to SWAT_HOME,
    "https://appswat.com/v2/api/v1/chapters/?limit=20&offset=1&created_last=week&order_by=-views_count" to SWAT_FEATURED,
    "https://appswat.com/v2/api/v1/series/?search=one%20piece&page=1&page_size=20" to SWAT_SEARCH,
    "https://appswat.com/v2/api/v1/series/1702399/" to SWAT_INFO,
    "https://appswat.com/v2/api/v1/series/1702399/chapters/?page=1&page_size=200" to SWAT_CHAPTERS_P1,
    "https://appswat.com/v2/api/v1/series/1702399/chapters/?page=2&page_size=200" to SWAT_CHAPTERS_P2,
    "https://appswat.com/v2/api/v1/chapters/1746475/" to SWAT_PAGES,
)
