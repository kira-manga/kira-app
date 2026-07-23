package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeChapterRef
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.sources.contracts.HeaderStore
import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.SourceBaseUrlProvider
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.engine.GenericSourceClient
import me.manga.kira.sources_repositry.ar.azora.AzoraChapterImagesResponse
import me.manga.kira.sources_repositry.ar.azora.AzoraPostDetailResponse
import me.manga.kira.sources_repositry.ar.azora.AzoraQueryResponse
import me.manga.kira.sources_repositry.ar.azora.toImageUrls
import me.manga.kira.sources_repositry.ar.azora.toMangaInfo
import me.manga.kira.sources_repositry.ar.azora.toMangaItems
import me.manga.kira.sources_repositry.ar.azora.toPopularMangaList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Stage-1 Azora pilot's parity proof: the config-driven [GenericSourceClient] is run over canned
 * Azora JSON and its `:domain` output is compared to the OUTPUT OF THE ACTUAL LEGACY PARSER
 * (`AzoraModels.toMangaItems`/`toMangaInfo`/`toImageUrls`, public in `:shared`). Equality means the
 * generic descriptor reproduces the legacy behavior field-for-field — not just "looks right".
 */
class AzoraPilotParityTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val api = "Azora"
    private val lang = "(AR)"

    private fun azoraConfig(): SourceConfig {
        val doc = (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value
        return doc.sources.first { it.api == "Azora" }
    }

    // The bundled config carries Azora's CURRENT domain (api.azorafly.com since the 2026-06 site
    // move, commit 298f4d5b); the read-only legacy parser spec still hardcodes the pre-move
    // api.azoramoon.com in its buildMangaUrl/buildChapterUrl. Derive the request base from the
    // config so fixtures + URL anchors track the live domain (a future move can't silently re-red
    // this suite), and rewrite legacy-BUILT urls onto the config host — SHAPE parity, with the
    // host owned by the config, is exactly what production ships.
    private val base = azoraConfig().baseUrl

    private fun String.onConfigHost() = replace(LEGACY_AZORA_BASE, base)

    private fun client(http: HttpExecutor = MapFakeHttp(azoraResponses(base)), headers: HeaderStore = NoopHeaderStore()) =
        GenericSourceClient(azoraConfig(), http, headers)

    private fun <T> AppResult<T>.valueOrFail(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> fail("expected success, got $error")
    }

    @Test
    fun header_free_source_never_reads_the_header_store() = runTest {
        // Azora declares usesCapturedHeaders=false (its API serves directly) → the engine must skip
        // the header-store read on every fetch (no needless I/O / log noise).
        var reads = 0
        val recording = object : HeaderStore {
            override suspend fun headersFor(api: String): Map<String, String> { reads++; return emptyMap() }
            override suspend fun save(api: String, headers: Map<String, String>) = Unit
        }
        val client = GenericSourceClient(azoraConfig(), MapFakeHttp(azoraResponses(base)), recording)
        client.home(1).valueOrFail()
        client.featured(1).valueOrFail()
        client.search("one piece", 1).valueOrFail()
        assertEquals(0, reads)
    }

    @Test
    fun invalidPersistedBaseUrl_fallsBackToSignedDescriptor() = runTest {
        val invalidRoomOverride = object : SourceBaseUrlProvider {
            override suspend fun baseUrlFor(api: String): String = "about:about"
        }
        val client = GenericSourceClient(
            config = azoraConfig(),
            http = MapFakeHttp(azoraResponses(base)),
            headerStore = NoopHeaderStore(),
            baseUrlProvider = invalidRoomOverride,
        )

        val home = client.home(1).valueOrFail()

        assertTrue(home.isNotEmpty())
        assertTrue(home.all { it.url.startsWith(base) })
    }

    @Test
    fun locked_chapters_are_hidden_from_details_and_home() = runTest {
        // Azora marks paid/locked chapters with isLocked=true (they return empty images). The config
        // declares chapter.locked → isLocked, so the engine drops them from both the details chapter
        // list and the home recent-chapter chips (generic, multi-site — any source can declare it).
        val http = MapFakeHttp(
            mapOf(
                "$base/api/query?page=1&perPage=24&orderBy=lastChapterAddedAt&orderDirection=desc" to LOCKED_QUERY_JSON,
                "$base/api/post/?postId=lock&includeChapters=true" to DETAIL_LOCKED_JSON,
            ),
        )
        val client = client(http = http)

        val home = client.home(1).valueOrFail()
        assertEquals(listOf("Chapter 1"), home[0].recentChapters.map { it.number }) // locked ch2 hidden

        val manga = Manga(api, lang, "t", "$base/api/post/?postId=lock", "", null, emptyList())
        val details = client.details(manga).valueOrFail()
        assertEquals(listOf("Chapter 1", "Chapter 3"), details.chapters.map { it.number }) // locked ch2 hidden
    }

    // Domain mappings mirrored from the data boundary (the production helpers are private/internal).
    private fun me.manga.kira.domain.model.MangaItem.toHomeFeedItem() = HomeFeedItem(
        api, language, title, url, coverUrl = imageUrl, rating = rating, genres = genres,
        recentChapters = chapters?.map { HomeChapterRef(it.number, it.url, it.isDownloaded) } ?: emptyList(),
    )

    private fun me.manga.kira.domain.model.PopularManga.toFeatured() =
        FeaturedManga(api, language, title, url, coverUrl = imageUrl)

    private fun me.manga.kira.domain.model.ChapterItem.toChapter() =
        Chapter(number, name, url, date, isDownloaded, isBookmarked)

    private fun me.manga.kira.domain.model.MangaInfo.toDetails() = MangaDetails(
        api, language, title, url, coverUrl = imageUrl, description, author, rating, status, genres,
        chapters = chapters.map { it.toChapter() },
    )

    /** Legacy-built request urls carry the pre-move host — rewrite onto the config host (shape parity). */
    private fun HomeFeedItem.onConfigHost() = copy(
        url = url.onConfigHost(),
        recentChapters = recentChapters.map { it.copy(url = it.url.onConfigHost()) },
    )

    @Test
    fun home_matches_legacy_parser_field_for_field_incl_recent_chapters() = runTest {
        val generic = client().home(1).valueOrFail()
        val legacy = json.decodeFromString<AzoraQueryResponse>(QUERY_JSON).toMangaItems(api, lang)
            .map { it.toHomeFeedItem().onConfigHost() }
        assertEquals(legacy, generic) // includes recentChapters, the rich Home data
        // spot anchors so the test fails loudly if either side silently changes
        assertEquals("$base/api/post/?postId=92", generic[0].url)
        assertEquals(8, generic[0].rating) // averageRating 8.5 -> Int 8
        assertEquals(0, generic[1].rating) // missing averageRating -> 0 (legacy default)
        // the rich Home data is preserved: post 92 carries one recent chapter
        assertEquals(listOf("Chapter 1"), generic[0].recentChapters.map { it.number })
        assertEquals("$base/api/chapter?chapterId=85027", generic[0].recentChapters[0].url)
    }

    @Test
    fun search_matches_legacy_parser() = runTest {
        val generic = client().search("one piece", 1).valueOrFail()
        val legacy = json.decodeFromString<AzoraQueryResponse>(QUERY_JSON).toMangaItems(api, lang)
            .map { it.toHomeFeedItem().onConfigHost() }
        assertEquals(legacy, generic)
    }

    @Test
    fun featured_matches_legacy_parser() = runTest {
        val generic = client().featured(1).valueOrFail()
        val legacy = json.decodeFromString<AzoraQueryResponse>(QUERY_JSON).toPopularMangaList(api, lang)
            .map { it.toFeatured() }
            .map { f -> f.copy(url = f.url.onConfigHost()) }
        assertEquals(legacy, generic)
    }

    @Test
    fun details_match_legacy_parser_incl_chapters() = runTest {
        val manga = Manga(api, lang, "One Piece", "$base/api/post/?postId=92", "", null, emptyList())
        val generic = client().details(manga).valueOrFail()
        // detail.url comes from manga.url (already on the config host); only the legacy-BUILT
        // chapter urls carry the pre-move host and need the rewrite.
        val legacy = json.decodeFromString<AzoraPostDetailResponse>(DETAIL_JSON).toMangaInfo(api, lang, manga.url).toDetails()
            .let { d -> d.copy(chapters = d.chapters.map { it.copy(url = it.url.onConfigHost()) }) }

        // Compare everything except chapter dates (legacy uses device-local TZ on full timestamps;
        // generic uses the timestamp's date part — deterministic, asserted separately below).
        assertEquals(legacy.copy(chapters = emptyList()), generic.copy(chapters = emptyList()))
        assertEquals(legacy.chapters.map { it.copy(date = null) }, generic.chapters.map { it.copy(date = null) })

        // chapter specifics: title-or-"Chapter N" fallback, formatted number, built URL, ISO date
        assertEquals("Chapter 1", generic.chapters[0].number)
        assertEquals("Romance Dawn", generic.chapters[0].name)
        assertEquals("Chapter 2", generic.chapters[1].name) // null title -> "Chapter <n>"
        assertEquals("Chapter 3", generic.chapters[2].number) // 3.0 -> format-number -> "Chapter 3"
        assertEquals("Chapter 3", generic.chapters[2].name) // null title + whole-float -> "Chapter 3" (not "3.0")
        assertEquals("$base/api/chapter?chapterId=85027", generic.chapters[0].url)
        assertEquals("Pirates & adventure", generic.description) // clean-html (tags + &amp; + whitespace)
        assertEquals("8.0", generic.rating) // integer averageRating 8 -> decimal -> "8.0" (== Double.toString())
        assertEquals(LocalDate(2024, 1, 15), generic.chapters[0].date)
        assertEquals(LocalDate(2024, 1, 22), generic.chapters[1].date)
    }

    @Test
    fun pages_match_legacy_image_urls() = runTest {
        val manga = Manga(api, lang, "One Piece", "$base/api/post/?postId=92", "", null, emptyList())
        val chapter = Chapter("Chapter 1", "Romance Dawn", "$base/api/chapter?chapterId=85027", null, false, false)
        val generic = client().pages(manga, chapter).first().valueOrFail().map { it.url }
        val legacy = json.decodeFromString<AzoraChapterImagesResponse>(PAGES_JSON).toImageUrls()
        assertEquals(legacy, generic) // both sort by `order` despite scrambled array order
        // pages are sorted by the `order` field (1,2,3), all URLs non-blank + absolute (storage CDN)
        assertEquals(
            listOf(
                "https://storage.azoramoon.com/WP-manga/data/op/1.jpg",
                "https://storage.azoramoon.com/WP-manga/data/op/2.jpg",
                "https://storage.azoramoon.com/WP-manga/data/op/3.jpg",
            ),
            generic,
        )
        assertTrue(generic.all { it.isNotBlank() })
    }

    @Test
    fun pages_carry_no_download_headers() = runTest {
        // Download parity (Phase 4): Azora is usesCapturedHeaders=false with no static `headers`, so the
        // page image GETs the download path issues must carry NO headers — exactly what the legacy
        // Azora download attached (none). Every page (not just the first) is header-free.
        val manga = Manga(api, lang, "One Piece", "$base/api/post/?postId=92", "", null, emptyList())
        val chapter = Chapter("Chapter 1", "Romance Dawn", "$base/api/chapter?chapterId=85027", null, false, false)
        val pages = client().pages(manga, chapter).first().valueOrFail()
        assertTrue(pages.all { it.headers.isEmpty() })
    }

    @Test
    fun config_validates_under_the_real_strategy_registry() {
        val doc = (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value
        val validation = me.manga.kira.sources.engine.DefaultSourceConfigValidator(
            me.manga.kira.sources.engine.DefaultStrategyRegistry(),
        ).validate(doc)
        assertTrue(validation.isValid, "pilot config must validate: ${validation.errors}")
    }
}

// Locked-chapter fixtures (isLocked=true on the paid chapter). Used to assert the engine hides them.
private const val LOCKED_QUERY_JSON = """
{ "posts": [ { "id": 7, "slug": "lock", "postTitle": "Lock Test", "featuredImage": "https://img.azoramoon.com/x.jpg",
  "chapters": [
    { "id": 1, "number": 1, "title": "", "slug": "c1", "createdAt": "2024-01-01T12:00:00Z", "isLocked": false },
    { "id": 2, "number": 2, "title": "", "slug": "c2", "createdAt": "2024-01-02T12:00:00Z", "isLocked": true }
  ] } ] }
"""

private const val DETAIL_LOCKED_JSON = """
{ "post": { "id": 7, "slug": "lock", "postTitle": "Lock Test", "featuredImage": "https://img.azoramoon.com/x.jpg",
  "chapters": [
    { "id": 1, "number": 1, "slug": "c1", "createdAt": "2024-01-01T12:00:00Z", "isLocked": false },
    { "id": 2, "number": 2, "slug": "c2", "createdAt": "2024-01-02T12:00:00Z", "isLocked": true },
    { "id": 3, "number": 3, "slug": "c3", "createdAt": "2024-01-03T12:00:00Z", "isLocked": false }
  ] } }
"""

/** The host the read-only legacy parser spec hardcodes in its URL builders (pre-move domain). */
private const val LEGACY_AZORA_BASE = "https://api.azoramoon.com"

/** Request fixtures keyed off the CONFIG's base url, so the suite tracks the live domain. */
private fun azoraResponses(base: String): Map<String, String> = mapOf(
    "$base/api/query?page=1&perPage=24&orderBy=lastChapterAddedAt&orderDirection=desc" to QUERY_JSON,
    "$base/api/query?page=1&perPage=24&orderBy=totalViews&orderDirection=desc" to QUERY_JSON,
    "$base/api/query?searchTerm=one%20piece&perPage=24" to QUERY_JSON,
    "$base/api/post/?postId=92&includeChapters=true" to DETAIL_JSON,
    "$base/api/chapter?chapterId=85027" to PAGES_JSON,
)

private const val QUERY_JSON = """
{
  "posts": [
    {
      "id": 92, "slug": "one-piece", "postTitle": "One Piece",
      "featuredImage": "https://api.azoramoon.com/covers/op.jpg",
      "seriesStatus": "ongoing", "totalViews": 1000, "author": "Oda", "averageRating": 8.5,
      "genres": [ { "id": 1, "name": "Action" }, { "id": 2, "name": "Adventure" } ],
      "chapters": [ { "id": 85027, "number": 1, "title": "Romance Dawn", "slug": "ch-1", "createdAt": "2024-01-15T12:00:00Z" } ]
    },
    {
      "id": 93, "slug": "naruto", "postTitle": "Naruto",
      "featuredImage": "https://api.azoramoon.com/covers/naruto.jpg",
      "seriesStatus": "completed", "totalViews": 900
    }
  ],
  "totalCount": 2
}
"""

private const val DETAIL_JSON = """
{
  "totalChapterCount": 2,
  "post": {
    "id": 92, "slug": "one-piece", "postTitle": "One Piece",
    "postContent": "<p>Pirates  &amp; adventure</p>",
    "featuredImage": "https://api.azoramoon.com/covers/op.jpg",
    "seriesStatus": "ongoing", "totalViews": 1000, "author": "Oda",
    "averageRating": 8, "totalRatings": 50,
    "genres": [ { "id": 1, "name": "Action" }, { "id": 2, "name": "Adventure" } ],
    "chapters": [
      { "id": 85027, "slug": "ch-1", "number": 1, "title": "Romance Dawn", "createdAt": "2024-01-15T12:00:00Z" },
      { "id": 85028, "slug": "ch-2", "number": 2, "title": null, "createdAt": "2024-01-22T12:00:00Z" },
      { "id": 85029, "slug": "ch-3", "number": 3.0, "title": null, "createdAt": "2024-02-01T12:00:00Z" }
    ]
  }
}
"""

// Real /api/chapter shape (verified live): chapter.images = [{id,url,width,height,order}], url absolute
// on the storage CDN host. Array order is deliberately SCRAMBLED here (2,1,3) so the engine must sort
// by the `order` field to match the legacy `toImageUrls()` (which does sortedBy { order }).
private const val PAGES_JSON = """
{
  "chapter": {
    "id": 85027, "slug": "ch-1", "number": 1, "title": "Romance Dawn", "mangaPostId": 92,
    "chapterStatus": "PUBLIC", "price": 0, "isLockedByCoins": false, "isPermanentlyLocked": false,
    "images": [
      { "id": 2, "url": "https://storage.azoramoon.com/WP-manga/data/op/2.jpg", "width": null, "height": null, "order": 2 },
      { "id": 1, "url": "https://storage.azoramoon.com/WP-manga/data/op/1.jpg", "width": null, "height": null, "order": 1 },
      { "id": 3, "url": "https://storage.azoramoon.com/WP-manga/data/op/3.jpg", "width": null, "height": null, "order": 3 }
    ]
  },
  "nextChapter": null, "previousChapter": null
}
"""
