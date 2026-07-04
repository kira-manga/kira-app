package me.manga.kira.sources_repositry.ar.dilar

/**
 * Migration note (Phase 7.1): Retrofit -> Ktor ApiClient, jsoup -> ksoup,
 * okhttp3.FormBody / RequestBody -> Map<String, String>? (for form bodies) or raw JSON String via
 * api.postJson (for JSON bodies), @Inject dropped, android.util.Log -> Kermit Logger,
 * kotlin.jvm.Volatile -> kotlin.concurrent.Volatile, java.time -> kotlinx.datetime
 * (Instant.parse + toLocalDateTime(tz).date).
 *
 * Two notable behaviour notes:
 *
 *  1. The upstream `normalSearchFormBody` returned an okhttp3 `RequestBody` carrying a raw JSON
 *     body (NOT form-data) for `/api/mangas/search`. The KMP base `normalSearchFormBody` returns
 *     `Map<String, String>?` for form-encoded bodies, so we leave that `null` and override
 *     `normalSearch` to call `api.postJson(url, body, headers)` directly, preserving upstream
 *     behaviour.
 *
 *  2. The upstream `getChapterImages` had an Android-version branch:
 *
 *         val componentHtml = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
 *             html.select(".js-react-on-rails-component").first.data()
 *         } else {
 *             html.select(".js-react-on-rails-component").html()
 *         }
 *
 *     Both branches read the same JSON payload embedded in the same DOM node; the Android
 *     `Build.VERSION_CODES.VANILLA_ICE_CREAM` branch was a workaround for a jsoup CDATA edge
 *     case on Android 15. In commonMain we only have ksoup, which exposes `.data()` /
 *     `.html()` uniformly across platforms, so the branch collapses to a single call:
 *     `select(".js-react-on-rails-component").firstOrNull()?.data().orEmpty()`. If `data()`
 *     returns blank, fall back to `.html()` to mirror the pre-Android-15 path. This matches
 *     upstream's intent (read the JSON sitting inside the `<script>`-like component element)
 *     while staying KMP-portable. See TODO(Phase 8 - dom) if this turns out to need
 *     platform-specific handling at runtime.
 *
 * Audit-trail postscript (Phase 9.x.cluster194.staleKdocSweep.cascade, Task #649, 2026-05-29).
 * Position-in-cluster: leaf 2/4 (591 lines).
 *
 * Classification under cluster57+ taxonomy:
 *
 * a) `Retrofit -> Ktor ApiClient` (line 4) — LIVE-NOT-STALE. Verified: imports
 *    `me.manga.kira.data.remote.api.ApiClient` (line 52); `SeparatedDetailsSitesv2` parent
 *    receives `api` (line 75); `api.postJson(url, body, headers)` in normalSearch override
 *    (line 236).
 *
 * b) `jsoup -> ksoup` (line 4) — LIVE-NOT-STALE. Verified: `com.fleeksoft.ksoup.Ksoup` import
 *    (line 38); `Ksoup.parse(...)` in getChapterImages (line 384); no `org.jsoup` imports.
 *
 * c) `okhttp3.FormBody / RequestBody -> Map<String,String>? or raw JSON String via api.postJson`
 *    (lines 5-6) — LIVE-NOT-STALE. Verified: 4 form-body methods (handelFormBody line 150,
 *    normalSearchFormBody line 186, genresSearchFormBody line 240, sortFormBody line 265) all
 *    return `Map<String, String>?`; `normalSearchFormBody` returns null deliberately because
 *    the search endpoint expects raw JSON, dispatched via `api.postJson` in the
 *    `normalSearch` override (line 236).
 *
 * d) `@Inject dropped` (line 6) — LIVE-NOT-STALE. Verified: constructor (lines 71-75) is
 *    plain `private val dataStore`, plain `api`, plain `sourcesRepository`; no annotation.
 *
 * e) `android.util.Log -> Kermit Logger` (line 6) — LIVE-NOT-STALE. Verified:
 *    `co.touchlab.kermit.Logger` import (line 37); `Logger.withTag(...).i { html }` in
 *    parseChapters (line 301) with verbatim-upstream keyboard-mashed tag
 *    "sdghsfdlgdsfjlgsfdlkgjdsflkgkdslfgdsfgsd" (POTENTIAL-BUG-PRESERVED debug-tag noise).
 *
 * f) `kotlin.jvm.Volatile -> kotlin.concurrent.Volatile` (line 7) — LIVE-NOT-STALE. Verified:
 *    `kotlin.concurrent.Volatile` import (line 39); `@Volatile` annotation on `_cachedHeaders`
 *    backing field (line 113).
 *
 * g) `java.time -> kotlinx.datetime (Instant.parse + toLocalDateTime(tz).date)` (lines 7-8) —
 *    LIVE-NOT-STALE. Verified: `kotlin.time.Instant` (line 42) with `@OptIn(ExperimentalTime)`
 *    (line 70); `Instant.parse(...).toLocalDateTime(zone).date` for ISO-8601 `created_at`
 *    branch in toChapterItem (line 505); `Instant.fromEpochSeconds(it.toLong()).toLocalDateTime
 *    (zone).date` for `time_stamp` epoch-seconds branch (lines 513-515); secondary
 *    `Instant.fromEpochSeconds(...)` for `year` extraction in toMangaInfo (lines 455-458).
 *
 * h) Behaviour note 1 — JSON-body search endpoint (lines 10-15) — LIVE-NOT-STALE.
 *    Verified: `normalSearchFormBody` returns null (line 186) with KDoc explaining the rationale
 *    (lines 181-185); custom `normalSearch` override (lines 232-238) builds JSON via
 *    `buildSearchJsonBody` (lines 191-225) and calls `api.postJson(url, body, headers)`.
 *
 * i) Behaviour note 2 — Android-version-branch collapse (lines 17-34) — LIVE-NOT-STALE.
 *    Verified: getChapterImages at lines 391-393 uses
 *    `componentEl?.data()?.takeIf { it.isNotBlank() } ?: componentEl?.html().orEmpty()` — the
 *    documented data-then-html fallback. TODO(Phase 8 - dom) remains open.
 *
 * j) POTENTIAL-BUG-PRESERVED: `handelFormBody` KDoc (lines 150-157) explicitly documents
 *    multi-value form-key truncation (e.g. "manga_types[include][]" can only carry the LAST
 *    write in a Map<String, String>); upstream's okhttp3.FormBody.Builder appended both "1"
 *    and "2" and dilar's backend may rely on multi-value semantics. TODO(Phase 8 -
 *    form-multi-values) remains open. Preserved verbatim per audit-trail convention.
 *
 * k) POTENTIAL-BUG-PRESERVED: `BrowseManga.toMangaItems` (lines 529-554) hardcodes
 *    `rating = 0` (line 546) and `chapters = null` (line 548) and `genres` derived from
 *    `this.categories` — the rating-always-zero is upstream behaviour for the search-result
 *    pathway and contrasts with `DilarResponse.toMangaItems` (lines 557-589) which preserves
 *    `manga.rating?.toIntOrNull()` (line 579). Preserved verbatim.
 *
 * l) POTENTIAL-BUG-PRESERVED: extractMangaInfo catch (lines 341-354) returns a 10-field
 *    blank `MangaInfo` on any decode-failure (silent failure, no caller-visible error). Matches
 *    sibling 323's error-swallowing partial-success pattern (cluster193). Same caller-blind
 *    failure mode.
 *
 * m) FORECAST-NOT-YET-FULFILLED: TODO(Phase 8 - dom) at lines 33-34 — open.
 *
 * n) COSMETIC-NOT-STALE: empty `sortTypes` + `allGenres` + `blackListGenres` scaffolds (lines
 *    133-138) preserve override surface for future content-policy enablement. Matches sibling
 *    325 (cluster193) empty-scaffold pattern.
 *
 * o) COSMETIC-NOT-STALE: 9 dilar-specific model imports (lines 58-66) — `DilarResponse`,
 *    `ChaptersResponse`/`Release`, `ReleaseInfo`/`Root`, `InfoResponse`, `BrowseManga`/
 *    `EncryptedResponse`/`SearchMangaDto` — verbatim upstream model surface for the encrypted-
 *    JSON-API endpoints. `decrypt(data.data)` call in getSearchResults (line 366) references
 *    an out-of-file helper (DilarParser likely owns the AES decryption — sibling-file surface).
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import me.manga.kira.core.states.State
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.ar.dilar.models.DilarResponse
import me.manga.kira.sources_repositry.ar.dilar.models.chapter.ChaptersResponse
import me.manga.kira.sources_repositry.ar.dilar.models.chapter.Release
import me.manga.kira.sources_repositry.ar.dilar.models.images.ReleaseInfo
import me.manga.kira.sources_repositry.ar.dilar.models.images.Root
import me.manga.kira.sources_repositry.ar.dilar.models.info.InfoResponse
import me.manga.kira.sources_repositry.ar.dilar.models.search.BrowseManga
import me.manga.kira.sources_repositry.ar.dilar.models.search.EncryptedResponse
import me.manga.kira.sources_repositry.ar.dilar.models.search.SearchMangaDto
import me.manga.kira.sources_repositry.common.SeparatedDetailsSitesv2
import me.manga.kira.sources_repositry.data.MangaSource

@OptIn(ExperimentalTime::class)
class DilarRepository(
    private val dataStore: DataStoreHelper,
    api: ApiClient,
    sourcesRepository: SourcesDao,
) : SeparatedDetailsSitesv2(api, sourcesRepository) {

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }
    }

    override val mangaSource: MangaSource
        get() = MangaSource.DILAR
    override val homeUrl: String by lazy { "${baseUrl.ifBlank { mangaSource.BASEURL }}api/releases?page=1" }


    override val popularUrl: String
        get() = ""
    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0
    override val BASE_URL: String
        get() = MangaSource.DILAR.BASEURL
    override val API: String
        get() = MangaSource.DILAR.API
    override val LANGUAGE: String
        get() = MangaSource.DILAR.LANGUAGE.Language


    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    override var useGetForNormalSearch: Boolean = false
    override var useGetForGenresSearch: Boolean = false
    override var useGetForSortSearch: Boolean = false

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            // Merge cached headers with Referer; if cached also contains "Referer", this ensures
            // our value wins (last write wins in Map merge order):
            return base + refererHeader
        }

    private val refererHeader get() = "Referer" to baseUrl


    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        val merged = newHeaders + refererHeader
        _cachedHeaders = merged
        dataStore.saveHeadersForApi(API, merged)
    }

    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()


    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}api/releases?page=$page"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "${baseUrl.ifBlank { BASE_URL }}api/mangas/search"
    }


    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
        // Form-data shape upstream built with okhttp3.FormBody.Builder, ported to a Map. Keys
        // that repeat in form-data (e.g. "manga_types[include][]") are not directly expressible
        // as Map<String, String> entries; the last one wins. Upstream's first builder appended
        // both "1" and "2" for that key; we preserve "2" (the last write) here since the dilar
        // backend uses these primarily as filters and Map<String,String> is the KMP-portable
        // shape the base class supports. See TODO(Phase 8 - form-multi-values) if multi-valued
        // form keys turn out to be load-bearing for the search endpoint.
        return mapOf(
            "oneshot[value]" to "false",
            "title" to "a",
            "page" to "1",

            "manga_types[include][]" to "2",
            "manga_types[exclude][]" to "3",

            "story_status[include][]" to "1",

            "translation_status[include][]" to "1",

            "categories[include][]" to "10",
            "categories[exclude][]" to "20",

            "chapters[min]" to "",
            "chapters[max]" to "",

            "dates[start]" to "",
            "dates[end]" to "",
        )
    }

    /**
     * Upstream returned an okhttp3 `RequestBody` carrying raw JSON for the search endpoint.
     * The base class's `Map<String, String>?` return type is form-data only, so we leave this
     * `null` and submit the raw JSON via the custom `normalSearch` override below.
     */
    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? = null

    /**
     * Build the raw JSON body the dilar `/api/mangas/search` endpoint expects.
     */
    private fun buildSearchJsonBody(searchType: SearchType.Normal): String {
        return """
{
  "oneshot": {
    "value": false
  },
  "title": "${searchType.query}",
  "page": 1,
  "manga_types": {
    "include": [],
    "exclude": []
  },
  "story_status": {
    "include": [],
    "exclude": []
  },
  "translation_status": {
    "include": [],
    "exclude": []
  },
  "categories": {
    "include": [ ],
    "exclude": []
  },
  "chapters": {
    "min": "",
    "max": ""
  },
  "dates": {
    "start": "",
    "end": ""
  }
}
""".trimIndent()
    }

    /**
     * Custom normalSearch override: POST with `application/json` body (not form-data) — preserves
     * upstream's `api.post(url, body = requestBody, headers = defaultHeaders)` behaviour where
     * `requestBody` was a JSON-typed `okhttp3.RequestBody`.
     */
    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        val jsonBody = buildSearchJsonBody(searchType)
        return fetchDataWithHeaders({
            api.postJson(url, body = jsonBody, headers = defaultHeaders)
        }) { html -> getSearchResults(html) }
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? {
        return mapOf(
            "oneshot[value]" to "false",
            "title" to "a",
            "page" to "1",

            "manga_types[include][]" to "2",
            "manga_types[exclude][]" to "3",

            "story_status[include][]" to "1",
            "story_status[exclude][]" to "2",

            "translation_status[include][]" to "1",

            "categories[include][]" to "10",
            "categories[exclude][]" to "20",

            "chapters[min]" to "",
            "chapters[max]" to "",

            "dates[start]" to "",
            "dates[end]" to "",
        )
    }

    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? {
        return mapOf(
            "oneshot[value]" to "false",
            "title" to "a",
            "page" to "1",

            "manga_types[include][]" to "2",
            "manga_types[exclude][]" to "3",

            "story_status[include][]" to "1",
            "story_status[exclude][]" to "2",

            "translation_status[include][]" to "1",

            "categories[include][]" to "10",
            "categories[exclude][]" to "20",

            "chapters[min]" to "",
            "chapters[max]" to "",

            "dates[start]" to "",
            "dates[end]" to "",
        )
    }

    override fun createInfoUrl(mangaId: String): String {
        return mangaId
    }

    override fun createChaptersUrl(mangaId: String): String {
        return "${mangaId}/releases"
    }

    override fun parseChapters(html: String): List<ChapterItem> {

        return try {
            Logger.withTag("sdghsfdlgdsfjlgsfdlkgjdsflkgkdslfgdsfgsd").i { html }
            val chapters: ChaptersResponse = jsonParser.decodeFromString(html)


            chapters.toChapterItems("${baseUrl.ifBlank { BASE_URL }}r")

        } catch (e: Exception) {
            mutableListOf()
        }
    }

    override fun extractHomeMangaItems(string: String): MutableList<MangaItem> {

        return try {
            val dilarItems: DilarResponse = jsonParser.decodeFromString(string)
            val items = dilarItems.toMangaItems(API, LANGUAGE)

            items
        } catch (e: Exception) {
            mutableListOf()
        }

    }

    override fun extractMangaList(string: String): List<PopularManga> {
        return listOf()
    }

    override suspend fun extractMangaInfo(
        string: String,
        baseUrl: String
    ): MangaInfo {

        return try {
            val dilarItems: InfoResponse = jsonParser.decodeFromString(string)


            val items = dilarItems.toMangaInfo(baseUrl)

            items
        } catch (e: Exception) {
            MangaInfo(
                api = "",
                language = "",
                url = "",
                title = "",
                imageUrl = "",
                rating = "",
                description = "",
                author = "",
                genres = listOf(),
                status = "",
                chapters = mutableListOf()
            )
        }

    }

    override suspend fun getSearchResults(string: String): List<MangaItem> {
        val data = jsonParser.decodeFromString<EncryptedResponse>(string)
        val parts = string.split("|")
        if (parts.size < 4) {
            return emptyList()
        }

        val dData = decrypt(data.data)

        return try {
            val manga = jsonParser.decodeFromString<SearchMangaDto>(dData)

            val mangas = manga.mangas.map {
                it.toMangaItems()
            }

            mangas
        } catch (e: Exception) {

            emptyList()
        }
    }


    override fun getChapterImages(string: String): List<String> {
        val html = Ksoup.parse(string)

        return try {
            // Android source had a `Build.VERSION.SDK_INT >= VANILLA_ICE_CREAM` branch picking
            // `.first.data()` vs `.html()`. In commonMain ksoup we only have one parser, so we
            // prefer `.data()` (the embedded raw text inside the script-like component, which is
            // what the JSON lives in) and fall back to `.html()` if `data()` came back empty.
            val componentEl = html.select(".js-react-on-rails-component").firstOrNull()
            val componentHtml = componentEl?.data()?.takeIf { it.isNotBlank() }
                ?: componentEl?.html().orEmpty()

            val release: ReleaseInfo = jsonParser
                .decodeFromString<Root>(componentHtml)
                .readerDataAction
                .readerData
                .release


            // Decide whether to use webp or png/jpg pages
            val (pages, directory) = if (release.webpPages.isNotEmpty()) {
                release.webpPages to "hq_webp"
            } else {
                release.pages to "hq"
            }


            pages
                .sortedWith(pageSort)
                .map {
                    "${baseUrl.ifBlank { BASE_URL }}uploads/releases/${release.storageKey}/$directory/$it"

                }
        } catch (e: Exception) {
            listOf()
        }


    }

    private val pageSort =
        compareBy<String>({ parseNumber(0, it) ?: Double.MAX_VALUE }, { parseNumber(1, it) }, { parseNumber(2, it) })

    private fun parseNumber(index: Int, string: String): Double? =
        Regex("\\d+").findAll(string).map { it.value }.toList().getOrNull(index)?.toDoubleOrNull()


    fun InfoResponse.toMangaInfo(
        url: String,
        imgUrl: String = "${baseUrl.ifBlank { BASE_URL }}uploads/manga/cover"
    ): MangaInfo {
        val data = this.mangaData
        val library = this.mangaLibrary

        // Basic info fallback
        val title = data?.title.orEmpty()
        val imageUrl = "${imgUrl}/${data?.id}/${data?.cover.orEmpty()}"
        val rating = data?.rating.orEmpty()
        val ratingCount = data?.rates_count?.toString().orEmpty()
        val description = data?.summary.orEmpty()
        val otherNames = data?.synonyms.orEmpty()
        val author = data?.creator_nick.orEmpty()
        val artist = data?.editor_nick.orEmpty()

        // Categories as genres; tags can be same or empty
        val genres = data?.categories
            ?.mapNotNull { it?.name }
            ?: emptyList()
        val tags = emptyList<String>()

        // Year of production: extract year from timestamp if present
        val yearOfProduction = data?.time_stamp?.let { ts ->
            Instant.fromEpochSeconds(ts.toLong())
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .year
                .toString()
        }.orEmpty()

        // Status mapping from translation_status or story_status
        val status = when (data?.translation_status) {
            1 -> "Ongoing"
            2 -> "Completed"
            else -> "Unknown"
        }

        // Favorites count from library
        val favoritesCount = library?.favorite?.toString().orEmpty()

        // Initialize chapters empty; populate elsewhere
        val chapters = mutableListOf<ChapterItem>()

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = url,
            title = title,
            imageUrl = imageUrl,
            rating = rating,
            description = description,
            author = author,
            genres = genres,
            status = status,
            chapters = chapters
        )
    }

    // 2) Convert Releases → ChapterItems
    fun ChaptersResponse.toChapterItems(baseUrl: String): List<ChapterItem> {
        return releases
            .orEmpty()
            .filterNotNull()
            .map { it.toChapterItem(baseUrl) }
    }

    // 1) Release → ChapterItem
    private fun Release.toChapterItem(baseUrl: String): ChapterItem {
        val num = (chapter ?: 0).toString()
        val name = title.orEmpty()
        val url = "$baseUrl/${id}"
        val date: LocalDate? = created_at
            ?.let {
                try {
                    Instant.parse(it).toLocalDateTime(TimeZone.currentSystemDefault()).date
                } catch (_: Exception) {
                    null
                }
            }
            ?: time_stamp
                ?.let {
                    try {
                        Instant.fromEpochSeconds(it.toLong())
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                            .date
                    } catch (_: Exception) {
                        null
                    }
                }

        return ChapterItem(
            number = num,
            name = name,
            url = url,
            date = date,
        )
    }

    fun BrowseManga.toMangaItems(

        api: String = API,
        language: String = LANGUAGE,
        url: String = "${baseUrl.ifBlank { BASE_URL }}api/mangas",  // adjust to your real base URL
        imgUrl: String = "${baseUrl.ifBlank { BASE_URL }}uploads/manga/cover"

    ): MangaItem {

        return MangaItem(
            api = api,
            language = language,
            title = this.title.orEmpty(),
            // construct URL from base + id
            url = "${url}/${this.id}",
            imageUrl = "${imgUrl}/${this.id}/${this.cover.orEmpty()}",
            // parse rating string to Int if possible
            rating = 0,
            // here we don't have chapter details—return null or empty list
            chapters = null,
            // extract names from categories
            genres = this.categories
                ?.mapNotNull { it?.name }
                .orEmpty()
        )
    }


    fun DilarResponse.toMangaItems(
        api: String = API,
        language: String = LANGUAGE,
        url: String = "${baseUrl.ifBlank { BASE_URL }}api/mangas",  // adjust to your real base URL
        imgUrl: String = "${baseUrl.ifBlank { BASE_URL }}uploads/manga/cover"
    ): MutableList<MangaItem> {
        return this.releases
            // skip null releases or releases without a manga
            .orEmpty()
            .mapNotNull { it?.manga }
            .filter { it.is_novel == false }

            .distinctBy { it.id }
            .map { manga ->
                MangaItem(
                    api = api,
                    language = language,
                    title = manga.title.orEmpty(),
                    // construct URL from base + id
                    url = "${url}/${manga.id ?: 0}",
                    imageUrl = "${imgUrl}/${manga.id}/${manga.cover.orEmpty()}",
                    // parse rating string to Int if possible
                    rating = manga.rating?.toIntOrNull(),
                    // here we don't have chapter details—return null or empty list
                    chapters = null,
                    // extract names from categories
                    genres = manga.categories
                        ?.mapNotNull { it?.name }
                        .orEmpty()
                )
            }
            .toMutableList()
    }

}
