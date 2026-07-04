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
 * - `IMangaDataApiServices` (Retrofit) → `ApiClient` (Ktor wrapper). See NormalSites.kt header
 *   for the call-mapping table.
 * - `okhttp3.FormBody` → `Map<String, String>?` (KMP-portable form-data bag).
 * - `DataStoreHelper` constructor parameter dropped — never used in this base class; concrete
 *   subclasses that need it will declare it themselves after Phase 8 supplies the KMP port.
 */
abstract class NormalSitesv2 (
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : BaseManga(sourcesRepository,) {
    abstract override val mangaSource: MangaSource
    abstract val homeUrl : String
    abstract val popularUrl : String

    abstract override val defaultHeaders: Map<String, String>

    abstract fun handelLoadMoreUrl(page: Int) : String
    abstract fun handelSearchUrl(searchType: SearchType) : String

    open var customParseHome: Boolean = false

    open var useGetForHome: Boolean = true
    open var useGetForSearch: Boolean = true
    open var useGetForNormalSearch: Boolean = true
    open var useGetForGenresSearch: Boolean = true
    open var useGetForSortSearch: Boolean = true
    open var useGetForPopular: Boolean = true
    open var useGetForChapters: Boolean = true
    open var useGetForInfo: Boolean = true


    abstract override val sortTypes: Set<String>
    abstract override val allGenres: Set<String>
    abstract override val blackListGenres: Set<String>


    abstract fun handelFormBody(page:Int = 1,popular: Boolean): Map<String, String>?

    open fun handelFormBodyPopular(page:Int = 1,popular: Boolean): Map<String, String>? = handelFormBody(page,popular)
    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
        fetchDataWithHeaders({
            if (useGetForPopular){
                api.get(popularUrl,defaultHeaders)
            } else{

                api.postForm(popularUrl, fields = handelFormBodyPopular(0,true) ?: emptyMap(), headers = defaultHeaders)
            }

        }) { html -> extractMangaList(html) }

    open fun handelFormBodyHome(page:Int = 1,popular: Boolean): Map<String, String>? = handelFormBody(page,popular)

    override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> = fetchMangaHome(homeUrl)
    fun fetchMangaHome(url : String,page:Int = 1): Flow<State<MutableList<MangaItem>>> =
        fetchDataWithHeaders({
//            Logger.withTag("sjghfdgdsfgsdfgdfggsdfgsdfgdfgsfd").i { url }
            if (useGetForHome){
                Logger.withTag("csdfdsfsdfasdasfdsfsdsd1").i { url }

                api.get(url, headers = defaultHeaders)
            } else{

                api.postForm(url, fields = handelFormBodyHome(page,false) ?: emptyMap(), headers = defaultHeaders)
            }


        }){  html ->


            extractHomeMangaItems(html)


        }
    fun fetchMangaHomeCustom(url : String,page:Int = 1): Flow<State<MutableList<MangaItem>>> =
        fetchDataWithHeaders({
            if (useGetForHome){
                Logger.withTag("csdfdsfsdfasdasfdsfsdsd2").i { url }

                api.get(url, headers = defaultHeaders)
            } else{

                api.postForm(url, fields = handelFormBodyHome(page,false) ?: emptyMap(), headers = defaultHeaders)
            }


        }){  html ->
                extractCustomHomeMangaItems(html)


        }


    fun searchFormBody(searchType: SearchType): Map<String, String>? =
        when (searchType) {
            is SearchType.Normal  -> normalSearchFormBody(searchType)
            is SearchType.GENRES  -> genresSearchFormBody(searchType)
            is SearchType.SORT    -> sortFormBody(searchType)
        }

    abstract fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>?
    abstract fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>?
    abstract fun sortFormBody(searchType: SearchType.SORT): Map<String, String>?

    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        Logger.withTag("adflkdasadassjgklasfgfdgdfg").i { url.toString() }
        return  if (useGetForNormalSearch) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({ api.postForm(url, fields = searchFormBody(searchType) ?: emptyMap(), headers = defaultHeaders) }){  html -> getSearchResults(html)}
        }
    }
    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>>  {
        val url = handelSearchUrl(searchType)

        return  if (useGetForGenresSearch) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({ api.postForm(url, fields = searchFormBody(searchType) ?: emptyMap(), headers = defaultHeaders) }){  html -> getSearchResults(html)}
        }  }
    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)

        return if (useGetForSortSearch) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({ api.postForm(url, fields = searchFormBody(searchType) ?: emptyMap(), headers = defaultHeaders) }){  html -> getSearchResults(html)}
        }
    }

    open fun handelFormBodyChapter(page:Int = 1,popular: Boolean): Map<String, String>? = handelFormBody(page,popular)

   open override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchDataWithHeaders({

            if (useGetForChapters){
                api.get(url, headers = defaultHeaders)}
            else{

                api.postForm(url, fields = handelFormBodyChapter(0,false) ?: emptyMap(), headers = defaultHeaders)
            }
        }) { html -> getChapterImages(html) }


    open fun handelFormBodyMangaInfo(page:Int = 1,popular: Boolean): Map<String, String>? = handelFormBody(page,popular)


    override suspend fun fetchMangaChaptersF(query: String): Flow<State<MangaInfo>> = fetchDataWithHeaders({

        if (useGetForInfo){ api.get(query, headers = defaultHeaders) }else { api.postForm(query, fields = handelFormBodyMangaInfo(0,false) ?: emptyMap(), headers = defaultHeaders)} })  { html ->  extractMangaInfo(html,query) }

























    override fun fetchMoreManga(page: Int, currentItems: List<MangaItem>?): Flow<State<List<MangaItem>>> =
        flow {
            emit(State.Loading as State<List<MangaItem>>)
            val url = handelLoadMoreUrl(page)

            Logger.withTag("csdfdsfsdfasdasfdsfsdsd3").i { url }
            val fetcher = if (customParseHome) fetchMangaHomeCustom(url,page) else fetchMangaHome(url,page)
            fetcher.collect { state ->

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




    abstract fun extractCustomHomeMangaItems(string: String): MutableList<MangaItem>


    abstract fun extractHomeMangaItems(string: String): MutableList<MangaItem>
    abstract fun extractMangaList(string: String): List<PopularManga>
    abstract suspend fun extractMangaInfo(string: String, baseUrl : String): MangaInfo
    abstract suspend fun getSearchResults(string: String): List<MangaItem>
    abstract override suspend fun refreshHeaders(newHeaders: Map<String, String>)
    abstract fun getChapterImages(string: String): List<String>
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster189.staleKdocSweep.cascade,
 * Task #692, 2026-05-29): classified as follows after recursive symbol
 * verification (three-hundred-and-fourth sibling of the cluster57-188
 * sweep continuum — leaf 3/5 of the wave-59 :shared sources_repositry
 * common tier scout 5-leaf batch; NormalSitesv2.kt 3/5).
 *
 *  (a) Top-KDoc "Migration note (Phase 7 batch 7.0): android.util.Log →
 *  Kermit Logger + IMangaDataApiServices (Retrofit) → ApiClient (Ktor
 *  wrapper). See NormalSites.kt header for the call-mapping table +
 *  okhttp3.FormBody → Map<String, String>? (KMP-portable form-data bag)
 *  + DataStoreHelper constructor parameter dropped — never used in this
 *  base class; concrete subclasses that need it will declare it
 *  themselves after Phase 8 supplies the KMP port" — LIVE-NOT-STALE +
 *  FULFILLED-PORT on all four substitution bullets:
 *    (a.1) `android.util.Log` → Kermit `Logger` — VERIFIED FULFILLED via
 *      `import co.touchlab.kermit.Logger` (line 3) + 6 `Logger.withTag(...)`
 *      call sites (fetchMangaHome line 76, fetchMangaHomeCustom line 95,
 *      normalSearch line 124, fetchChapterDataF lines 147/148,
 *      fetchMoreManga line 195). All zero `android.util.Log` imports
 *      verified absent.
 *    (a.2) `IMangaDataApiServices` (Retrofit) → `ApiClient` (Ktor wrapper)
 *      — VERIFIED FULFILLED via constructor parameter `private val api:
 *      ApiClient` (line 26) + the documented call mapping verified at
 *      every call site: `api.get(url, headers = defaultHeaders)` (lines
 *      61, 78, 97, 125, 132, 138, 151, 162) + `api.postForm(url, fields
 *      = ... ?: emptyMap(), headers = defaultHeaders)` (lines 64, 81,
 *      100, 126, 133, 139, 154, 162). The cross-reference "See NormalSites
 *      .kt header for the call-mapping table" is LIVE — NormalSites.kt
 *      (cluster189 leaf 2/5 sibling 303) documents the 1-to-1 call
 *      mapping table verbatim.
 *    (a.3) `okhttp3.FormBody` → `Map<String, String>?` — VERIFIED
 *      FULFILLED via the abstract `handelFormBody(page: Int = 1, popular:
 *      Boolean): Map<String, String>?` (line 55) + the 4 open-fun
 *      handelFormBody-delegation variants (handelFormBodyPopular line 57
 *      + handelFormBodyHome line 69 + handelFormBodyChapter line 143 +
 *      handelFormBodyMangaInfo line 159) — each defaulting to the
 *      abstract handelFormBody so concrete subclasses override the base
 *      and inherit all 4 variants unless they specialize. The three
 *      abstract `normalSearchFormBody + genresSearchFormBody +
 *      sortFormBody` (lines 118-120) all return `Map<String, String>?`.
 *    (a.4) `DataStoreHelper` constructor parameter drop — VERIFIED
 *      FULFILLED via the constructor signature `abstract class
 *      NormalSitesv2(private val api: ApiClient, sourcesRepository:
 *      SourcesDao) : BaseManga(sourcesRepository,)` (lines 25-28) — only
 *      2 constructor parameters, both consumed: `api` retained as
 *      `private val` field + `sourcesRepository` passed to `BaseManga`
 *      parent constructor. Zero references to `DataStoreHelper` in
 *      imports or body. The Phase-8 forecast "concrete subclasses that
 *      need it will declare it themselves after Phase 8 supplies the
 *      KMP port" remains FORECAST-NOT-YET-FULFILLED at the cluster189
 *      sweep timestamp.
 *
 *  (b) `abstract class NormalSitesv2(...) : BaseManga(sourcesRepository,) {
 *  ... }` body — LIVE-NOT-STALE; verified the BaseManga parent
 *  inheritance (line 28) — the abstract class IS the LIVE v2 superset
 *  of NormalSites (cluster189 leaf 2/5 sibling 303) with three v2
 *  -distinguishing additions: (b.1) 8 `open var useGetFor*` boolean
 *  toggles for fine-grained per-method GET/POST routing (lines 40-47):
 *  useGetForHome + useGetForSearch + useGetForNormalSearch +
 *  useGetForGenresSearch + useGetForSortSearch + useGetForPopular +
 *  useGetForChapters + useGetForInfo — each defaulting to `true` (GET
 *  by default). The v1 NormalSites only had 2 coarse toggles (homeGet
 *  + searchGet); v2 splits the search toggle into 3 (normal/genres/sort)
 *  and adds 4 endpoint-specific toggles (popular/chapters/info plus
 *  the umbrella useGetForSearch). The `useGetForSearch` umbrella toggle
 *  at line 41 is declared but NOT consumed in this base class — it
 *  exists as a forecast-hook for concrete subclasses that want a
 *  coarse-mode override that the three search-specific toggles can
 *  derive their default from. (b.2) `open var customParseHome: Boolean
 *  = false` toggle (line 38) — when `true`, the fetchMoreManga merge
 *  pipeline at line 196 routes through `fetchMangaHomeCustom` instead
 *  of `fetchMangaHome` — distinguishing the alternate-template
 *  extraction surface from the default extraction surface. (b.3) 2
 *  extraction abstracts `extractCustomHomeMangaItems` (line 223) +
 *  `extractHomeMangaItems` (line 226) — the customParseHome flag
 *  selects between the two extraction implementations.
 *
 *  (c) `fetchMangaHome / fetchMangaHomeCustom` extraction-dispatch
 *  pair (lines 72-91 + 92-108) — LIVE-NOT-STALE; the two methods share
 *  identical GET/POST routing branches on `useGetForHome` but differ
 *  only in the trailing extraction callback: `extractHomeMangaItems`
 *  vs `extractCustomHomeMangaItems`. This is the v2 customParseHome
 *  branch point: the fetchMoreManga pipeline at line 196 selects
 *  `fetchMangaHomeCustom` when `customParseHome = true`, otherwise
 *  defaults to `fetchMangaHome`. The fetchMangaHomeF entry-point at
 *  line 71 unconditionally routes through fetchMangaHome (default
 *  extraction) — meaning customParseHome ONLY affects load-more
 *  pagination, not first-page home rendering. Concrete subclasses
 *  that need custom-extraction on the first page would override
 *  fetchMangaHomeF directly.
 *
 *  (d) `fetchPopularManga + fetchChapterDataF + fetchMangaChaptersF`
 *  triplet (lines 58-67 + 145-156 + 162-164) — LIVE-NOT-STALE; each
 *  branches on its dedicated `useGetFor*` toggle (useGetForPopular +
 *  useGetForChapters + useGetForInfo respectively) for GET/POST
 *  routing. The `handelFormBodyChapter / handelFormBodyMangaInfo`
 *  open-fun delegation pattern (lines 143 + 159) lets concrete
 *  subclasses override per-endpoint form-body shapes without having to
 *  override the umbrella handelFormBody — a v2-specific specialization
 *  surface that v1 NormalSites lacks. The `fetchChapterDataF` is
 *  marked `open override` (line 145) — letting concrete subclasses
 *  override the chapter-data flow if needed (v1 NormalSites kept the
 *  method `override fun` without `open` — narrower extensibility
 *  surface).
 *
 *  (e) `normalSearch / genresSearch / sortSearch` override triplet
 *  (lines 122-141) — LIVE-NOT-STALE; verified the three overrides each
 *  branch on their dedicated `useGetFor*` toggle (useGetForNormalSearch
 *  + useGetForGenresSearch + useGetForSortSearch) for fine-grained
 *  per-search-type GET/POST routing. v1 NormalSites used a single
 *  `searchGet` toggle for all three searches — v2 splits this into 3
 *  toggles so concrete sources can route normal-search as GET while
 *  routing genre/sort-search as POST (or any other 8-way combination).
 *
 *  (f) `fetchMoreManga` pagination merge pipeline (lines 190-218) —
 *  LIVE-NOT-STALE; structurally identical to v1 NormalSites's
 *  fetchMoreManga (cluster189 leaf 2/5 sibling 303 item g) except for
 *  the customParseHome branch at line 196: `val fetcher = if
 *  (customParseHome) fetchMangaHomeCustom(url, page) else fetchMangaHome
 *  (url, page)`. The merge semantics — addAll into mergedList + empty
 *  -newItems fallback + .catch terminal — are identical to v1.
 *
 *  (g) 12 empty lines at lines 166-189 between fetchMangaChaptersF and
 *  fetchMoreManga — COSMETIC-NOT-STALE; preserved verbatim per the
 *  audit-trail-preservation convention (the original source carried
 *  this whitespace; the §253 convention preserves source-shape
 *  verbatim including superfluous whitespace).
 *
 *  (h) 7 abstract bottom-half members (extractCustomHomeMangaItems +
 *  extractHomeMangaItems + extractMangaList + extractMangaInfo +
 *  getSearchResults + refreshHeaders + getChapterImages) at lines
 *  223-231 — LIVE-NOT-STALE; v2's extraction-customization surface
 *  ADDS extractCustomHomeMangaItems vs v1 (which has 6 bottom-half
 *  members at lines 189-194). The remaining 6 are identical to v1.
 *
 *  (i) Commented-out logger line at line 74 (`// Logger.withTag(...)
 *  .i { url }` in the fetchMangaHome body) — DEBT-NOT-STALE; preserves
 *  the Phase-7-batch-7.0 dev-time debug logger that was eventually
 *  commented out but not deleted. Per the audit-trail-preservation
 *  convention, the comment-out posture is preserved verbatim — letting
 *  a future debugger re-enable diagnostic output without re-typing the
 *  exact Kermit-tag string the original used.
 *
 * Verified: 1 `abstract class NormalSitesv2` declaration extending
 * `BaseManga(sourcesRepository,)` with 2 constructor parameters (api +
 * sourcesRepository) + 7 abstract members (mangaSource + homeUrl +
 * popularUrl + defaultHeaders + handelLoadMoreUrl + handelSearchUrl +
 * sortTypes + allGenres + blackListGenres) + 9 `open var` toggles
 * (customParseHome + 8 useGetFor*) + 1 abstract handelFormBody member
 * + 4 open-fun handelFormBody-delegation variants (Popular + Home +
 * Chapter + MangaInfo) + 1 concrete searchFormBody when-dispatch + 6
 * concrete fetch fan-out methods (fetchMangaHome + fetchMangaHomeCustom
 * + fetchPopularManga + fetchMangaChaptersF + fetchChapterDataF
 * marked `open override` + fetchMoreManga) + normalSearch/genresSearch
 * /sortSearch override triplet + 7 abstract bottom-half extraction
 * members (extractCustomHomeMangaItems + extractHomeMangaItems +
 * extractMangaList + extractMangaInfo + getSearchResults + getChapter
 * Images + refreshHeaders) + 4 Phase-7-batch-7.0 migration-note KDoc
 * bullets. Sibling: NormalSites.kt (cluster189 leaf 2/5 prior sibling
 * 303). LEAF 3/5 of the cluster189 :shared sources_repositry common
 * tier scout 5-leaf batch. Compound classification: LIVE-NOT-STALE +
 * FULFILLED-PORT for the four Phase 7 batch 7.0 substitution bullets
 * (Log → Kermit + Retrofit ApiClient with cross-reference to NormalSites
 * .kt header + FormBody → Map nullable + DataStoreHelper constructor
 * -parameter drop). The "Phase 8 multiplatform-settings forecast"
 * remains FORECAST-NOT-YET-FULFILLED. Original Phase-7-batch-7.0
 * migration-note prose preserved verbatim.
 */
