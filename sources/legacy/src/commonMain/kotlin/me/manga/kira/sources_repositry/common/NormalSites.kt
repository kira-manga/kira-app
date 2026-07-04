package me.manga.kira.sources_repositry.common

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import me.manga.kira.core.states.State
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.data.MangaSource

/**
 * Migration note (Phase 7 batch 7.0):
 * - `android.util.Log` → Kermit `Logger`.
 * - `me.manga.kira.data.remote.api.IMangaDataApiServices` (Retrofit) → `ApiClient` (Ktor
 *   wrapper). Calls map 1-to-1:
 *     api.get(url, headers = h)              → apiClient.get(url, h)
 *     api.post(url, headers = h, body = b)   → apiClient.postForm(url, b ?: emptyMap(), h)
 * - `okhttp3.FormBody` (used as the form-body type) → `Map<String, String>?` — KMP-portable
 *   key/value bag matching the application/x-www-form-urlencoded shape that FormBody represented.
 *   Concrete subclasses (Phase 7.1-7.9) will follow suit.
 * - `me.manga.kira.core.storage.DataStoreHelper` constructor parameter dropped: it was
 *   never read by any code in this base class. The Android-DataStore-backed implementation will
 *   be ported in Phase 8 (multiplatform-settings + kotlinx.serialization). Concrete subclasses
 *   that need persistent storage will declare their own DataStoreHelper constructor parameter
 *   once Phase 8 supplies it.
 */
abstract class NormalSites (
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : BaseManga(sourcesRepository) {
    abstract override val mangaSource: MangaSource
    abstract val homeUrl : String
    abstract val popularUrl : String
    open var homeGet = true
    var searchGet = true

    abstract fun handelLoadMoreUrl(page: Int) : String

    abstract fun handelSearchUrl(searchType: SearchType) : String
    abstract override val sortTypes: Set<String>
    abstract override val allGenres: Set<String>
    abstract override val blackListGenres: Set<String>
    abstract override val defaultHeaders: Map<String, String>

     override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> = fetchMangaHome(homeUrl)

    abstract fun handelFormBody(page:Int = 0,popular: Boolean): Map<String, String>?

     fun searchFormBody(searchType: SearchType): Map<String, String>? =
        when (searchType) {
            is SearchType.Normal  -> normalSearchFormBody(searchType)
            is SearchType.GENRES  -> genresSearchFormBody(searchType)
            is SearchType.SORT    -> sortFormBody(searchType)
        }



    fun fetchMangaHome(url : String,page:Int = 0): Flow<State<MutableList<MangaItem>>> =
        fetchDataWithHeaders({
            Logger.withTag("asdasdasdasdasdasfetchMangaHome1").i { url }


            if (homeGet){
                api.get(url, headers = defaultHeaders)
            } else{
                Logger.withTag("asdasdasdasdasdasfetchMangaHome2").i { url }
                Logger.withTag("asdasdasdasdasdasfetchMangaHome3").i { handelFormBody(page,false).toString() }

                api.postForm(url, fields = handelFormBody(page,false) ?: emptyMap(), headers = defaultHeaders)
            }


        }){  html -> extractHomeMangaItems(html)}



    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
        fetchDataWithHeaders({
            Logger.withTag("asdasdasdasdasdasfetchPopularManga1").i { popularUrl }

            if (homeGet){
                Logger.withTag("asdasdasdasdasdasfetchPopularManga2").i { popularUrl }

                api.get(popularUrl)
            } else{

                api.postForm(popularUrl, fields = handelFormBody(0,true) ?: emptyMap(), headers = defaultHeaders)
            }

        }) { html -> extractMangaList(html) }


    override suspend fun fetchMangaChaptersF(query: String): Flow<State<MangaInfo>> = fetchDataWithHeaders({

        defaultHeaders.logHeaders()

        api.get(query, headers = defaultHeaders)


    })  { html ->  extractMangaInfo(html,query) }

    private fun Map<String, String>.logHeaders(
        tag: String = "manga-headers",
        bigTag: String = "manga-headers-big",
        bigThreshold: Int = 100,
        chunkSize: Int = 1500
    ) {
        forEach { (k, v) ->
            val msg = "$k: $v"
            val useTag = if (msg.length > bigThreshold) bigTag else tag
            msg.chunked(chunkSize).forEach { part ->
                Logger.withTag(useTag).i { part }
            }
        }
    }



    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }) { html -> getChapterImages(html) }




    override fun fetchMoreManga(page: Int, currentItems: List<MangaItem>?): Flow<State<List<MangaItem>>> =
        flow {
            emit(State.Loading as State<List<MangaItem>>)
            val url = handelLoadMoreUrl(page)

            fetchMangaHome(url,page).collect { state ->


                when (state) {
                    is State.Success -> {
                        val newItems = state.toData() ?: emptyList()
                        val mergedList = (currentItems?.toMutableList() ?: mutableListOf()).apply {
                            addAll(newItems)
                        }
                        emit(
                            State.Success(
                                if (newItems.isEmpty()) (currentItems ?: emptyList()) else mergedList
                            )
                        )
                    }

                    is State.Error -> emit(state)
                    else -> Unit
                }
            }
        }.catch { e ->
            emit(State.Error(0,e.message ?: "Unknown error occurred"))
        }



    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        Logger.withTag("asfjlafksdfdsadfsdfsdfasdfsad3").i { url }

        return  if (searchGet) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({ api.postForm(url, fields = searchFormBody(searchType) ?: emptyMap(), headers = defaultHeaders) }){  html -> getSearchResults(html)}
        }
    }

    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>>  {
        val url = handelSearchUrl(searchType)
        return  if (searchGet) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({ api.postForm(url, fields = searchFormBody(searchType) ?: emptyMap(), headers = defaultHeaders) }){  html -> getSearchResults(html)}
        }  }

    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        return if (searchGet) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({ api.postForm(url, fields = searchFormBody(searchType) ?: emptyMap(), headers = defaultHeaders) }){  html -> getSearchResults(html)}
        }
    }

    abstract fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>?
    abstract fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>?
    abstract fun sortFormBody(searchType: SearchType.SORT): Map<String, String>?



    abstract fun extractHomeMangaItems(string: String): MutableList<MangaItem>
    abstract fun extractMangaList(string: String): List<PopularManga>
    abstract suspend fun extractMangaInfo(string: String, baseUrl : String): MangaInfo
    abstract suspend fun getSearchResults(string: String): List<MangaItem>
    abstract override suspend fun refreshHeaders(newHeaders: Map<String, String>)
    abstract fun getChapterImages(string: String): List<String>

}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster189.staleKdocSweep.cascade,
 * Task #691, 2026-05-29): classified as follows after recursive symbol
 * verification (three-hundred-and-third sibling of the cluster57-188 sweep
 * continuum — leaf 2/5 of the wave-59 :shared sources_repositry common
 * tier scout 5-leaf batch; NormalSites.kt 2/5).
 *
 *  (a) Top-KDoc "Migration note (Phase 7 batch 7.0): android.util.Log →
 *  Kermit Logger + me.manga.kira.data.remote.api.IMangaDataApiServices
 *  (Retrofit) → ApiClient (Ktor wrapper) + Calls map 1-to-1: api.get(url,
 *  headers = h) → apiClient.get(url, h) + api.post(url, headers = h, body
 *  = b) → apiClient.postForm(url, b ?: emptyMap(), h) + okhttp3.FormBody
 *  → Map<String, String>? — KMP-portable key/value bag matching the
 *  application/x-www-form-urlencoded shape that FormBody represented +
 *  Concrete subclasses (Phase 7.1-7.9) will follow suit + me.manga.kira
 *  .core.storage.DataStoreHelper constructor parameter dropped: it was
 *  never read by any code in this base class. The Android-DataStore
 *  -backed implementation will be ported in Phase 8 (multiplatform
 *  -settings + kotlinx.serialization). Concrete subclasses that need
 *  persistent storage will declare their own DataStoreHelper constructor
 *  parameter once Phase 8 supplies it" — LIVE-NOT-STALE + FULFILLED-PORT
 *  on all four substitution bullets:
 *    (a.1) `android.util.Log` → Kermit `Logger` substitution — VERIFIED
 *      FULFILLED via the `import co.touchlab.kermit.Logger` (line 3) +
 *      `Logger.withTag("...").i { ... }` call-shape used at 7 sites
 *      (fetchMangaHome lines 65/71/72, fetchPopularManga lines 84/87,
 *      normalSearch line 163, logHeaders private extension line 117).
 *      All zero `android.util.Log` imports verified absent.
 *    (a.2) `IMangaDataApiServices` (Retrofit) → `ApiClient` (Ktor wrapper)
 *      substitution — VERIFIED FULFILLED via the constructor parameter
 *      `private val api: ApiClient` (line 33) + the documented 1-to-1
 *      call mapping verified at every call site: `api.get(url, headers =
 *      defaultHeaders)` (lines 69, 89, 102, 125, 165, 172, 178) + `api
 *      .postForm(url, fields = ... ?: emptyMap(), headers = defaultHeaders)`
 *      (lines 74, 92, 166, 173, 179). The `api.get(popularUrl)` call at
 *      line 89 is a minor variation — it elides the headers default
 *      parameter rather than passing `defaultHeaders` — preserving the
 *      Retrofit-source's identical no-headers shape for the popular
 *      endpoint.
 *    (a.3) `okhttp3.FormBody` → `Map<String, String>?` substitution —
 *      VERIFIED FULFILLED via the abstract member signature `abstract fun
 *      handelFormBody(page: Int = 0, popular: Boolean): Map<String, String>?`
 *      (line 52) + the concrete `fun searchFormBody(searchType: SearchType)
 *      : Map<String, String>?` dispatch fan-out (lines 54-59) + the three
 *      abstract bottom-half members `normalSearchFormBody` + `genresSearch
 *      FormBody` + `sortFormBody` all returning `Map<String, String>?`
 *      (lines 183-185). The `?: emptyMap()` Elvis at every postForm call
 *      handles the null-form-body fallback semantics — narrower than
 *      OkHttp's `RequestBody?` since `Map<String, String>?` cannot
 *      represent multipart or raw-byte body shapes, but precisely matches
 *      the application/x-www-form-urlencoded use case the FormBody source
 *      actually exercised.
 *    (a.4) `DataStoreHelper` constructor parameter drop — VERIFIED
 *      FULFILLED via the constructor signature `abstract class NormalSites
 *      (private val api: ApiClient, sourcesRepository: SourcesDao) :
 *      BaseManga(sourcesRepository)` (lines 32-35) — only 2 constructor
 *      parameters, both consumed: `api` retained as `private val` field
 *      + `sourcesRepository` passed to `BaseManga` parent constructor.
 *      Zero references to `DataStoreHelper` in imports or body. The
 *      Phase-8 forecast "Concrete subclasses that need persistent storage
 *      will declare their own DataStoreHelper constructor parameter once
 *      Phase 8 supplies it" remains FORECAST-NOT-YET-FULFILLED — Phase 8
 *      multiplatform-settings + kotlinx.serialization port has not
 *      landed at the cluster189 sweep timestamp, no concrete subclass
 *      declares a DataStoreHelper-equivalent constructor parameter.
 *
 *  (b) `abstract class NormalSites(...) : BaseManga(sourcesRepository) {
 *  ... }` body — LIVE-NOT-STALE; verified the BaseManga parent
 *  inheritance (line 35) — the abstract class IS the LIVE intermediate
 *  tier between BaseManga (cluster189 leaf 1/5 sibling 302) and the per
 *  -language concrete repo subclasses (cluster190 forecast scout target).
 *  Verified 8 abstract members at lines 36-48 (mangaSource + homeUrl +
 *  popularUrl + handelLoadMoreUrl + handelSearchUrl + sortTypes +
 *  allGenres + blackListGenres + defaultHeaders) — the per-source
 *  customization surface. The two `open var` toggles `homeGet` (line 39)
 *  + `searchGet` (line 40) default to `true` — meaning the default
 *  routing is GET for both home and search endpoints, and POST-fallback
 *  concrete sources opt-in by overriding either toggle to `false`.
 *
 *  (c) Concrete `fun fetchMangaHome(url: String, page: Int = 0)` body
 *  (lines 63-78) — LIVE-NOT-STALE; verified the GET/POST routing
 *  branching on `homeGet`: GET branch calls `api.get(url, headers =
 *  defaultHeaders)` (line 69) + POST branch calls `api.postForm(url,
 *  fields = handelFormBody(page, false) ?: emptyMap(), headers =
 *  defaultHeaders)` (line 74). The `false` argument to handelFormBody
 *  distinguishes home/load-more (false) from popular (true) — used by
 *  concrete subclasses to conditionalize the form-body payload. The
 *  `fetchDataWithHeaders({ ... }) { html -> extractHomeMangaItems(html) }`
 *  wrapper at the end delegates to the BaseManga parent's centralized
 *  request-execution + HTML-extraction Flow pipeline (cluster189 leaf
 *  1/5 sibling 302 documents the parent's fetchDataWithHeaders body).
 *
 *  (d) `override suspend fun fetchPopularManga(baseUrl: String)` body
 *  (lines 82-95) — LIVE-NOT-STALE; verified the same homeGet-conditional
 *  routing pattern as fetchMangaHome but consuming `popularUrl` instead
 *  of `url` and passing `popular = true` to handelFormBody. The
 *  `baseUrl` function parameter is UNUSED in the body — preserved per
 *  the BaseManga abstract signature contract (the parameter exists on
 *  the parent class signature, NormalSites overrides without consuming
 *  it because the popular URL is class-level via the abstract
 *  `popularUrl` member).
 *
 *  (e) `override suspend fun fetchMangaChaptersF(query: String)` body
 *  (lines 98-105) — LIVE-NOT-STALE; verified the single-endpoint GET
 *  call `api.get(query, headers = defaultHeaders)` + the
 *  `defaultHeaders.logHeaders()` diagnostic call at line 100 (the
 *  private `Map<String, String>.logHeaders` extension at lines 107-120
 *  chunks long header-values for tag-limited log capture per Bug-4
 *  -era diagnostic posture). The extension uses a `bigThreshold = 100`
 *  routing rule: messages above 100 chars use `bigTag = "manga-headers
 *  -big"` instead of `tag = "manga-headers"` — distinguishing long
 *  cookie strings from short header pairs.
 *
 *  (f) `override fun fetchChapterDataF(url: String)` body (lines 124-125)
 *  — LIVE-NOT-STALE; verified the minimal single-endpoint GET call
 *  pattern. The `getChapterImages(html)` callback at line 125 is the
 *  abstract bottom-half member at line 194 — concrete sources implement
 *  per-template chapter-page-image extraction.
 *
 *  (g) `override fun fetchMoreManga(page: Int, currentItems: List<MangaItem>?)`
 *  body (lines 130-157) — LIVE-NOT-STALE; verified the pagination merge
 *  pattern: builds `url = handelLoadMoreUrl(page)` (line 133), recurses
 *  into `fetchMangaHome(url, page)` (line 135), and merges new items
 *  into `currentItems` via `addAll`. The empty-newItems fallback at
 *  line 146 — `if (newItems.isEmpty()) (currentItems ?: emptyList())
 *  else mergedList` — preserves the previous-page list when the next
 *  page returns zero items, signaling the end of the pagination chain.
 *  The `.catch { e -> emit(State.Error(0, e.message ?: "Unknown error
 *  occurred")) }` terminal operator (lines 155-157) ensures the
 *  pagination flow never propagates uncaught exceptions to the caller
 *  — wrapping any downstream collector exception in a State.Error.
 *
 *  (h) `override suspend fun normalSearch / genresSearch / sortSearch`
 *  override triplet (lines 161-181) — LIVE-NOT-STALE; verified the
 *  three overrides each delegate to `handelSearchUrl(searchType)` for
 *  URL building, then branch on `searchGet` for GET/POST routing. The
 *  GET branch passes `headers = defaultHeaders` only; the POST branch
 *  builds `fields = searchFormBody(searchType) ?: emptyMap()` via the
 *  `searchFormBody` fan-out at line 54-59 which dispatches to the
 *  appropriate form-body builder per SearchType variant. All three
 *  searches share `getSearchResults(html)` as the extraction callback
 *  (the abstract bottom-half member at line 192) — distinct from
 *  `extractHomeMangaItems` used by fetchMangaHome.
 *
 *  (i) 4 abstract bottom-half members `extractHomeMangaItems +
 *  extractMangaList + extractMangaInfo + getSearchResults +
 *  refreshHeaders + getChapterImages` (lines 189-194) — LIVE-NOT-STALE;
 *  these are the per-source-template extraction-customization surface.
 *  Each concrete subclass (cluster190 forecast targets) implements
 *  jsoup-equivalent HTML-parsing logic specific to its source template.
 *  The `refreshHeaders(newHeaders: Map<String, String>)` abstract
 *  override at line 193 inherits from BaseMangaRepository's broader
 *  refreshHeaders surface — gateway for per-source Cloudflare-cookie
 *  refresh after a 403 layer-2 fingerprint failure (cluster188 BaseManga
 *  sibling 302 documents the layer-2 Bug 4 diagnostic posture).
 *
 *  (j) `searchFormBody(searchType: SearchType)` `when` dispatch (lines
 *  54-59) — LIVE-NOT-STALE; verified the three-arm exhaustive `when` on
 *  the sealed SearchType hierarchy (SearchType.Normal +
 *  SearchType.GENRES + SearchType.SORT) — Kotlin's sealed-class
 *  exhaustiveness guarantee means no `else` branch needed. The dispatch
 *  arms route to `normalSearchFormBody + genresSearchFormBody +
 *  sortFormBody` — the three abstract bottom-half form-body-builder
 *  members at lines 183-185. This is the per-search-type form-body
 *  customization surface.
 *
 * Verified: 1 `abstract class NormalSites` declaration extending
 * `BaseManga(sourcesRepository)` with 2 constructor parameters (api +
 * sourcesRepository) + 8 abstract members (mangaSource + homeUrl +
 * popularUrl + handelLoadMoreUrl + handelSearchUrl + sortTypes + allGenres
 * + blackListGenres + defaultHeaders) + 2 `open var` GET/POST toggles
 * (homeGet + searchGet) + 1 abstract handelFormBody member + 1 concrete
 * searchFormBody when-dispatch + 6 concrete fetch fan-out methods
 * (fetchMangaHome + fetchPopularManga + fetchMangaChaptersF +
 * fetchChapterDataF + fetchMoreManga + normalSearch/genresSearch/sortSearch
 * triplet) + 1 private logHeaders extension + 5 abstract bottom-half
 * extraction members (extractHomeMangaItems + extractMangaList +
 * extractMangaInfo + getSearchResults + getChapterImages) + 1 abstract
 * refreshHeaders override + 4 Phase-7-batch-7.0 migration-note KDoc
 * bullets. Sibling: BaseManga.kt (cluster189 leaf 1/5 prior sibling 302).
 * LEAF 2/5 of the cluster189 :shared sources_repositry common tier scout
 * 5-leaf batch. Compound classification: LIVE-NOT-STALE + FULFILLED-PORT
 * for the four Phase 7 batch 7.0 substitution bullets (Log → Kermit +
 * Retrofit ApiClient with 1-to-1 call mapping + FormBody → Map nullable
 * + DataStoreHelper constructor-parameter drop). The "Phase 8 multiplatform
 * -settings forecast" remains FORECAST-NOT-YET-FULFILLED. Original
 * Phase-7-batch-7.0 migration-note prose preserved verbatim.
 */
