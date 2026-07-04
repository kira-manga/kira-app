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
 * Parity proof for the DilarV2 pilot (dilar.tube JSON). Fixtures are REAL response bodies captured live
 * (2026-06-05). All five verbs are generic: home + details + chapters use the two-request
 * separated-details feature (scalars at /api/series/{id}, chapter list at /api/series/{id}/chapters);
 * search uses a POST_JSON body + a novel-dropping list filter; pages build the URL from a response-root
 * `storage_key` var + a webp/hq coalesced dir root.
 */
class DilarV2PilotParityTest {

    private val base = "https://dilar.tube"

    private fun config(): SourceConfig {
        val doc = (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value
        return doc.sources.first { it.api == "DilarV2" }
    }

    private fun client() = GenericSourceClient(config(), MapFakeHttp(DILAR_RESPONSES), NoopHeaderStore())

    private fun <T> AppResult<T>.valueOrFail(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> fail("expected success, got $error")
    }

    @Test
    fun home_maps_items_with_cover_template() = runTest {
        val home = client().home(1).valueOrFail()
        assertEquals(2, home.size)
        assertEquals("DilarV2", home[0].api)
        assertEquals("I'm Secretly Dating the Emperor, but I Don't Know That", home[0].title)
        assertEquals("$base/api/series/12203", home[0].url)
        assertEquals("$base/uploads/manga/cover/12203/1000546747.webp", home[0].coverUrl)
        assertEquals(0, home[0].rating) // "0.00" -> 0
        assertEquals("Baby Squirrel Is Good at Everything", home[1].title)
        assertEquals("$base/uploads/manga/cover/4310/G4G7tPiXAAAyPPm.jpg", home[1].coverUrl)
        assertEquals(4, home[1].rating) // "4.83" -> 4
    }

    @Test
    fun details_merges_scalars_and_separate_chapters() = runTest {
        val manga = Manga("DilarV2", "(AR)", "x", "$base/api/series/12203", "", null, emptyList())
        val d = client().details(manga).valueOrFail()
        assertEquals("I'm Secretly Dating the Emperor, but I Don't Know That", d.title)
        assertEquals("$base/uploads/manga/cover/12203/1000546747.webp", d.coverUrl)
        assertEquals("0.00", d.rating)
        assertEquals("Ongoing", d.status) // translation_status "ongoing"
        assertEquals("Rosie3", d.author) // creator.nick
        assertEquals(listOf("رومانسي", "مانهوا", "فانتازيا", "كوميدي", "خيال", "جوسي"), d.genres)

        // chapters from the separate /chapters request (newest-first; lock=false → none filtered)
        assertEquals(3, d.chapters.size)
        val c0 = d.chapters[0]
        assertEquals("Chapter 18", c0.number) // "18.00" -> format-number -> "18" -> prepend
        assertEquals("Chapter 18", c0.name) // title "" -> "Chapter {num}" fallback
        assertEquals("$base/api/chapters/140002", c0.url) // releases[0].id
        assertEquals(LocalDate(2026, 6, 5), c0.date)
        assertEquals("$base/api/chapters/139999", d.chapters[1].url)
    }

    @Test
    fun search_post_json_filters_out_novels_and_deleted() = runTest {
        val results = client().search("naruto", 1).valueOrFail()
        // POST_JSON body + list filters: the Novel (series_type.name/title) and the deleted entry are dropped
        assertEquals(listOf("Naruto"), results.map { it.title })
        assertEquals("$base/api/series/6960", results[0].url)
        assertEquals("$base/uploads/manga/cover/6960/IMG_0714.jpeg", results[0].coverUrl)
    }

    @Test
    fun pages_build_url_from_root_storage_key_and_coalesced_dir() = runTest {
        val manga = Manga("DilarV2", "(AR)", "x", "$base/api/series/12203", "", null, emptyList())
        val chapter = Chapter("Chapter 18", "Chapter 18", "$base/api/chapters/140002", null, false, false)
        val pages = client().pages(manga, chapter).first().valueOrFail().map { it.url }
        // webp_pages empty -> coalesce uses `pages` + dir token `hq`; {root:storage_key} = "3434/REL"
        assertEquals(
            listOf(
                "$base/uploads/releases/3434/REL/hq/01.JPG_part1.webp",
                "$base/uploads/releases/3434/REL/hq/01.JPG_part2.webp",
            ),
            pages,
        )
    }

    @Test
    fun pages_carry_referer_download_header() = runTest {
        // Download parity (Phase 4): DilarV2 declares a static Referer (usesCapturedHeaders=true, but
        // the static `headers` apply with or without a captured cookie). The dilar.tube CDN needs it, so
        // every page image GET must carry it. With an empty header store the result is exactly the
        // static config headers.
        val manga = Manga("DilarV2", "(AR)", "x", "$base/api/series/12203", "", null, emptyList())
        val chapter = Chapter("Chapter 18", "Chapter 18", "$base/api/chapters/140002", null, false, false)
        val pages = client().pages(manga, chapter).first().valueOrFail()
        assertTrue(pages.isNotEmpty())
        assertTrue(pages.all { it.headers == mapOf("Referer" to "https://dilar.tube") })
    }

    @Test
    fun featured_uses_popular_ranking_feed() = runTest {
        // featured() = the live /api/series/popular ranking — a genuinely distinct popular feed from
        // home (/api/series/), with the same {series:[...]} root and the same item.* fields.
        val feat = client().featured(1).valueOrFail()
        assertEquals(2, feat.size)
        assertEquals("The Villainess Is a Marionette", feat[0].title)
        assertEquals("$base/api/series/477", feat[0].url) // item.url template {baseUrl}/api/series/{id}
        assertEquals("$base/uploads/manga/cover/477/IMG_5802.jpeg", feat[0].coverUrl) // {imageBase}/manga/cover/{id}/{cover}
        assertEquals("A Way to Protect You, Sweetheart", feat[1].title)
    }
}

private const val DILAR_HOME = """
{ "series": [
  { "id": "12203", "title": "I'm Secretly Dating the Emperor, but I Don't Know That", "cover": "1000546747.webp", "rating": "0.00", "translation_status": "ongoing" },
  { "id": "4310",  "title": "Baby Squirrel Is Good at Everything", "cover": "G4G7tPiXAAAyPPm.jpg", "rating": "4.83", "translation_status": "ongoing" }
] }
"""

private const val DILAR_DETAILS = """
{ "id": "12203", "title": "I'm Secretly Dating the Emperor, but I Don't Know That",
  "summary": "وُلدتُ من جديد داخل روايةٍ رومانسيةٍ خيالية", "cover": "1000546747.webp", "rating": "0.00",
  "translation_status": "ongoing", "story_status": "ongoing",
  "creator": { "id": "133688", "nick": "Rosie3" },
  "categories": [ { "id": "1", "name": "رومانسي" }, { "name": "مانهوا" }, { "name": "فانتازيا" }, { "name": "كوميدي" }, { "name": "خيال" }, { "name": "جوسي" } ],
  "staff": [], "chapterCount": 18 }
"""

private const val DILAR_CHAPTERS = """
{ "chapters": [
  { "id": "145702", "series_id": "12203", "chapter": "18.00", "title": "", "lock": false, "created_at": "2026-06-05T02:01:47.327Z", "releases": [ { "id": "140002" } ] },
  { "id": "145700", "chapter": "17.00", "title": "", "lock": false, "created_at": "2026-06-05T01:55:41.092Z", "releases": [ { "id": "139999" } ] },
  { "id": "145699", "chapter": "16.00", "title": "", "lock": false, "created_at": "2026-06-05T01:46:55.322Z", "releases": [ { "id": "139998" } ] }
] }
"""

private const val DILAR_SEARCH = """
[ { "class": "Manga", "type_label": "x", "data": [
  { "id": "6960", "title": "Naruto",      "cover": "IMG_0714.jpeg", "series_type": { "name": "Japanese", "title": "مانجا" }, "deleted_at": null },
  { "id": "1",    "title": "Some Novel",  "cover": "n.jpg",         "series_type": { "name": "Novel", "title": "رواية" }, "deleted_at": null },
  { "id": "2",    "title": "Deleted One", "cover": "d.jpg",         "series_type": { "name": "Japanese", "title": "مانجا" }, "deleted_at": "2020-01-01" }
] } ]
"""

private const val DILAR_PAGES = """
{ "id": "140002", "storage_key": "3434/REL", "webp_pages": [],
  "pages": [ { "url": "01.JPG_part1.webp", "order": 0 }, { "url": "01.JPG_part2.webp", "order": 1 } ] }
"""

// Faithful to the live /api/series/popular ranking — same {series:[...]} root as home, distinct titles.
private const val DILAR_POPULAR = """
{ "series": [
  { "id": "477", "title": "The Villainess Is a Marionette", "cover": "IMG_5802.jpeg", "rating": "5.00", "translation_status": "ongoing" },
  { "id": "300", "title": "A Way to Protect You, Sweetheart", "cover": "way.jpg", "rating": "4.90", "translation_status": "ongoing" }
], "totalPages": 349, "currentPage": 1 }
"""

private val DILAR_RESPONSES: Map<String, String> = mapOf(
    "https://dilar.tube/api/series/?page=1" to DILAR_HOME,
    "https://dilar.tube/api/series/popular?page=1" to DILAR_POPULAR,
    "https://dilar.tube/api/series/12203" to DILAR_DETAILS,
    "https://dilar.tube/api/series/12203/chapters" to DILAR_CHAPTERS,
    "https://dilar.tube/api/search/quick_search" to DILAR_SEARCH,
    "https://dilar.tube/api/chapters/140002" to DILAR_PAGES,
)
