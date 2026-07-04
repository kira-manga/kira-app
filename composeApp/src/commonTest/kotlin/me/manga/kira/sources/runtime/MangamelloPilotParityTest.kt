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
 * Parity proof for the Mangamello + Mangamello Plus pilot. Both sources are the SAME Laravel-style JSON
 * backend (`plus.mangamello.com`), so one parameterised suite covers both. Fixtures mirror the LIVE API
 * shapes (verified 2026-06-05). Generic-engine output is asserted against the legacy
 * `MangamelloRepository` mapping semantics (cited inline) — the legacy mapper is an instance method that
 * can't be cheaply constructed in a test, so the expected values are derived field-for-field from it.
 *
 * The detail+chapters split exercises the engine's two-request "separated details" feature: scalars come
 * from `{itemUrl}` (root `data`), the chapter list from `{itemUrl}/chapters` (root `data`).
 */
class MangamelloPilotParityTest {

    private val apis = listOf("Mangamello", "Mangamello Plus")
    private val lang = "(AR)"
    private val base = "https://plus.mangamello.com"

    private fun config(api: String): SourceConfig {
        val doc = (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value
        return doc.sources.first { it.api == api }
    }

    private fun client(api: String) =
        GenericSourceClient(config(api), MapFakeHttp(MELLO_RESPONSES), NoopHeaderStore())

    private fun <T> AppResult<T>.valueOrFail(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> fail("expected success, got $error")
    }

    @Test
    fun home_maps_items_legacy_semantics() = runTest {
        for (api in apis) {
            val home = client(api).home(1).valueOrFail()
            assertEquals(2, home.size, api)
            val op = home[0]
            assertEquals(api, op.api)
            assertEquals("Asura no Sata", op.title)
            // item.url = "{baseUrl}/api/v1/mangas/{id}" (legacy toMangaItems builds the same)
            assertEquals("$base/api/v1/mangas/19829", op.url)
            assertEquals("https://raw.githubusercontent.com/x/op.jpg", op.coverUrl)
            assertEquals(4, op.rating) // rate 4.43 -> toInt() = 4 (legacy data.rate?.toInt())
            assertEquals(emptyList(), op.genres) // home payload carries no genres (legacy emptyList)
            assertEquals(8, home[1].rating) // rate 8.0 -> 8
        }
    }

    @Test
    fun search_carries_genres() = runTest {
        for (api in apis) {
            val results = client(api).search("one piece", 1).valueOrFail()
            assertEquals(1, results.size, api)
            assertEquals(listOf("Action", "Drama"), results[0].genres) // search genres[*].name
            assertEquals(4, results[0].rating)
        }
    }

    @Test
    fun featured_maps_title_url_cover() = runTest {
        for (api in apis) {
            val feat = client(api).featured(1).valueOrFail()
            assertEquals(2, feat.size, api)
            assertEquals("Asura no Sata", feat[0].title)
            assertEquals("$base/api/v1/mangas/19829", feat[0].url)
            assertEquals("https://raw.githubusercontent.com/x/op.jpg", feat[0].coverUrl)
        }
    }

    @Test
    fun details_merges_scalars_and_separate_chapters() = runTest {
        for (api in apis) {
            val manga = Manga(api, lang, "x", "$base/api/v1/mangas/19829", "", null, emptyList())
            val d = client(api).details(manga).valueOrFail()
            assertEquals("Asura no Sata", d.title)
            assertEquals("https://raw.githubusercontent.com/x/op.jpg", d.coverUrl)
            assertEquals("8.86", d.rating) // ten_rate 8.86 -> decimal (== ten_rate.toString())
            assertEquals("A summary.", d.description) // summary, no html clean (legacy doesn't clean)
            assertEquals("مكتمل", d.status) // data.status=3 -> enum-map -> completed (the live API field; is_completed was removed from the payload)
            assertEquals("", d.author) // legacy author = "" always
            assertEquals(emptyList(), d.genres) // legacy detail genres = emptyList default

            // chapters come from the SECOND request (root "data"); API returns newest-first (desc),
            // which matches legacy parseChapters .sortedBy{number}.reversed()
            assertEquals(2, d.chapters.size, api)
            val c0 = d.chapters[0]
            assertEquals("33.0", c0.number) // order 33 -> decimal -> "33.0" (== Double.toString())
            assertEquals("33", c0.name) // chapter title
            assertEquals("$base/api/v1/mangas/19829/chapters/996020?relations=chapterImages", c0.url)
            assertEquals(LocalDate(2026, 6, 4), c0.date) // iso (date part)
            assertEquals("5.5", d.chapters[1].number) // fractional order preserved
        }
    }

    @Test
    fun pages_map_src_in_array_order() = runTest {
        for (api in apis) {
            val manga = Manga(api, lang, "x", "$base/api/v1/mangas/19829", "", null, emptyList())
            val chapter = Chapter(
                "33.0", "33",
                "$base/api/v1/mangas/19829/chapters/996020?relations=chapterImages",
                null, false, false,
            )
            val pages = client(api).pages(manga, chapter).first().valueOrFail().map { it.url }
            assertEquals(
                listOf(
                    "https://dilar.tube/uploads/releases/2972/a.webp",
                    "https://dilar.tube/uploads/releases/2972/b.webp",
                ),
                pages,
            )
            assertTrue(pages.all { it.isNotBlank() })
        }
    }

    @Test
    fun pages_carry_static_download_headers() = runTest {
        // Download parity (Phase 4): the API fingerprints the Dart UA + accept/auth headers, so the
        // page image GETs must carry them. usesCapturedHeaders=false → the headers are exactly the
        // static config set (no header-store read). Every page carries the same set.
        val expected = mapOf(
            "accept" to "application/json",
            "authorization" to "Bearer null",
            "content-type" to "application/json",
            "installer" to "com.google.android.packageinstaller",
            "user-agent" to "Dart/3.3 (dart:io)",
            "vsesion" to "1.1.7",
        )
        for (api in apis) {
            val manga = Manga(api, lang, "x", "$base/api/v1/mangas/19829", "", null, emptyList())
            val chapter = Chapter(
                "33.0", "33",
                "$base/api/v1/mangas/19829/chapters/996020?relations=chapterImages",
                null, false, false,
            )
            val pages = client(api).pages(manga, chapter).first().valueOrFail()
            assertTrue(pages.isNotEmpty(), api)
            assertTrue(pages.all { it.headers == expected }, api)
        }
    }
}

private const val MELLO_LIST = """
{ "data": [
  { "id": 19829, "title": "Asura no Sata", "img": "https://raw.githubusercontent.com/x/op.jpg", "rate": 4.43, "average_rate": 4.43 },
  { "id": 20000, "title": "Second",        "img": "https://raw.githubusercontent.com/x/2.jpg",  "rate": 8.0,  "average_rate": 8.0 }
] }
"""

private const val MELLO_SEARCH = """
{ "data": [
  { "id": 19829, "title": "Asura no Sata", "img": "https://raw.githubusercontent.com/x/op.jpg", "rate": 4.43, "average_rate": 4.43,
    "genres": [ { "id": 1, "name": "Action" }, { "id": 2, "name": "Drama" } ] }
] }
"""

private const val MELLO_INFO = """
{ "data": { "id": 19829, "title": "Asura no Sata", "img": "https://raw.githubusercontent.com/x/op.jpg",
  "ten_rate": 8.86, "summary": "A summary.", "status": 3 } }
"""

// API returns chapters newest-first (desc), matching legacy's sortedBy{number}.reversed().
private const val MELLO_CHAPTERS = """
{ "data": [
  { "id": 996020, "manga_id": 19829, "order": 33,  "title": "33",   "created_at": "2026-06-04T21:33:14.000000Z" },
  { "id": 996019, "manga_id": 19829, "order": 5.5, "title": "Half", "created_at": "2026-06-01T10:00:00.000000Z" }
] }
"""

private const val MELLO_PAGES = """
{ "data": { "chapterImages": [
  { "id": 1, "order": 0, "src": "https://dilar.tube/uploads/releases/2972/a.webp", "originalSrc": "" },
  { "id": 2, "order": 1, "src": "https://dilar.tube/uploads/releases/2972/b.webp", "originalSrc": "" }
] } }
"""

private val MELLO_RESPONSES: Map<String, String> = mapOf(
    "https://plus.mangamello.com/api/v1/mangas?sort_by=updated_at&page=1" to MELLO_LIST,
    "https://plus.mangamello.com/api/v1/mangas?sort_by=views&page=1" to MELLO_LIST,
    "https://plus.mangamello.com/api/v1/mangas/search?per_page=40&title=one%20piece" to MELLO_SEARCH,
    "https://plus.mangamello.com/api/v1/mangas/19829" to MELLO_INFO,
    "https://plus.mangamello.com/api/v1/mangas/19829/chapters?per_page=2000" to MELLO_CHAPTERS,
    "https://plus.mangamello.com/api/v1/mangas/19829/chapters/996020?relations=chapterImages" to MELLO_PAGES,
)
