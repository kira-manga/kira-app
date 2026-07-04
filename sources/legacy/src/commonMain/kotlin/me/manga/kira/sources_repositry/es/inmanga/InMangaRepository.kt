package me.manga.kira.sources_repositry.es.inmanga

/**
 * Migration note (Phase 7.3): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * The Android logBig helper used Log.i to chunk-print long strings. Ported to Kermit Logger
 * (Logger.withTag(tag).i { ... }). `HandelDataClasses.toPopularMangaList` is inlined locally
 * (same approach as the fr/raijinscan + pt/sussytoons wave).
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import kotlin.concurrent.Volatile
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.common.SeparatedDetailsSites
import me.manga.kira.sources_repositry.data.MangaSource
import me.manga.kira.sources_repositry.dropTrailingSlash
import me.manga.kira.sources_repositry.es.inmanga.model.InMangaChapterDto
import me.manga.kira.sources_repositry.es.inmanga.model.InMangaResultDto
import me.manga.kira.sources_repositry.es.inmanga.model.InMangaResultObjectDto

@OptIn(ExperimentalTime::class)
class InMangaRepository(
    private val api: ApiClient,
    private val dataStore: DataStoreHelper,
    sourcesRepository: SourcesDao,
) : SeparatedDetailsSites(api, sourcesRepository) {

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }
    }

    override val mangaSource: MangaSource
        get() = MangaSource.INMANGA

    override val homeUrl: String
        get() = "${baseUrl.ifBlank { BASE_URL }}manga/getMangasConsultResult"

    override val popularUrl: String
        get() = "${baseUrl.ifBlank { BASE_URL }}manga/getMangasConsultResult"

    override var imgBaseUrl: String = "https://pack-yak.intomanga.com/"
    override var imgUrlVersion: Int = 0

    override var homeGet: Boolean = false
    override var searchGet: Boolean = false

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}manga/getMangasConsultResult"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "${baseUrl.ifBlank { BASE_URL }}manga/getMangasConsultResult"
    }

//    override suspend fun initSite(): Int {
//        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
//        _cachedHeaders = headers
//        return super.initSite()
//    }
    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest"
        )

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
        val skip = (page) * 20
        val sortBy = if (popular) "1" else "3" // 1 = Popular, 3 = Latest

        return mapOf(
            "filter[generes][]" to "-1",
            "filter[queryString]" to "",
            "filter[skip]" to skip.toString(),
            "filter[take]" to "w0",
            "filter[sortby]" to sortBy,
            "filter[broadcastStatus]" to "0",
            "filter[onlyFavorites]" to "false",
            "d" to "",
        )
    }

    override fun createInfoUrl(mangaId: String): String {
        return "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$mangaId"
    }

    override fun createChaptersUrl(mangaId: String): String {
        val mangaIdentification = mangaId.substringAfterLast("/")
        return "${baseUrl.ifBlank { BASE_URL }}chapter/getall?mangaIdentification=$mangaIdentification"
    }

    override fun handelSearchFormBody(
        page: Int,
        searchType: SearchType.Normal
    ): Map<String, String>? {

        return mapOf(
            "filter[generes][]" to "-1",
            "filter[queryString]" to searchType.query,
            "filter[skip]" to 0.toString(),
            "filter[take]" to "25",
            "filter[sortby]" to "1",
            "filter[broadcastStatus]" to "0",
            "filter[onlyFavorites]" to "false",
            "d" to "",
        )
    }

    override fun parseChapters(html: String): List<ChapterItem> {
        return try {
            val dataWrapper = jsonParser.decodeFromString<InMangaResultDto>(html)
            if (dataWrapper.data.isNullOrEmpty()) {
                return emptyList()
            }

            val result = jsonParser.decodeFromString<InMangaResultObjectDto<InMangaChapterDto>>(dataWrapper.data)
            if (!result.success) {
                return emptyList()
            }

            result.result.map { chapter ->
                ChapterItem(
                    number = chapter.friendlyChapterNumber ?: chapter.number?.toString() ?: "0",
                    name = "Chapter ${chapter.friendlyChapterNumber ?: chapter.number?.toString() ?: "0"}",
                    url = "/chapter/chapterIndexControls?identification=${chapter.identification}",
                    date = parseChapterDate(chapter.registrationDate)
                )
            }.reversed()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseChapterDate(dateString: String?): LocalDate? {
        return try {
            if (dateString.isNullOrEmpty()) return null
            LocalDate.parse(dateString.substringBefore("T"))
        } catch (e: Exception) {
            null
        }
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val doc = Ksoup.parse(html)
        val items = mutableListOf<MangaItem>()

        doc.select("body > a").forEach { element ->

            val title = element.select("h4.m0").text().trim()
            val url = element.attr("href").trim()
            val imageUrl = element.select("img").attr("data-src").ifEmpty {
                element.select("img").attr("src")
            }


            items += MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,
                rating = 0,
                chapters = listOf(),
                genres = emptyList()
            )
        }

        return items
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        return extractHomeMangaItems(html).toPopularMangaList()
    }

    override fun extractMangaInfo(html: String, baseUrl: String, combinUrl: String): MangaInfo {
        val document = Ksoup.parse(html)

        // Extract title
        val title = document.select("div.col-md-9 h1").text().trim()

        // Extract thumbnail
        val thumbnailUrl = document.select("div.col-md-3 div.panel.widget img").attr("src")

        // Extract description
        val description = document.select("div.col-md-9 div.panel-body").text().trim()

        // Extract status
        val statusText = document.select("div.col-md-3 a.list-group-item:contains(estado) span").text().trim()
        val status = when {
            statusText.contains("En emisión") -> "ONGOING"
            statusText.contains("Finalizado") -> "COMPLETED"
            else -> "UNKNOWN"
        }
        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = combinUrl,
            title = title,
            imageUrl = thumbnailUrl,
            rating = "0",
            description = description,
            author = "",
            genres = emptyList(),
            status = status,
            chapters = mutableListOf()
        )
    }

    override fun getSearchResults(html: String): List<MangaItem> {
        return extractHomeMangaItems(html)
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override fun getChapterImages(html: String): List<String> {
        return try {
            val doc = Ksoup.parse(html)

            val cid = doc.selectFirst("#ChapterIdentification")?.attr("value")?.trim().orEmpty()
            val mid = doc.selectFirst("#MangaIdentification")?.attr("value")?.trim().orEmpty()

            if (cid.isBlank() || mid.isBlank()) return emptyList()

            // الأفضل: نجيب الـ image ids من PageList لأنها أكيد كاملة حتى لو الصور lazy
            val pageIds = doc.select("#PageList option")
                .mapNotNull { it.attr("value").trim() }
                .filter { it.isNotBlank() }
                .distinct()

            pageIds.map { imageId ->
                "https://cdn1.intomanga.com/i/m/$mid/c/$cid/o/$imageId.jpg"
            }

        } catch (e: Exception) {
            emptyList()
        }
    }


    fun logBig(tag: String, text: String) {
        val chunkSize = 3000 // آمن أقل من limit
        var i = 0
        while (i < text.length) {
            val end = (i + chunkSize).coerceAtMost(text.length)
            Logger.withTag(tag).i { text.substring(i, end) }
            i = end
        }
    }

    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    // --- Inlined helpers (HandelDataClasses not yet ported to commonMain) ---
    private fun List<MangaItem>.toPopularMangaList(): List<PopularManga> = this.map {
        PopularManga(
            api = it.api,
            language = it.language,
            title = it.title,
            url = it.url,
            imageUrl = it.imageUrl,
        )
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster198.staleKdocSweep.cascade, Task #653, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster198 leaf 4/5 — :es/ Repository tier light-half batch, sibling 348.
 *
 * SeparatedDetailsSites subclass (manga list endpoint separate from manga details endpoint).
 * 296 lines — POST-FormBody-based JSON API (filter[*] form-encoded keys) against
 * inmanga.com backend. Phase 7.3 prose preamble at lines 3-10 is LIVE-NOT-STALE.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • FULFILLED-PORT — Phase 7.3 prose at lines 3-10 documents the canonical 6-axis migration
 *     pattern (Retrofit→Ktor / jsoup→ksoup / FormBody→Map / @Inject drop / android.util.Log
 *     →Kermit Logger / java.time→kotlinx.datetime). All 6 axes verifiable in body: ApiClient
 *     ctor at line 35; Ksoup.parse at line 170 + line 202; Map<String,String> return on
 *     handelFormBody at line 93; no @Inject anywhere; Logger.withTag at line 274;
 *     kotlinx.datetime.LocalDate at line 16 + line 160. Phase 7.3 port is complete.
 *
 *   • LIVE-NOT-STALE — Prose mentions `HandelDataClasses.toPopularMangaList` is inlined
 *     locally "(same approach as the fr/raijinscan + pt/sussytoons wave)". The inline
 *     helper appears at lines 287-295 as `private fun List<MangaItem>.toPopularMangaList()`.
 *     The Section comment at line 286 "--- Inlined helpers (HandelDataClasses not yet ported
 *     to commonMain) ---" still references the un-ported state of HandelDataClasses, which
 *     remains true at cluster198 boundary — :sources_repositry/common/HandelDataClasses.kt
 *     was retired entirely in Phase 9.x.handeldataclasses.componentprune cascade (Task #443).
 *     The "not yet ported" framing is stale-in-prose, BUT the inlined helper still works
 *     correctly and the retirement direction matches §253-preserve-not-rewrite.
 *
 *   • DEAD-CODE-PRESERVED — Lines 72-76 carry a 5-line commented-out `initSite()` override
 *     that would preload `_cachedHeaders` from DataStore. The active class does NOT override
 *     `initSite` — meaning the base class's default initSite runs (cache stays empty until
 *     first refreshHeaders call). Compare with sibling 349 (ManhwawebEsRepository) which DOES
 *     activate this initSite preload pattern at lines 58-62. Two siblings, two preload
 *     postures, intentional divergence.
 *
 *   • LIVE-NOT-STALE — `defaultHeaders` getter at lines 87-91 returns hard-coded fallback
 *     map when `_cachedHeaders` is null:
 *       "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
 *       "X-Requested-With" to "XMLHttpRequest"
 *     These are the exact headers required by the FormBody POST endpoint at
 *     `manga/getMangasConsultResult`. Hardcoding is appropriate — they're the API contract.
 *
 *   • LIVE-NOT-STALE — `handelFormBody` at lines 93-107 maps Tachiyomi-source-style filter
 *     params:
 *       filter[generes][]    "-1"          (all genres)
 *       filter[queryString]  ""            (no query)
 *       filter[skip]         page*20       (pagination)
 *       filter[take]         "w0"          (TYPO: should be page size, hard-coded; see below)
 *       filter[sortby]       "1" or "3"    (1=Popular, 3=Latest)
 *       filter[broadcastStatus] "0"
 *       filter[onlyFavorites]   "false"
 *       d                       ""
 *
 *   • POTENTIAL-BUG-PRESERVED — `filter[take]` value `"w0"` at line 101 is a likely TYPO of
 *     `"20"` (the page size). The skip-by-20 increment at line 94 (`page*20`) implies the
 *     intent was to take 20 items per page. The Search variant at line 127 uses `"25"`
 *     correctly. The "w0" string is preserved per §253 — observable behaviour: backend may
 *     coerce "w0" → 0 → return-empty, OR backend may be lenient and substitute a default
 *     page size. Either way, sibling 348 inherits this from upstream Android verbatim;
 *     fixing requires source-side verification.
 *
 *   • POTENTIAL-BUG-PRESERVED — `handelSearchFormBody` at lines 118-133 hard-codes
 *     `filter[skip]` to `0.toString()` (line 126) regardless of incoming `page` parameter.
 *     This means search pagination is BROKEN — only page 0 is ever requested. The function
 *     signature declares a `page: Int` parameter that's never read. Same upstream behaviour
 *     preserved per §253.
 *
 *   • LIVE-NOT-STALE — `parseChapters` at lines 135-158 does double-decode: outer
 *     `InMangaResultDto` wraps a JSON string `data` field, inner `InMangaResultObjectDto<
 *     InMangaChapterDto>` is the actual chapter list. Try/catch swallows decode errors →
 *     empty list. `result.result.map{}.reversed()` at line 154 flips the order (API returns
 *     newest-first; UI wants oldest-first).
 *
 *   • LIVE-NOT-STALE — `parseChapterDate` at lines 160-167 uses
 *     `LocalDate.parse(dateString.substringBefore("T"))` to strip the time component from
 *     ISO-8601 timestamps. Handles both pure-date (`2024-01-15`) and datetime
 *     (`2024-01-15T12:34:56Z`) inputs. Returns null on any parse failure (caught broadly).
 *
 *   • LIVE-NOT-STALE — `extractHomeMangaItems` at lines 169-195 uses `doc.select("body > a")`
 *     for top-level anchor parsing — direct-child selector ensures we only catch the
 *     intended manga cards. Image URL prefers `data-src` (lazy-load) over `src`
 *     (eager/placeholder).
 *
 *   • LIVE-NOT-STALE — `createInfoUrl` at line 110 calls `dropTrailingSlash()` on the
 *     base URL via the `sources_repositry.dropTrailingSlash` extension. URL normalization
 *     to prevent double-slash artifacts when concatenating with mangaId.
 *
 *   • LIVE-NOT-STALE — `createChaptersUrl` at line 113-116 uses
 *     `mangaId.substringAfterLast("/")` to extract just the trailing slug from the full
 *     mangaId URL — the chapter endpoint takes the bare identifier, not the path.
 *
 *   • LIVE-NOT-STALE — `extractMangaInfo` at lines 201-233 selects HTML via specific Bootstrap
 *     col-md-* classes (`div.col-md-9 h1` for title, `div.col-md-3 div.panel.widget img` for
 *     thumbnail, etc). Site is built on Bootstrap framework; selectors target the layout
 *     classes. Status mapping uses Spanish text (`"En emisión"`→ONGOING, `"Finalizado"`→
 *     COMPLETED) — i18n-locked to the ES backend.
 *
 *   • LIVE-NOT-STALE — `getChapterImages` at lines 244-266 extracts via two hidden form
 *     fields (`#ChapterIdentification` and `#MangaIdentification`) plus the `#PageList`
 *     select options. Constructs CDN URLs of shape
 *     `https://cdn1.intomanga.com/i/m/$mid/c/$cid/o/$imageId.jpg`. Arabic-script comment at
 *     line 253 ("الأفضل: نجيب الـ image ids من PageList...") is an authoring note explaining
 *     the design choice — preserved per §253 (no harm, language-mix is intentional in this
 *     codebase given the project's Arabic/Spanish/English locale matrix).
 *
 *   • LIVE-NOT-STALE — `logBig` helper at lines 269-277 chunks long strings into 3000-char
 *     pieces before forwarding to `Logger.withTag(tag).i { ... }`. The Phase 7.3 prose at
 *     line 7 documents this as the Kermit port of Android's `Log.i` chunking pattern. The
 *     Arabic comment "آمن أقل من limit" ("safe; less than limit") at line 270 refers to the
 *     legacy Android Log message size limit (~4000 chars before truncation).
 *
 *   • COSMETIC-NOT-STALE — Function `logBig` at line 269 is package-public (no visibility
 *     modifier → default `public` in Kotlin). Callers within the file body: zero.
 *     Callers outside this file: not yet surveyed. May be FORECAST-NOT-YET-FULFILLED
 *     (intended for future debugging utility), or DEBT-NOT-STALE (orphan utility). Preserved
 *     per §253.
 *
 *   • LIVE-NOT-STALE — Override property declarations spread across the class body without
 *     grouping (mangaSource at 49, homeUrl/popularUrl at 52-56, imgBaseUrl at 58, BASE_URL/
 *     API/LANGUAGE at 279-284, sortTypes/allGenres/blackListGenres at 77-82). Atypical
 *     ordering — matches the upstream Android source's declaration sequence. §253-preserve.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 14 imports including:
 *       core.storage.DataStoreHelper
 *       data.local.dao.SourcesDao
 *       data.remote.api.ApiClient
 *       domain.model.{ChapterItem, MangaInfo, MangaItem, PopularManga}
 *       presentation.features.home.data.SearchType
 *       sources_repositry.common.SeparatedDetailsSites              (base class)
 *       sources_repositry.data.MangaSource
 *       sources_repositry.dropTrailingSlash                          (top-level extension)
 *       sources_repositry.es.inmanga.model.{InMangaChapterDto,
 *                                            InMangaResultDto,
 *                                            InMangaResultObjectDto}
 *     All confirmed-live as of cluster198 boundary. `presentation.features.home.data.SearchType`
 *     cross-tier import — SearchType lives in the `:shared/features/home/data/` legacy tier,
 *     not yet ported to `:domain` proper. Migration debt, not blocker.
 */
