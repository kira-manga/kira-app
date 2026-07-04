package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
 * Parity proof for the Tapas pilot (tapas.io). All five verbs are generic (verified live 2026-06-05):
 * home + featured are JSON feeds on the `story-api.tapas.io` host; search is HTML with the URL built
 * from the numeric `data-series-id`; details merge HTML scalars with paginated JSON episodes
 * (/series/{id}/info); pages extract the lazy `data-src`. Exercises three engine features at once: the
 * `{pageOffset}` template var (Tapas API page = UI page − 1), the cover built by appending `.png` to
 * `assetProperty.bookCoverImage.path`, and `blacklistGenres` (BL/LGBTQ+/GL) filtering.
 */
class TapasPilotParityTest {

    private fun config(): SourceConfig {
        val doc = (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value
        return doc.sources.first { it.api == "Tapas" }
    }

    private fun client() = GenericSourceClient(config(), MapFakeHttp(TAPAS_RESPONSES), NoopHeaderStore())

    private fun <T> AppResult<T>.valueOrFail(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> fail("expected success, got $error")
    }

    @Test
    fun home_maps_json_items_drops_blacklisted_and_builds_cover() = runTest {
        // home(1) -> {pageOffset}=0 -> the legacy page-0 URL. The "BL"-genre item is dropped.
        val home = client().home(1).valueOrFail()
        assertEquals(listOf("Going Undercover as the Villain's Nanny"), home.map { it.title })
        val it0 = home[0]
        assertEquals("Tapas", it0.api)
        assertEquals("https://tapas.io/series/327057", it0.url) // {baseUrl}/series/{seriesId}
        assertEquals("https://story-a.tapas.io/prod/story/abc/bc/2x/xyz.png", it0.coverUrl) // path + ".png"
        assertEquals(listOf("Romance Fantasy"), it0.genres)
    }

    @Test
    fun featured_maps_ranking_feed() = runTest {
        val feat = client().featured(1).valueOrFail()
        assertEquals(1, feat.size)
        assertEquals("The Villainess Flips the Script!", feat[0].title)
        assertEquals("https://tapas.io/series/212134", feat[0].url)
    }

    @Test
    fun search_uses_html_with_numeric_id_url() = runTest {
        // search is HTML (vs JSON home/featured) → per-verb search.item.* overrides. url is built from the
        // NUMERIC data-series-id (not the slug href) so the episodes API works; title from the highlight-
        // stripped img@alt. listSelector a.thumb-wrap[data-series-id] selects ONLY the image-bearing anchor,
        // so the duplicate text-only a.link--ink (same id, no <img>) does NOT produce a blank-shell result.
        val results = client().search("solo", 1).valueOrFail()
        assertEquals(1, results.size) // the a.link--ink duplicate is excluded — no empty-title/cover shell
        assertEquals("Solo-Q", results[0].title)
        assertEquals("https://tapas.io/series/69274", results[0].url)
        assertEquals("https://us-a.tapas.io/thumb.png", results[0].coverUrl)
    }

    @Test
    fun details_html_scalars_plus_paginated_json_episodes() = runTest {
        val manga = Manga("Tapas", "(EN)", "x", "https://tapas.io/series/327057", "", null, emptyList())
        val d = client().details(manga).valueOrFail()
        assertEquals("Going Undercover as the Villain's Nanny", d.title)
        assertEquals("https://us-a.tapas.io/sa/cover.png", d.coverUrl)
        assertEquals(listOf("Romance Fantasy", "Drama", "Comedy"), d.genres)
        // episodes JSON, paginated by has_next: page1 (2) + page2 (1) = 3
        assertEquals(3, d.chapters.size)
        assertEquals("Episode 1", d.chapters[0].name)
        assertEquals("1", d.chapters[0].number) // scene
        assertEquals("https://tapas.io/episode/3842182", d.chapters[0].url)
    }

    @Test
    fun pages_extract_lazy_data_src() = runTest {
        val manga = Manga("Tapas", "(EN)", "x", "https://tapas.io/series/327057", "", null, emptyList())
        val chapter = Chapter("1", "Episode 1", "https://tapas.io/episode/3842182", null, false, false)
        val pages = client().pages(manga, chapter).first().valueOrFail().map { it.url }
        assertEquals(
            listOf("https://us-a.tapas.io/pc/a.webp", "https://us-a.tapas.io/pc/b.webp"),
            pages,
        )
    }

    @Test
    fun pages_carry_static_referer_and_user_agent_download_headers() = runTest {
        // Download parity (Phase 4): Tapas declares a static Referer + Firefox User-Agent the CDN needs.
        // Every page image GET must carry both (config headers apply with or without a captured cookie).
        val manga = Manga("Tapas", "(EN)", "x", "https://tapas.io/series/327057", "", null, emptyList())
        val chapter = Chapter("1", "Episode 1", "https://tapas.io/episode/3842182", null, false, false)
        val pages = client().pages(manga, chapter).first().valueOrFail()
        assertTrue(pages.isNotEmpty())
        assertTrue(
            pages.all {
                it.headers["Referer"] == "https://m.tapas.io" &&
                    it.headers["User-Agent"] == "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:105.0) Gecko/20100101 Firefox/105.0"
            },
        )
    }
}

private const val TAPAS_HOME = """
{ "data": { "items": [
  { "seriesId": 327057, "title": "Going Undercover as the Villain's Nanny",
    "genreList": [ { "key": "ROMANCE_FANTASY", "value": "Romance Fantasy" } ],
    "assetProperty": { "bookCoverImage": { "path": "https://story-a.tapas.io/prod/story/abc/bc/2x/xyz" } } },
  { "seriesId": 999, "title": "A BL Story",
    "genreList": [ { "key": "BL", "value": "BL" } ],
    "assetProperty": { "bookCoverImage": { "path": "https://story-a.tapas.io/prod/story/bl" } } }
] } }
"""

private const val TAPAS_FEATURED = """
{ "data": { "items": [
  { "seriesId": 212134, "title": "The Villainess Flips the Script!",
    "genreList": [ { "key": "ROMANCE_FANTASY", "value": "Romance Fantasy" } ],
    "assetProperty": { "bookCoverImage": { "path": "https://story-a.tapas.io/prod/story/vil" } } }
] } }
"""

// Each Tapas search result renders TWO anchors with the same data-series-id: the image-bearing
// `a.thumb-wrap` AND a text-only `a.link--ink` (no <img>). listSelector `a.thumb-wrap[data-series-id]`
// must select ONLY the former — the old `a[data-series-id]` matched both and emitted a blank-shell item.
private const val TAPAS_SEARCH = """
<html><body>
<a class="thumb-wrap" data-series-id="69274" data-series-type="COMMUNITY" href="/series/Solo-Q"><img src="https://us-a.tapas.io/thumb.png" alt="#_h_i_g_h_L_i_g_h_t_#Solo-Q#/_h_i_g_h_L_i_g_h_t_#"/><div class="thumb-overlay"></div></a>
<a class="link--ink" data-series-id="69274" href="/series/Solo-Q"><span class="title">Solo-Q</span></a>
</body></html>
"""

private const val TAPAS_INFO = """
<html><body>
<div class="info__right"><div class="title">Going Undercover as the Villain's Nanny</div></div>
<div class="thumb js-thumbnail"><img src="https://us-a.tapas.io/sa/cover.png"/></div>
<div class="description__body">When the author of a romantic fantasy...</div>
<a class="genre-btn">Romance Fantasy</a><a class="genre-btn">Drama</a><a class="genre-btn">Comedy</a>
</body></html>
"""

private const val TAPAS_EPS_P1 = """
{ "data": { "episodes": [
  { "id": 3842182, "title": "Episode 1", "scene": 1, "must_pay": false, "publish_date": "2026-05-29T18:00:00Z" },
  { "id": 3842183, "title": "Episode 2", "scene": 2, "must_pay": false, "publish_date": "2026-06-01T18:00:00Z" }
], "pagination": { "has_next": true } } }
"""

private const val TAPAS_EPS_P2 = """
{ "data": { "episodes": [
  { "id": 3842184, "title": "Episode 3", "scene": 3, "must_pay": false, "publish_date": "2026-06-05T18:00:00Z" }
], "pagination": { "has_next": false } } }
"""

private const val TAPAS_READER = """
<html><body>
<img class="content__img" src="data:image/gif;base64,R0lGODlh" data-src="https://us-a.tapas.io/pc/a.webp"/>
<img class="content__img" src="data:image/gif;base64,R0lGODlh" data-src="https://us-a.tapas.io/pc/b.webp"/>
</body></html>
"""

private val TAPAS_RESPONSES: Map<String, String> = mapOf(
    "https://story-api.tapas.io/cosmos/api/v1/landing/genre?category_type=COMIC&sort_option=NEWEST_EPISODE&subtab_id=17&size=20&page=0" to TAPAS_HOME,
    "https://story-api.tapas.io/cosmos/api/v1/landing/ranking?category_type=COMIC&subtab_id=17&size=20&page=0" to TAPAS_FEATURED,
    "https://tapas.io/search?pageNumber=1&q=solo&t=COMICS" to TAPAS_SEARCH,
    "https://tapas.io/series/327057/info" to TAPAS_INFO,
    "https://tapas.io/series/327057/episodes?page=1" to TAPAS_EPS_P1,
    "https://tapas.io/series/327057/episodes?page=2" to TAPAS_EPS_P2,
    "https://tapas.io/episode/3842182" to TAPAS_READER,
)
