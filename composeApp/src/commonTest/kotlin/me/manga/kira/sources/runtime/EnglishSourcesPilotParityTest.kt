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
 *  - Zazamanga: all five verbs generic (Madara; page images need the static Referer header).
 *
 * Also exercises advanced Ksoup selectors the configs rely on: `:not([src*=…])` (Demonicscans page ad
 * filter), `:has(li:contains(…)) li:nth-child(2)` (Demonicscans author/status), `ownText` (clean chapter
 * number where the date span is nested in the anchor), and `>` child combinator (Zazamanga genres).
 */
class EnglishSourcesPilotParityTest {

    private fun config(api: String): SourceConfig {
        val doc = (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value
        return doc.sources.first { it.api == api }
    }

    private fun client(api: String) = GenericSourceClient(config(api), MapFakeHttp(EN_RESPONSES), NoopHeaderStore())

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
        val pages = client("Demonicscans").pages(manga, chapter).first().valueOrFail().map { it.url }
        assertEquals(
            listOf(
                "https://mangareadon.org/Solo Leveling/1/1.jpg",
                "https://mangareadon.org/Solo Leveling/1/2.jpg",
            ),
            pages, // the leading /img/free_ads.jpg ad is excluded by the :not([src*='free_ads.jpg']) selector
        )
    }

    @Test
    fun demonicscans_pages_carry_captured_headers_for_download() = runTest {
        // Download parity (Phase 4): Demonicscans is usesCapturedHeaders=true with NO static headers, so
        // its page image GETs carry whatever the (Cloudflare) WebView captured. Seed the header store and
        // assert those captured headers flow into Page.headers — the capture→download path. (Cold-start,
        // i.e. empty store, yields no headers — same as the legacy download, not a regression.)
        val captured = mapOf("Cookie" to "cf_clearance=abc123", "User-Agent" to "Mozilla/5.0 (captured)")
        val client = GenericSourceClient(config("Demonicscans"), MapFakeHttp(EN_RESPONSES), NoopHeaderStore(captured))
        val manga = Manga("Demonicscans", "(EN)", "x", "https://demonicscans.org/manga/Solo-Leveling", "", null, emptyList())
        val chapter = Chapter("1", "Chapter 1", "https://demonicscans.org/chaptered.php?manga=6&chapter=1", null, false, false)
        val pages = client.pages(manga, chapter).first().valueOrFail()
        assertTrue(pages.isNotEmpty())
        assertTrue(pages.all { it.headers == captured })
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
    fun mangabuddy_pages_carry_referer_download_header() = runTest {
        // Download parity (Phase 4): Mangabuddy declares a static Referer (https://mangak.io/) — the
        // image CDN needs it. Every page image GET the download path issues must carry it (config
        // headers apply with or without a captured cookie).
        val manga = Manga("Mangabuddy", "(EN)", "x", "https://api.mangak.io/titles/VYPXkPYz", "", null, emptyList())
        val chapter = Chapter("748", "Chapter 700.5", "https://mangak.io/naruto/chapter-700-5", null, false, false)
        val pages = client("Mangabuddy").pages(manga, chapter).first().valueOrFail()
        assertTrue(pages.isNotEmpty())
        assertTrue(pages.all { it.headers["Referer"] == "https://mangak.io/" })
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
    fun zazamanga_pages_carry_referer_header() = runTest {
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
        // the static Referer is load-bearing — the CDN 403s without it
        assertEquals("https://www.zazamanga.com/", pages[0].headers["Referer"])
    }
}

// ---- Demonicscans real captured HTML ----
private const val DS_HOME = """
<html><body><div id="updates-container">
<div class="updates-element border-box"><div class="flex flex-row">
  <div class="thumb"><a href="/manga/Mr-Devourer%252C-Please-Act-Like-a-Final-Boss" title="Mr Devourer, Please Act Like a Final Boss"><img src="https://readermc.org/images/thumbnails/Mr Devourer, Please Act Like a Final Boss.webp"/></a></div>
  <div class="updates-element-info ml flex flex-col justify-space-between full-width">
    <h2 style="font-size:16px;"><a href="/manga/Mr-Devourer%252C-Please-Act-Like-a-Final-Boss" title="Mr Devourer, Please Act Like a Final Boss">Mr Devourer, Please Act Like a Final ...</a></h2>
    <div>
      <div class="flex flex-row chap-date justify-space-between"><div><a class="chplinks" href="chaptered.php?manga=12094&chapter=129">Chapter 129</a></div><div><a class="chplinks" href="chaptered.php?manga=12094&chapter=129">2026-06-05</a></div></div>
      <div class="flex flex-row chap-date justify-space-between"><div><a class="chplinks" href="chaptered.php?manga=12094&chapter=128">Chapter 128</a></div><div><a class="chplinks" href="chaptered.php?manga=12094&chapter=128">2026-05-29</a></div></div>
    </div></div></div></div>
</div></body></html>
"""

private const val DS_SEARCH = """
<html><body>
<a href="/manga/Solo-Leveling"><li class="flex flex-row">
  <img src="https://readermc.org/images/thumbnails/Solo Leveling.webp" class="search-thumb"/>
  <div class="flex flex-col seach-right justify-space-between"><div>Solo Leveling</div><div style="font-size:12px;">8942671</div></div>
</li></a>
</body></html>
"""

private const val DS_DETAILS = """
<html><body>
<div id="manga-page"><img src="https://readermc.org/images/thumbnails/Solo Leveling.webp"/></div>
<div id="manga-info-rightColumn"><h1>Solo Leveling</h1><div class="white-font">10 years ago, after the Gate opened...</div></div>
<div id="R-V-B"><span class="RVB">9.85</span><span class="RVB">8.49m</span><span class="RVB">4.06k</span></div>
<div id="manga-info-stats">
  <div class="flex flex-row"><li style="width:150px;">Author</li><li>Chugong,H-goon,KI Soryeong</li></div>
  <div class="flex flex-row"><li>Rating</li><li>100%</li></div>
  <div class="flex flex-row"><li>Status</li><li>Completed</li></div>
  <div class="flex flex-row"><li>Last Update</li><li>2024-07-13</li></div>
</div>
<ul class="genres-list"><li>Shounen</li><li>Fantasy</li><li>Action</li></ul>
<ul id="chapters-list">
  <li><a class="chplinks" href="/chaptered.php?manga=6&chapter=200.5" title="Solo Leveling 200.5">Chapter 200.5<span style="float:right;text-align: right;">2024-07-13</span></a></li>
  <li><a class="chplinks" href="/chaptered.php?manga=6&chapter=200" title="Solo Leveling 200">Chapter 200<span style="float:right;text-align: right;">2024-07-06</span></a></li>
</ul>
</body></html>
"""

private const val DS_FEATURED = """
<html><body><div id="carousel">
<div class="owl-element"><a href="/manga/Reformation-Of-The-Deadbeat-Noble" title="Reformation Of The Deadbeat Noble"><img src="https://readermc.org/images/thumbnails/The lazy prince becomes a genius.webp" alt="Reformation Of The Deadbeat Noble"/><div class="shadowimg"></div><h1>Reformation Of The Deadbeat Noble<br/><div>5090054</div></h1></a></div>
</div></body></html>
"""

private const val DS_PAGES = """
<html><body>
<img class="imgholder" src="/img/free_ads.jpg"/>
<img class="imgholder" src="https://mangareadon.org/Solo Leveling/1/1.jpg"/>
<img class="imgholder" src="https://mangareadon.org/Solo Leveling/1/2.jpg"/>
</body></html>
"""

// ---- Mangabuddy / mangak.io real API shapes ----
// /titles/home serves both home (data.latest.items — an {items,pagination} object) and featured
// (data.popular — a BARE LIST, matching the live API; it is NOT wrapped in {items:[...]}).
private const val MB_HOME = """
{ "data": {
  "latest":  { "items": [ { "id": "p2qOKyBY", "name": "Shoot Through a Different World", "cover": "https://rx.resmk.org/covers/10.webp", "genres": [ { "name": "Action" } ] } ] },
  "popular": [ { "id": "VYPXkPYz", "name": "Naruto", "cover": "https://rx.resmk.org/covers/nar.webp", "genres": [ { "name": "Action" } ] } ]
} }
"""

private const val MB_SEARCH = """
{ "data": { "items": [ { "id": "VYPXkPYz", "name": "Naruto", "cover": "https://rx.resmk.org/covers/nar.webp", "genres": [ { "name": "Action" } ] } ] } }
"""

private const val MB_DETAILS = """
{ "data": { "title": { "name": "Naruto", "cover": "https://rx.resmk.org/covers/nar.webp", "status": "Completed",
  "summary": "Ninja story.", "genres": [ { "name": "Action" }, { "name": "Adventure" } ] } } }
"""

private const val MB_CHAPTERS = """
{ "data": { "chapters": [
  { "id": "WYXlbzbY", "url": "/naruto/chapter-700-5", "name": "Chapter 700.5", "chapter_number": 748, "updated_at": "2019-08-25T02:08:00.000Z" },
  { "id": "abc", "url": "/naruto/chapter-700", "name": "Chapter 700", "chapter_number": 747, "updated_at": "2019-08-20T00:00:00.000Z" }
] } }
"""

private const val MB_READER = """
<html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"initialChapter":{"images":["https://rx.qvzrd.org/a.webp","https://rx.qvzrd.org/b.webp"]}}}}</script></body></html>
"""

// ---- Zazamanga real captured HTML (Madara) ----
private const val ZZ_HOME = """
<html><body>
<div class="page-item-detail manga">
  <div class="item-thumb hover-details" data-post-id="75718"><a href="https://www.zazamanga.com/manga/the-berserkers-second-playthrough" title="Read The Berserker’s Second Playthrough"><img alt="The Berserker’s Second Playthrough" class="img-responsive" src="https://cdn4.zinmanga1.com/thumb/the-berserkers-second-playthrough.webp"/></a></div>
  <div class="item-summary">
    <div><div class="post-title font-title"><h3 class="h5"><a href="https://www.zazamanga.com/manga/the-berserkers-second-playthrough">The Berserker’s Second Playthrough</a></h3></div></div>
    <div class="tags"><span><a href="/manga-genre/action" rel="tag">Action</a></span><span><a href="/manga-genre/adventure" rel="tag">Adventure</a></span><span><a href="/manga-genre/drama" rel="tag">Drama</a></span><span><a href="/manga-genre/fantasy" rel="tag">Fantasy</a></span><span><a href="/manga-genre/seinen" rel="tag">Seinen</a></span></div>
    <div class="list-chapter"><div class="chapter"><div class="chapter-detail"><a class="btn-link" href="/manga/the-berserkers-second-playthrough/chapter-30"> Chapter 30 </a></div><span class="post-on">50 minutes ago</span></div></div>
  </div>
</div>
</body></html>
"""

private const val ZZ_DETAILS = """
<html><body>
<div class="summary_image"><img class="img-comic effect-fade" data-backup="https://cdn4.zinmanga1.com/thumb/the-berserkers-second-playthrough.webp" src="/image/dflazy.jpg"/></div>
<h1 class="post-title font-title">The Berserker’s Second Playthrough</h1>
<div class="post-content"><div class="tags"><a href="/manga-genre/action" rel="tag">Action</a><a href="/manga-genre/adventure" rel="tag">Adventure</a><a href="/manga-genre/drama" rel="tag">Drama</a><a href="/manga-genre/fantasy" rel="tag">Fantasy</a><a href="/manga-genre/seinen" rel="tag">Seinen</a></div></div>
<span id="averagerate">3.5</span>
<div class="description-summary"><div class="summary__content">About The Berserker’s Second Playthrough story.</div></div>
<ul>
<li class="wp-manga-chapter"><a href="https://www.zazamanga.com/manga/the-berserkers-second-playthrough/chapter-30">Chapter 30</a><span class="chapter-release-date"><i>51 minutes ago</i></span></li>
<li class="wp-manga-chapter"><a href="https://www.zazamanga.com/manga/the-berserkers-second-playthrough/chapter-29">Chapter 29</a><span class="chapter-release-date"><i>1 week ago</i></span></li>
</ul>
</body></html>
"""

private const val ZZ_PAGES = """
<html><body>
<img id="manga-image-0" class="wp-manga-chapter-img effect-fade" src="https://cdn4.zinmanga1.com/the-berserkers-second-playthrough/30/0.webp"/>
<img id="manga-image-1" class="wp-manga-chapter-img effect-fade" src="https://cdn4.zinmanga1.com/the-berserkers-second-playthrough/30/1.webp"/>
</body></html>
"""

// Zazamanga page-2 home with a blacklisted ("Yaoi") item + a clean item — verifies blacklistGenres filtering.
private const val ZZ_HOME_BL = """
<html><body>
<div class="page-item-detail manga">
  <div class="item-thumb"><a href="https://www.zazamanga.com/manga/blacklisted-one" title="x"><img src="https://cdn4.zinmanga1.com/thumb/blacklisted.webp"/></a></div>
  <div class="item-summary"><div><div class="post-title font-title"><h3 class="h5"><a href="https://www.zazamanga.com/manga/blacklisted-one">Blacklisted One</a></h3></div></div>
    <div class="tags"><span><a href="/manga-genre/romance" rel="tag">Romance</a></span><span><a href="/manga-genre/yaoi" rel="tag">Yaoi</a></span></div></div>
</div>
<div class="page-item-detail manga">
  <div class="item-thumb"><a href="https://www.zazamanga.com/manga/clean-series" title="x"><img src="https://cdn4.zinmanga1.com/thumb/clean.webp"/></a></div>
  <div class="item-summary"><div><div class="post-title font-title"><h3 class="h5"><a href="https://www.zazamanga.com/manga/clean-series">Clean Series</a></h3></div></div>
    <div class="tags"><span><a href="/manga-genre/action" rel="tag">Action</a></span></div></div>
</div>
</body></html>
"""

private val EN_RESPONSES: Map<String, String> = mapOf(
    "https://www.zazamanga.com/manga?orderby=latest&page=2" to ZZ_HOME_BL,
    "https://demonicscans.org/lastupdates.php?list=1" to DS_HOME,
    "https://demonicscans.org/search.php?manga=solo" to DS_SEARCH,
    "https://demonicscans.org/manga/Solo-Leveling" to DS_DETAILS,
    "https://demonicscans.org/" to DS_FEATURED,
    "https://demonicscans.org/chaptered.php?manga=6&chapter=1" to DS_PAGES,
    "https://api.mangak.io/titles/home" to MB_HOME,
    "https://api.mangak.io/titles/search?q=naruto" to MB_SEARCH,
    "https://api.mangak.io/titles/VYPXkPYz" to MB_DETAILS,
    "https://api.mangak.io/titles/VYPXkPYz/chapters" to MB_CHAPTERS,
    "https://mangak.io/naruto/chapter-700-5" to MB_READER,
    "https://www.zazamanga.com/manga?orderby=latest&page=1" to ZZ_HOME,
    "https://www.zazamanga.com/manga/the-berserkers-second-playthrough" to ZZ_DETAILS,
    "https://www.zazamanga.com/manga/the-berserkers-second-playthrough/chapter-30" to ZZ_PAGES,
)
