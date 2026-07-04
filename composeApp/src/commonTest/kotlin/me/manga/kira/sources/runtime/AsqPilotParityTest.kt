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
 * Parity proof for the 3asq pilot (3asq.org Madara HTML). All five verbs are generic and live-verified
 * (2026-06-05): home items use `.item-thumb a@title/@href` + `.item-thumb img@src`; the search
 * (admin-ajax `content-search` template) row uses the parallel `.tab-thumb a/img`, so the shared
 * `item.*` fields reconcile both via `fallbackSelectors` with the same attr. details come from a
 * separate `ajax/chapters` POST (chapter number = last numeric token of the title); pages extract
 * `img.wp-manga-chapter-img`; featured is the all-time-views ranking (`?m_orderby=views`).
 */
class AsqPilotParityTest {

    private fun config(): SourceConfig {
        val doc = (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value
        return doc.sources.first { it.api == "3asq" }
    }

    private fun client() = GenericSourceClient(config(), MapFakeHttp(ASQ_RESPONSES), NoopHeaderStore())

    private fun <T> AppResult<T>.valueOrFail(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> fail("expected success, got $error")
    }

    @Test
    fun home_uses_item_thumb_selectors() = runTest {
        val home = client().home(1).valueOrFail()
        assertEquals(1, home.size)
        assertEquals("3asq", home[0].api)
        assertEquals("Lookism", home[0].title) // .item-thumb a @title
        assertEquals("https://3asq.org/manga/lookism_fedaa-chan/", home[0].url)
        assertEquals("https://3asq.org/wp-content/uploads/2026/03/large_Picsart-175x238.webp", home[0].coverUrl)
    }

    @Test
    fun search_falls_back_to_tab_thumb_selectors() = runTest {
        val results = client().search("one", 1).valueOrFail()
        assertEquals(1, results.size)
        assertEquals("SAKAMOTO ONE SHOT", results[0].title) // .tab-thumb a @title (fallback)
        assertEquals("https://3asq.org/manga/sakamoto-one-shot/", results[0].url)
        assertEquals("https://3asq.org/wp-content/uploads/2024/10/OneShot.webp", results[0].coverUrl)
    }

    @Test
    fun details_scalars_and_separate_ajax_chapters() = runTest {
        val manga = Manga("3asq", "(AR)", "x", "https://3asq.org/manga/lookism_fedaa-chan/", "", null, emptyList())
        val d = client().details(manga).valueOrFail()
        assertEquals("Lookism", d.title)
        assertEquals("https://3asq.org/wp-content/uploads/cover.webp", d.coverUrl)
        assertEquals("4.4", d.rating)
        assertEquals("A description.", d.description) // meta[name=description]@content
        assertEquals("Unknown", d.status) // hardcoded (no labeled status element)
        assertEquals(listOf("أكشن", "دراما"), d.genres)
        // chapters from the separate POST ajax/chapters; number = LAST numeric token of the title
        assertEquals(2, d.chapters.size)
        assertEquals("610 - غابريونغ كيم [01]", d.chapters[0].name)
        assertEquals("01", d.chapters[0].number) // last token of "610 - … [01]"
        assertEquals("https://3asq.org/manga/lookism_fedaa-chan/610/", d.chapters[0].url)
        assertEquals("609", d.chapters[1].number) // last token of "609 - … [النهاية]"
    }

    @Test
    fun pages_extract_wp_manga_chapter_img() = runTest {
        val manga = Manga("3asq", "(AR)", "x", "https://3asq.org/manga/lookism_fedaa-chan/", "", null, emptyList())
        val chapter = Chapter("01", "n", "https://3asq.org/manga/lookism_fedaa-chan/610/", null, false, false)
        val pages = client().pages(manga, chapter).first().valueOrFail().map { it.url }
        assertEquals(
            listOf(
                "https://3asq.org/wp-content/uploads/WP-manga/data/m/01.jpg",
                "https://3asq.org/wp-content/uploads/WP-manga/data/m/02.jpg",
            ),
            pages,
        )
    }

    @Test
    fun pages_carry_captured_headers_for_download() = runTest {
        // Download parity (Phase 4): 3asq is usesCapturedHeaders=true with NO static headers. Seed the
        // header store and assert the captured headers flow into Page.headers — the capture→download path.
        val captured = mapOf("Cookie" to "cf_clearance=asq123", "User-Agent" to "Mozilla/5.0 (captured)")
        val client = GenericSourceClient(config(), MapFakeHttp(ASQ_RESPONSES), NoopHeaderStore(captured))
        val manga = Manga("3asq", "(AR)", "x", "https://3asq.org/manga/lookism_fedaa-chan/", "", null, emptyList())
        val chapter = Chapter("01", "n", "https://3asq.org/manga/lookism_fedaa-chan/610/", null, false, false)
        val pages = client.pages(manga, chapter).first().valueOrFail()
        assertTrue(pages.isNotEmpty())
        assertTrue(pages.all { it.headers == captured })
    }

    @Test
    fun featured_uses_views_ranking() = runTest {
        // featured() = the all-time-views ranking (?m_orderby=views) — a genuinely distinct popular
        // feed from home (?m_orderby=latest), reusing the same .page-item-detail.manga item.* selectors.
        val feat = client().featured(1).valueOrFail()
        assertEquals(2, feat.size)
        assertEquals("One Piece", feat[0].title) // .item-thumb a @title on the views-sorted grid
        assertEquals("https://3asq.org/manga/one-piece/", feat[0].url)
        assertEquals("Berserk", feat[1].title)
    }
}

// Faithful to the live structure (home .page-item-detail.manga -> .item-thumb a@title/@href + img@src).
private const val ASQ_HOME = """
<html><body>
<div class="page-item-detail manga"><div class="item-thumb c-image-hover"><a href="https://3asq.org/manga/lookism_fedaa-chan/" title="Lookism"><img class="img-responsive" src="https://3asq.org/wp-content/uploads/2026/03/large_Picsart-175x238.webp"/></a></div></div>
</body></html>
"""

// Faithful to the live admin-ajax content-search row (div.row.c-tabs-item__content -> .tab-thumb a@title/@href + img).
private const val ASQ_SEARCH = """
<div class="row c-tabs-item__content"><div class="col-4 col-md-2"><div class="tab-thumb c-image-hover"><a href="https://3asq.org/manga/sakamoto-one-shot/" title="SAKAMOTO ONE SHOT"><img class="img-responsive" src="https://3asq.org/wp-content/uploads/2024/10/OneShot.webp"/></a></div></div><div class="col-8 col-md-10"><div class="tab-summary"><div class="post-title"><h3 class="h4">SAKAMOTO ONE SHOT</h3></div></div></div></div>
"""

// Faithful to the live series detail page.
private const val ASQ_DETAILS = """
<html><head><meta name="description" content="A description."/></head><body>
<div class="post-title"><h1>Lookism</h1></div>
<div class="summary_image"><img src="https://3asq.org/wp-content/uploads/cover.webp"/></div>
<span id="averagerate">4.4</span>
<div class="summary-heading">الكاتب</div><div class="summary-content"><a>SomeAuthor</a></div>
<div class="summary-heading">التصنيفات</div><div class="summary-content"><a>أكشن</a><a>دراما</a></div>
</body></html>
"""

// Faithful to the live ajax/chapters POST response (ul.main.version-chap.no-volumn li.wp-manga-chapter).
private const val ASQ_CHAPTERS = """
<ul class="main version-chap no-volumn">
<li class="wp-manga-chapter"><a href="https://3asq.org/manga/lookism_fedaa-chan/610/">610 - غابريونغ كيم [01]</a></li>
<li class="wp-manga-chapter"><a href="https://3asq.org/manga/lookism_fedaa-chan/609/">609 - صيد العمال [النهاية]</a></li>
</ul>
"""

private const val ASQ_PAGES = """
<html><body>
<img class="wp-manga-chapter-img" src="https://3asq.org/wp-content/uploads/WP-manga/data/m/01.jpg"/>
<img class="wp-manga-chapter-img" src="https://3asq.org/wp-content/uploads/WP-manga/data/m/02.jpg"/>
</body></html>
"""

// Faithful to the live views-sorted archive (?m_orderby=views) — same .page-item-detail.manga layout as home.
private const val ASQ_FEATURED = """
<html><body>
<div class="page-item-detail manga"><div class="item-thumb c-image-hover"><a href="https://3asq.org/manga/one-piece/" title="One Piece"><img class="img-responsive" src="https://3asq.org/wp-content/uploads/one-piece.webp"/></a></div></div>
<div class="page-item-detail manga"><div class="item-thumb c-image-hover"><a href="https://3asq.org/manga/berserk/" title="Berserk"><img class="img-responsive" src="https://3asq.org/wp-content/uploads/berserk.webp"/></a></div></div>
</body></html>
"""

private val ASQ_RESPONSES: Map<String, String> = mapOf(
    "https://3asq.org/manga/page/1/?m_orderby=latest" to ASQ_HOME,
    "https://3asq.org/manga/page/1/?m_orderby=views" to ASQ_FEATURED,
    "https://3asq.org/wp-admin/admin-ajax.php" to ASQ_SEARCH,
    "https://3asq.org/manga/lookism_fedaa-chan/" to ASQ_DETAILS,
    "https://3asq.org/manga/lookism_fedaa-chan/ajax/chapters" to ASQ_CHAPTERS,
    "https://3asq.org/manga/lookism_fedaa-chan/610/" to ASQ_PAGES,
)
