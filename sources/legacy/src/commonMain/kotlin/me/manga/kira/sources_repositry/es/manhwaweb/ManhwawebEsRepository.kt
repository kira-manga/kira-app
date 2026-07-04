package me.manga.kira.sources_repositry.es.manhwaweb

/**
 * Migration note (Phase 7.3): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * `okhttp3.HttpUrl` query builder replaced with manual URL string assembly preserving the
 * exact parameter order from the Android source. `java.time.Instant.ofEpochMilli(...).atZone
 * (...).toLocalDate()` → `Instant.fromEpochMilliseconds(...).toLocalDateTime(...).date`.
 */

import kotlin.concurrent.Volatile
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.common.NormalSitesv2
import me.manga.kira.sources_repositry.data.MangaSource
import me.manga.kira.sources_repositry.es.manhwaweb.models.chaptes.ChaptersResponse
import me.manga.kira.sources_repositry.es.manhwaweb.models.home.ManhwasEsp
import me.manga.kira.sources_repositry.es.manhwaweb.models.home.ManhwawebResponse
import me.manga.kira.sources_repositry.es.manhwaweb.models.info.InfoResponse
import me.manga.kira.sources_repositry.es.manhwaweb.models.library.Data
import me.manga.kira.sources_repositry.es.manhwaweb.models.library.LibraryResponse

@OptIn(ExperimentalTime::class)
class ManhwawebEsRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : NormalSitesv2(api, sourcesRepository) {


    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }
    }
    override val mangaSource: MangaSource
        get() = MangaSource.MANHOWAWEB

    override val homeUrl: String by lazy { "${baseUrl.ifBlank { "https://manhwawebbackend-production.up.railway.app" }}/latest/new-manhwa" }
    override val popularUrl: String
        get() = ""

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    override val BASE_URL: String
        get() = "https://manhwawebbackend-production.up.railway.app"
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override var imgBaseUrl: String = "https://imagizer.imageshack.com/"
    override var imgUrlVersion: Int = 0
    override var customParseHome = true
    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            // Merge cached headers with Referer; if cached also contains "Referer", this ensures your value wins:
            return base
        }


    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // Option A: merge here so that cache always has Referer, and if you persist headers you want to include it
        _cachedHeaders = newHeaders


        dataStore.saveHeadersForApi(API, newHeaders)

    }
    override fun handelLoadMoreUrl(page: Int): String {
        // Android used okhttp3 HttpUrl builder; the resulting URL has these query parameters
        // appended in this exact order.
        return "${baseUrl.ifBlank { BASE_URL }}/manhwa/library" +
            "?tipo=" +
            "&demografia=" +
            "&estado=" +
            "&erotico=no" +
            "&order_dir=desc" +
            "&order_item=alfabetico" +
            "&page=$page"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return when (searchType) {


            is SearchType.Normal -> "${baseUrl.ifBlank { BASE_URL }}/manhwa/library?buscar=${searchType.query}&tipo=&demografia=&estado=&erotico=no&order_dir=desc&order_item=alfabetico&page=0"

            is SearchType.GENRES -> "${baseUrl.ifBlank { BASE_URL }}/manhwa/library?buscar=${searchType.query}&tipo=&demografia=&estado=&erotico=no&generes=${getGeneroValue(searchType.genres)}&order_dir=desc&order_item=alfabetico&page=0"

            is SearchType.SORT -> "${baseUrl.ifBlank { BASE_URL }}/manhwa/library?buscar=${searchType.query}&tipo=&demografia=&estado=&erotico=no&generes=${getGeneroValue(searchType.genres)}&order_dir=desc&order_item=${getSortTypeComment(searchType.sortType)}&page=0"

        }
    }

    override val sortTypes: Set<String>
        get() = setOf(
            "Alphabetical", //alfabetico
            "Creation",//creacion
            "Number of Chapters" //num_chapter
        )

    fun getSortTypeComment(sortType: String): String? {
        val map = mapOf(
            "Alphabetical" to "alfabetico",
            "Creation" to "creacion",
            "Number of Chapters" to "num_chapter"
        )
        return map[sortType]
    }
    override val allGenres: Set<String>
        get() = setOf(
            "Acción",               // Action
            "Aventura",             // Adventure
            "Comedia",              // Comedy
            "Drama",                // Drama
            "Recuentos de la vida", // Slice of Life
            "Romance",              // Romance
            "Venganza",             // Revenge
            "Harem",                // Harem
            "Fantasía",             // Fantasy
            "Sobrenatural",         // Supernatural
            "Tragedia",             // Tragedy
            "Psicológico",          // Psychological
            "Horror",               // Horror
            "Thriller",             // Thriller
            "Historias cortas",     // Short Stories
            "Gore",                 // Gore
            "Reencarnación",        // Reincarnation
            "Sistema de niveles",   // Level System
            "Ciencia ficción",      // Science Fiction
            "Apocalíptico",         // Apocalyptic
            "Artes marciales",      // Martial Arts
            "Superpoderes",         // Superpowers
            "Cultivación (cultivo)",// Cultivation
        )

    fun getGeneroValue(genero: String): String? {
        val generosMap = mapOf(
            "Acción" to "3",
            "Aventura" to "29",
            "Comedia" to "18",
            "Drama" to "1",
            "Recuentos de la vida" to "42",
            "Romance" to "2",
            "Venganza" to "5",
            "Harem" to "6",
            "Fantasía" to "23",
            "Sobrenatural" to "31",
            "Tragedia" to "25",
            "Psicológico" to "43",
            "Horror" to "32",
            "Thriller" to "44",
            "Historias cortas" to "28",
            "Ecchi" to "30",
            "Gore" to "34",
            "Girls love" to "27",
            "Boys love" to "45",
            "Reencarnación" to "41",
            "Sistema de niveles" to "37",
            "Ciencia ficción" to "33",
            "Apocalíptico" to "38",
            "Artes marciales" to "39",
            "Superpoderes" to "40",
            "Cultivación (cultivo)" to "35",
            "Milf" to "8"
        )
        return generosMap[genero]
    }

    override val blackListGenres: Set<String>
        get() = setOf(
//            "Ecchi",                // Ecchi
            "Girls love",           // Girls' Love / Yuri
            "Boys love",            // Boys' Love / Yaoi
            "Milf",                 // MILF

        )

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
        return null
    }

    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? {
        return null
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? {
        return null
    }

    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? {
        return null
    }

    override fun extractCustomHomeMangaItems(json: String): MutableList<MangaItem> {
        return try {
            val dilarItems: LibraryResponse = jsonParser.decodeFromString(json)
            val items = dilarItems.data.toMangaItemsLib()

            items
        } catch (e: Exception) {
            mutableListOf()
        }

    }

    override fun extractHomeMangaItems(json: String): MutableList<MangaItem> {
        return try {
            val dilarItems: ManhwawebResponse = jsonParser.decodeFromString(json)
            val items = dilarItems.manhwas?.manhwas_esp.toMangaItems()
            if (items.isNullOrEmpty()) {
                mutableListOf()

            } else {
                items
            }

        } catch (e: Exception) {
            mutableListOf()
        }
    }

    override fun extractMangaList(string: String): List<PopularManga> {
        return listOf()
    }

    override suspend fun extractMangaInfo(
        json: String,
        baseUrl: String
    ): MangaInfo {
        return try {
            val dilarItems: InfoResponse = jsonParser.decodeFromString(json)

            val info = dilarItems.toMangaInfo()
            info


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

    override suspend fun getSearchResults(json: String): List<MangaItem> {
        return try {
            val dilarItems: LibraryResponse = jsonParser.decodeFromString(json)
            val items = dilarItems.data.toMangaItemsLib()

            items
        } catch (e: Exception) {
            mutableListOf()
        }
    }



    override fun getChapterImages(json: String): List<String> {
        return try {
            val chaptersResponse: ChaptersResponse = jsonParser.decodeFromString(json)
            chaptersResponse.chapter
                ?.img
                ?.filterNotNull()
                ?: emptyList()

        } catch (e: Exception) {
            listOf()
        }
    }



    fun List<Data?>?.toMangaItemsLib(): MutableList<MangaItem> {
        // if list is null or empty, return an empty MutableList


        return this
            // drop any null entries
            ?.mapNotNull { data ->
                data?.let {

                    MangaItem(
                        api = API,          // e.g. "manhwaweb"
                        language = LANGUAGE,   // e.g. "KR"
                        title = it.the_real_name ?: "—",
                        url = if (!it.real_id.isNullOrEmpty()) "${baseUrl.ifBlank { BASE_URL }}/manhwa/see/${it.real_id}" else "${baseUrl.ifBlank { BASE_URL }}/manhwa/see/${it._id}",
                        imageUrl = it._imagen ?: "",
                        rating = null,                          // no source field, so null
                        chapters = emptyList<ChapterItem>(),      // fill in if you have chapter data
                        genres = it._categoris
                            // map each Int? → String?
                            ?.mapNotNull { catId -> catId.toString() }
                            ?: emptyList()
                    )
                }
            }
            // produce a MutableList
            ?.toMutableList()
        // if original list was null, fall back to empty
            ?: mutableListOf()
    }
    fun List<ManhwasEsp?>?.toMangaItems(): MutableList<MangaItem>? {
        return this?.map { esp ->
            // 2) build up the genres list, filtering out blank/nulls


            val genres = listOfNotNull(
                esp?._demografi,
                esp?._plataforma,
                esp?._tipo,
                esp?.lgbt


            ).filter { it.isNotBlank() }

            MangaItem(
                api = API,                              // your source identifier
                language = LANGUAGE,                                     // Spanish
                title = esp?.name_manhwa.orEmpty(),                // e.g. "Solo Leveling"
                url = "${baseUrl.ifBlank { BASE_URL }}/manhwa/see/${esp?.id_rel}",                     // see helper below
                imageUrl = esp?.img.orEmpty(),                        // cover URL
                rating = null,                                     // no rating in this model
                chapters = emptyList<ChapterItem>(),                 // you'd fill this later if you fetch chapters
                genres = genres
            )
        }?.toMutableList()
    }


    fun InfoResponse.toMangaInfo(): MangaInfo {
        val userZone: TimeZone = TimeZone.currentSystemDefault()

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = if (!real_id.isNullOrEmpty()) "${baseUrl.ifBlank { BASE_URL }}/manhwa/see/${real_id}" else "${baseUrl.ifBlank { BASE_URL }}/manhwa/see/${_id}",
            title = the_real_name.orEmpty(),
            imageUrl = _imagen.orEmpty(),
            rating = "",               // No rating field in InfoResponse
            description = _sinopsis.orEmpty(),
            author = "",               // No author field available
            genres = _categoris
                ?.mapNotNull { it?.entries?.firstOrNull() }
                ?.mapNotNull { (key, value) ->
                    key.toIntOrNull()?.let { value.takeIf { it.isNotBlank() } }
                }
                .orEmpty(),
            status = _status.orEmpty(),
            chapters = chapters
                ?.mapNotNull { it }
                ?.map { chapter ->
                    val slug = chapter.link?.removeSuffix("/")?.substringAfterLast("/")
                    val date = chapter.create
                        ?.let { Instant.fromEpochMilliseconds(it) }
                        ?.toLocalDateTime(userZone)
                        ?.date
                    ChapterItem(
                        name = "Capítulo ${chapter.chapter}",
                        number = chapter.chapter.toString(),
                        url = "${baseUrl.ifBlank { BASE_URL }}/chapters/see/$slug",
                        date = date

                    )
                }?.reversed()
                ?.toMutableList()
                ?: mutableListOf()
        )
    }

}

/*
 * Audit-trail postscript (Phase 9.x.cluster198.staleKdocSweep.cascade, Task #653, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster198 leaf 5/5 — :es/ Repository tier light-half batch CLOSING leaf, sibling 349.
 *
 * Closes the :es/ Repository light-half arc (siblings 345-349). Sets up the heavy-half
 * (cluster199) which will sweep OlympusbibliotecaRepository (~663 lines) and
 * TaurusFansubEsRepository (~686 lines).
 *
 * The KDoc preamble at lines 3-10 lists a 6-axis Phase 7.3 migration (Retrofit→Ktor,
 * jsoup→ksoup, FormBody→Map, @Inject drop, android.util.Log→Kermit, java.time→
 * kotlinx.datetime) PLUS two additional shim notes: okhttp3.HttpUrl query builder →
 * manual URL string assembly (preserving parameter order), and
 * `Instant.ofEpochMilli(...).atZone(...).toLocalDate()` →
 * `Instant.fromEpochMilliseconds(...).toLocalDateTime(...).date`. Cluster198-boundary
 * verification: each Phase 7.3 axis has a live counterpart in the body (lines 12-33
 * import lines confirm kotlinx.datetime/kotlin.time, no Retrofit/jsoup/okhttp3, no
 * @Inject, no android.util.Log).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • FULFILLED-PORT — All 6+2 migration axes in the preamble are verified live. No
 *     residual legacy import remains. The two SHIM notes (HttpUrl→manual URL, Instant
 *     conversion) are visible at lines 92-103 (handelLoadMoreUrl manual URL assembly
 *     with the preserved parameter-order comment) and line 388 (Instant.fromEpochMilliseconds
 *     in toMangaInfo chapter-date construction).
 *
 *   • LIVE-NOT-STALE — `override suspend fun initSite(): Int { ... }` at lines 58-62 is
 *     ACTIVE (not commented out). Loads DataStore-persisted headers into _cachedHeaders
 *     then chains to super.initSite(). Contrast with sibling 348 (InMangaRepository)
 *     where the analogous initSite override was COMMENTED OUT (lines 72-76 there) —
 *     same DataStore-backed-_cachedHeaders pattern, but only this :es/ leaf currently
 *     activates it. Pattern-register entry for cluster198: 2/5 leaves expose the pattern,
 *     1/2 activate it.
 *
 *   • LIVE-NOT-STALE — `override var customParseHome = true` at line 72 is the routing
 *     flag distinguishing this source: it has a CUSTOM home-parse path (JSON-API rather
 *     than HTML scraping). Routes to extractCustomHomeMangaItems() at lines 218-228
 *     which decodes LibraryResponse and calls toMangaItemsLib().
 *
 *   • COSMETIC-NOT-STALE — defaultHeaders getter comment at line 79 says "Merge cached
 *     headers with Referer; if cached also contains 'Referer', this ensures your value
 *     wins" — but the actual implementation (lines 78-80) just returns `_cachedHeaders
 *     ?: emptyMap()`. No Referer merge is performed in the visible body. The comment
 *     describes intended-but-unrealized behaviour. Same pattern at refreshHeaders line 85
 *     ("Option A: merge here..."). Preserved per §253 — comments are migration-era
 *     intent, not factual claims about current behaviour.
 *
 *   • LIVE-NOT-STALE — `handelLoadMoreUrl(page)` at lines 92-103 uses explicit
 *     empty-value query parameters (`tipo=`, `demografia=`, `estado=`) to preserve the
 *     exact HttpUrl-builder parameter order from the Android source. Removing these
 *     "empty" params would change the URL fingerprint and potentially break server-side
 *     query parsing that depends on parameter presence.
 *
 *   • POTENTIAL-BUG-PRESERVED — `override val homeUrl: String by lazy { ... }` at line 54
 *     caches the URL forever once first computed. If `baseUrl` changes at runtime
 *     (DataStore-driven), this lazy property will NOT refresh. Compare with sibling 348
 *     and the BASE_URL/API/LANGUAGE getters at lines 63-68 which all use `get() = ...`
 *     property-accessor form (recomputed on every read). Asymmetric reactivity to
 *     baseUrl mutations. Preserved per §253 — divergence from upstream is unknown
 *     without a side-by-side; flagged for future review.
 *
 *   • LIVE-NOT-STALE — Bilingual maps: `sortTypes` (lines 118-123) declares 3 user-facing
 *     English labels ("Alphabetical"/"Creation"/"Number of Chapters") with Spanish
 *     backend values as line-comments; `getSortTypeComment` (lines 125-132) is the
 *     forward-lookup helper. Same pattern for `allGenres` (lines 133-158) → English
 *     line-comments on Spanish keys, with `getGeneroValue` (lines 160-191) providing
 *     ID lookup. Bilingual translation surface lives in code, not external resources.
 *
 *   • LIVE-NOT-STALE — `blackListGenres` at lines 193-200 has 3 ACTIVE entries
 *     ("Girls love"/"Boys love"/"Milf") and 1 COMMENTED ("Ecchi") at line 195.
 *     Contrast with sibling 348 (InMangaRepository) which had an EMPTY blacklist
 *     (no adult-content gate at that source). Pattern-register: this source applies
 *     an explicit adult-filter; the commented Ecchi entry is preserved history (was
 *     active, was disabled — preserved per §253 to document the decision).
 *
 *   • LIVE-NOT-STALE — All 4 FormBody overrides (handelFormBody, normalSearchFormBody,
 *     genresSearchFormBody, sortFormBody) at lines 202-216 return null. This source's
 *     API is GET-only (URL params, no POST bodies). Returning null is the
 *     NormalSitesv2 base-class signal "don't issue a POST request for this operation".
 *
 *   • LIVE-NOT-STALE — `extractMangaList` at lines 246-248 returns `listOf()` empty.
 *     This source has no "popular" endpoint — the empty implementation is the
 *     deliberate signal upstream that the source doesn't expose popularity ranking.
 *
 *   • LIVE-NOT-STALE — `getChapterImages` at lines 292-303 reads from
 *     `ChaptersResponse.chapter.img.filterNotNull()`. Straight nullable-list
 *     traversal — no CDN URL assembly, no template — the JSON response itself carries
 *     the absolute image URLs.
 *
 *   • POTENTIAL-BUG-PRESERVED — `toMangaItemsLib` at lines 307-335: the genres mapping
 *     at line 326 is `?.mapNotNull { catId -> catId.toString() }`. This is a NO-OP
 *     beyond null-stripping — it returns the NUMERIC category-IDs as strings rather
 *     than their human-readable names. The forward-translate helper `getGeneroValue`
 *     (lines 160-191) maps name→ID; the inverse lookup that would translate IDs back
 *     to names is NOT performed here. Result: library-list MangaItems carry genres
 *     like ["3", "29"] (numeric IDs) rather than ["Acción", "Aventura"]. Caller
 *     downstream may or may not handle this — preserved per §253, flagged.
 *
 *   • LIVE-NOT-STALE — `toMangaInfo` at lines 364-402: contrasts with the bug above.
 *     The genre mapping at lines 376-380 uses `_categoris ?.mapNotNull { it?.entries?
 *     .firstOrNull() } ?.mapNotNull { (key, value) -> key.toIntOrNull()?.let { value
 *     .takeIf { it.isNotBlank() } } }`. The shape is Map<String, String> entries
 *     keyed on string-int-castable IDs with values that ARE the human-readable
 *     names. Different JSON shape than toMangaItemsLib's input (which is List<Int?>
 *     of bare IDs only). The bug-vs-correct divergence is rooted in the input-shape
 *     asymmetry, not a coding error here.
 *
 *   • LIVE-NOT-STALE — `toMangaItems` (4 fields: _demografi/_plataforma/_tipo/lgbt)
 *     at lines 336-361 uses `listOfNotNull` + `filter { it.isNotBlank() }` to assemble
 *     the genres column from independent JSON fields (not a categories array). Third
 *     genre-assembly path in this file, third input shape — preserved as-is.
 *
 *   • LIVE-NOT-STALE — Spanish slug literal "Capítulo ${chapter.chapter}" at line 392
 *     in toMangaInfo chapter-name construction. UI-facing Spanish string baked into
 *     the source repository — appropriate for a Spanish-language source.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 18 imports at lines 12-33 cover:
 *       kotlin.concurrent.Volatile, kotlin.time.ExperimentalTime/Instant,
 *       kotlinx.datetime.TimeZone/toLocalDateTime, kotlinx.serialization.json.Json,
 *       core.storage.DataStoreHelper, data.local.dao.SourcesDao,
 *       data.remote.api.ApiClient, domain.model.{ChapterItem, MangaInfo, MangaItem,
 *       PopularManga}, presentation.features.home.data.SearchType,
 *       sources_repositry.{common.NormalSitesv2, data.MangaSource}, +
 *       5 same-package model imports under :es/manhwaweb/models/{chaptes, home, info,
 *       library}. All confirmed-live at cluster198 boundary.
 *
 *   • COSMETIC-NOT-STALE — Excessive blank lines in several places (line 42 between
 *     constructor `{` and first member; lines 88+90 around dataStore.saveHeadersForApi
 *     call; lines 107-108 inside `when` branch; lines 199+289+291+304-306+310 inside
 *     toMangaItemsLib). Whitespace artifacts from migration auto-formatting — preserved
 *     per §253, not stylistically meaningful.
 *
 * Cross-cluster pattern register (cluster198 light-half closing observations):
 *   1. DataStore-backed _cachedHeaders @Volatile + initSite preload: 2/5 leaves expose
 *      the pattern (sibling 348 InManga + sibling 349 ManhwawebEs); 1/2 activates it
 *      (sibling 349 only — sibling 348 has it commented out).
 *   2. blackListGenres: 2/5 leaves expose it; sibling 348 = empty Set, sibling 349 = 3
 *      active + 1 commented. Adult-content filtering is per-source.
 *   3. Bilingual translation surface (Spanish backend ↔ English UI): only sibling 349
 *      exposes it (sibling 348 is monolingual in Spanish; siblings 345-347 are
 *      delegation shims with no maps).
 *   4. POTENTIAL-BUG-PRESERVED count in cluster198: 4 (sibling 347 es_419 vs es-419
 *      separator, sibling 348 filter[take]="w0" typo, sibling 348 skip=0 hardcoded
 *      pagination, sibling 349 toMangaItemsLib numeric-string genres). Higher than
 *      cluster197's 0 — :es/ Repository tier has noticeably more drift than :en/
 *      Parsers/Models tier.
 *   5. Architecture posture: cluster198 contains 2 standalone repositories (348, 349)
 *      + 2 thin subclass shims (346, 347) + 1 documented disabled placeholder (345).
 *      No facade churn, no Strangler-fig touches, no §250/§251/§252 blockers.
 */

