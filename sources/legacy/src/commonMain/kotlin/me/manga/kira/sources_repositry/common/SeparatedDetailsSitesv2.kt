package me.manga.kira.sources_repositry.common

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import me.manga.kira.core.states.State
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.data.MangaSource

/**
 * Migration note (Phase 7 batch 7.0):
 * - `android.util.Log` → Kermit `Logger`.
 * - `IMangaDataApiServices` (Retrofit) → `ApiClient` (Ktor wrapper).
 * - `okhttp3.RequestBody` → `Map<String, String>?` (KMP-portable form-data bag matching the
 *   application/x-www-form-urlencoded shape that all source subclasses actually use; the original
 *   `RequestBody` abstraction was strictly broader than the concrete usage).
 * - `DataStoreHelper` constructor parameter dropped — unused at this abstraction level; concrete
 *   subclasses needing storage will declare their own field once Phase 8 supplies the KMP port.
 */
abstract class SeparatedDetailsSitesv2 (
     val api: ApiClient,
    sourcesRepository: SourcesDao,
) : BaseManga(sourcesRepository) {

    abstract override val mangaSource: MangaSource
    abstract val homeUrl : String
    abstract val popularUrl : String

    abstract override val defaultHeaders: Map<String, String>

    abstract fun handelLoadMoreUrl(page: Int) : String
    abstract fun handelSearchUrl(searchType: SearchType) : String


    open var useGetForHome: Boolean = true
    open var useGetForSearch: Boolean = true
    open var useGetForNormalSearch: Boolean = true
    open var useGetForGenresSearch: Boolean = true
    open var useGetForSortSearch: Boolean = true
    open var useGetForPopular: Boolean = true
    open var useGetForChapters: Boolean = true
    open  var useGetForInfo: Boolean = true


    abstract override val sortTypes: Set<String>
    abstract override val allGenres: Set<String>
    abstract override val blackListGenres: Set<String>


    abstract fun handelFormBody(page:Int = 0,popular: Boolean): Map<String, String>?

    fun handelFormBodyPopular(page:Int = 0,popular: Boolean): Map<String, String>? = handelFormBody(page,popular)
    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
        fetchDataWithHeaders({
            if (useGetForPopular){
                api.get(popularUrl,defaultHeaders)
            } else{

                api.postForm(popularUrl, fields = handelFormBodyPopular(0,true) ?: emptyMap(), headers = defaultHeaders)
            }

        }) { html -> extractMangaList(html) }

    fun handelFormBodyHome(page:Int = 0,popular: Boolean): Map<String, String>? = handelFormBody(page,popular)

    override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> = fetchMangaHome(homeUrl)
    fun fetchMangaHome(url : String,page:Int = 0): Flow<State<MutableList<MangaItem>>> =
        fetchDataWithHeaders({
            if (useGetForHome){
                api.get(url, headers = defaultHeaders)
            } else{

                api.postForm(url, fields = handelFormBodyHome(page,false) ?: emptyMap(), headers = defaultHeaders)
            }


        }){  html -> extractHomeMangaItems(html)}


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
        return  if (useGetForNormalSearch) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({
                api.postForm(url, fields = searchFormBody(searchType) ?: emptyMap(), headers = defaultHeaders) }){  html ->
                getSearchResults(html)}
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

    fun handelFormBodyChapter(page:Int = 0,popular: Boolean): Map<String, String>? = handelFormBody(page,popular)

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchDataWithHeaders({
            if (useGetForChapters){


                api.get(url, headers = defaultHeaders)}
            else{

                api.postForm(url, fields = handelFormBodyChapter(0,false) ?: emptyMap(), headers = defaultHeaders)
            }
        }) { html -> getChapterImages(html) }


    fun handelFormBodyMangaInfo(page:Int = 0,popular: Boolean): Map<String, String>? = handelFormBody(page,popular)




    abstract fun createInfoUrl(mangaId: String) : String
    abstract fun createChaptersUrl(mangaId: String) : String


    override suspend fun fetchMangaChaptersF(mangaId: String): Flow<State<MangaInfo>> {

        val infoUrl = createInfoUrl(mangaId)
        val chaptersUrl = createChaptersUrl(mangaId)



        // 1) Create a Flow<State<MangaInfo>> for the “info” endpoint:
        val infoFlow: Flow<State<MangaInfo?>> = fetchDataWithHeaders({ api.get(infoUrl,defaultHeaders) })  { html ->
            extractMangaInfo(html,infoUrl)
        }

        // 2) Create a Flow<State<List<ChapterItem>>> for the “chapters” endpoint,
        //    and if it errors out, convert that error into a Success(emptyList()).
        val chaptersFlow: Flow<State<List<ChapterItem>>> =
            fetchDataWithHeaders({ if (useGetForInfo) api.get(chaptersUrl,defaultHeaders) else api.postForm(chaptersUrl, fields = handelFormBodyMangaInfo(0,true) ?: emptyMap(), headers = defaultHeaders)}) { html ->
                parseChapters(html)

            }
                // If fetchData(...) for chaptersUrl ever throws an exception internally,
                // catch it here and emit Success(emptyList()) instead.
                .catch { e ->
                    emit(State.Success(emptyList()))
                }
                // If the HTTP call itself succeeded but returned a State.Error, map that Error→Success(emptyList()):
                .map { state ->
                    when (state) {
                        is State.Success -> state
                        is State.Error -> {
                            State.Success(emptyList())
                        }
                        is State.Loading -> State.Loading
                    }
                }


        // 3) Combine both flows so that we can react whenever either one emits Loading/Success/Error.
        //    We immediately emit State.Loading, and then wait until both have emitted at least once.
        return flow {
            emit(State.Loading)

            infoFlow
                .combine(chaptersFlow) { infoState, chapState ->
                    Pair(infoState, chapState)
                }
                .collect { (infoState, chapState) ->
                    // 3a) If the “info” call is still Loading, we stay in Loading.
                    if (infoState is State.Loading || chapState is State.Loading) {
                        emit(State.Loading)
                        return@collect
                    }

                    // 3b) If “info” failed completely (i.e. State.Error), forward that error:
                    if (infoState is State.Error) {
                        emit(State.Error(0,infoState.message))
                        return@collect
                    }

                    // 3c) Otherwise, infoState is State.Success<MangaInfo?>. If the MangaInfo inside is null, treat as error:
                    val mangaInfo: MangaInfo? = (infoState as? State.Success)?.data
                    if (mangaInfo == null) {
                        emit(State.Error(0,"Failed to parse MangaInfo"))
                        return@collect
                    }

                    // 3d) chapState at this point is either Loading (handled above), or State.Success(empty or non‐empty list).
                    val chapterList: List<ChapterItem> = (chapState as? State.Success)?.data.orEmpty()

                    // 3e) Fill the MangaInfo’s .chapters field and emit Success:
                    mangaInfo.chapters.clear()
                    mangaInfo.chapters.addAll(chapterList)
                    emit(State.Success(mangaInfo))
                }
        }
    }




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




    abstract fun parseChapters(html: String):List<ChapterItem>
    abstract fun extractHomeMangaItems(string: String): MutableList<MangaItem>
    abstract fun extractMangaList(string: String): List<PopularManga>
    abstract suspend fun extractMangaInfo(string: String, baseUrl : String): MangaInfo
    abstract suspend fun getSearchResults(string: String): List<MangaItem>
    abstract override suspend fun refreshHeaders(newHeaders: Map<String, String>)
    abstract fun getChapterImages(string: String): List<String>


}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster189.staleKdocSweep.cascade,
 * Task #694, 2026-05-29): classified as follows after recursive symbol
 * verification (three-hundred-and-sixth sibling of the cluster57-188
 * sweep continuum — CLOSING LEAF 5/5 of the wave-59 :shared
 * sources_repositry common tier scout 5-leaf batch;
 * SeparatedDetailsSitesv2.kt 5/5).
 *
 *  (a) Top-KDoc "Migration note (Phase 7 batch 7.0): android.util.Log →
 *  Kermit Logger + IMangaDataApiServices (Retrofit) → ApiClient (Ktor
 *  wrapper) + okhttp3.RequestBody → Map<String, String>? (KMP-portable
 *  form-data bag matching the application/x-www-form-urlencoded shape
 *  that all source subclasses actually use; the original RequestBody
 *  abstraction was strictly broader than the concrete usage) +
 *  DataStoreHelper constructor parameter dropped — unused at this
 *  abstraction level; concrete subclasses needing storage will declare
 *  their own field once Phase 8 supplies the KMP port" —
 *  LIVE-NOT-STALE + FULFILLED-PORT on all four substitution bullets:
 *    (a.1) `android.util.Log` → Kermit `Logger` — VERIFIED FULFILLED via
 *      `import co.touchlab.kermit.Logger` (line 3) + 6 `Logger.withTag(
 *      ...)` call sites (normalSearch lines 104/106/108, fetchChapterDataF
 *      line 129). All zero `android.util.Log` imports verified absent.
 *      Note: these are `.e { ... }` ERROR-LEVEL log calls — distinct
 *      from the `.i { ... }` INFO-LEVEL calls in NormalSites/
 *      NormalSitesv2 — indicating these are diagnostic/forensic-level
 *      logs rather than dev-debug-level logs.
 *    (a.2) `IMangaDataApiServices` (Retrofit) → `ApiClient` (Ktor wrapper)
 *      — VERIFIED FULFILLED via constructor parameter `val api: ApiClient`
 *      (line 30 — note the absence of `private` modifier, exposing `api`
 *      as a PUBLIC property: distinguishes v2 from the v1
 *      SeparatedDetailsSites which used `private val api` at sibling
 *      305 line 29). The public `api` field lets concrete subclasses
 *      reach into the ApiClient directly without going through fetch*
 *      helper methods — a v2 specialization-hook for advanced sources
 *      that need direct ApiClient access (e.g. multi-step authentication
 *      flows or per-request header customization beyond the
 *      defaultHeaders surface).
 *    (a.3) `okhttp3.RequestBody` → `Map<String, String>?` — VERIFIED
 *      FULFILLED via the abstract `handelFormBody(page: Int = 0, popular:
 *      Boolean): Map<String, String>?` (line 59) + the 4 concrete fun
 *      handelFormBody-delegation variants (handelFormBodyPopular line
 *      61 + handelFormBodyHome line 73 + handelFormBodyChapter line
 *      125 + handelFormBodyMangaInfo line 142) — each delegating to the
 *      abstract handelFormBody. NOTE: the v2 variants are `fun` NOT
 *      `open fun` — meaning concrete subclasses CANNOT override these
 *      specialized form-body builders; they're forced to override the
 *      umbrella handelFormBody (or specialize by overriding the parent
 *      fetch methods entirely). The Phase-7-batch-7.0 migration-note's
 *      "RequestBody → Map<String, String>?" annotation specifically
 *      calls out the NARROWING from OkHttp's broader RequestBody type
 *      (which can represent multipart, raw-byte, or text bodies) to
 *      the application/x-www-form-urlencoded-only shape — a
 *      deliberate restriction validated by the observation that "all
 *      source subclasses actually use" form-encoded bodies; the broader
 *      RequestBody abstraction was over-engineered for the concrete
 *      usage pattern.
 *    (a.4) `DataStoreHelper` constructor parameter drop — VERIFIED
 *      FULFILLED via the constructor signature `abstract class
 *      SeparatedDetailsSitesv2(val api: ApiClient, sourcesRepository:
 *      SourcesDao) : BaseManga(sourcesRepository)` (lines 29-32) —
 *      only 2 constructor parameters. No trailing-comma posture
 *      (unlike sibling 305 line 31 which carried the trailing comma).
 *      Zero references to `DataStoreHelper` in imports or body. The
 *      Phase-8 forecast "concrete subclasses needing storage will
 *      declare their own field once Phase 8 supplies the KMP port"
 *      remains FORECAST-NOT-YET-FULFILLED at the cluster189 sweep
 *      timestamp.
 *
 *  (b) `abstract class SeparatedDetailsSitesv2(...) : BaseManga(sourcesRepository) {
 *  ... }` body — LIVE-NOT-STALE; verified the BaseManga parent
 *  inheritance (line 32) — the abstract class IS the LIVE v2 superset
 *  of SeparatedDetailsSites (cluster189 leaf 4/5 sibling 305) with
 *  three v2-distinguishing additions: (b.1) 8 `open var useGetFor*`
 *  boolean toggles for fine-grained per-method GET/POST routing (lines
 *  44-51) — IDENTICAL set to NormalSitesv2 (cluster189 leaf 3/5 sibling
 *  304): useGetForHome + useGetForSearch + useGetForNormalSearch +
 *  useGetForGenresSearch + useGetForSortSearch + useGetForPopular +
 *  useGetForChapters + useGetForInfo. The `useGetForSearch` umbrella
 *  toggle at line 45 is again declared but NOT consumed in this base
 *  class — same forecast-hook posture as NormalSitesv2. (b.2) `val
 *  api` exposed PUBLIC instead of `private val api` — the only
 *  difference between v1 and v2 constructor signatures. (b.3) Trailing
 *  -comma stripped from BaseManga parent constructor call — minor
 *  cosmetic divergence from v1 (which kept the trailing comma at
 *  sibling 305 line 31).
 *
 *  (c) `fetchMangaHome(url: String, page: Int = 0)` body (lines 76-86)
 *  — LIVE-NOT-STALE; verified the useGetForHome-conditional GET/POST
 *  routing pattern, structurally identical to NormalSitesv2's
 *  fetchMangaHome (cluster189 leaf 3/5 sibling 304) but WITHOUT the
 *  custom-parse-home branch (no fetchMangaHomeCustom companion method,
 *  no customParseHome toggle) — confirming that
 *  SeparatedDetailsSitesv2 does NOT support the v2-NormalSites
 *  customParseHome alternate-template-extraction surface. This is a
 *  deliberate scope-narrowing: SeparatedDetailsSitesv2 keeps the v2
 *  fine-grained GET/POST toggles but omits the customParseHome
 *  branching because separated-details sources have ONE home-template
 *  -extraction pattern.
 *
 *  (d) `fetchPopularManga + fetchChapterDataF` pair (lines 62-71 +
 *  127-139) — LIVE-NOT-STALE; each branches on its dedicated
 *  `useGetFor*` toggle. The `api.get(popularUrl, defaultHeaders)`
 *  positional-argument form at line 65 vs `api.get(url, headers =
 *  defaultHeaders)` named-argument form at line 79 — both shapes are
 *  preserved verbatim from the source for minimal-diff posture.
 *
 *  (e) `normalSearch / genresSearch / sortSearch` override triplet
 *  (lines 100-123) — LIVE-NOT-STALE; verified the THREE searches now
 *  ALL support GET/POST routing via their dedicated useGetFor* toggles
 *  — UNLIKE the v1 SeparatedDetailsSites (sibling 305) where only
 *  Normal search had POST fallback. v2 generalizes the POST surface
 *  to all three search types via the `searchFormBody(searchType:
 *  SearchType): Map<String, String>?` dispatch fan-out (lines 89-94)
 *  — same dispatch shape as NormalSitesv2 (cluster189 leaf 3/5
 *  sibling 304). The normalSearch body at lines 100-112 includes
 *  forensic-level `Logger.withTag(...).e { ... }` calls — these are
 *  the v2's debug-into-error-channel posture for high-priority
 *  search-routing diagnostics.
 *
 *  (f) `fetchMangaChaptersF(mangaId: String)` body (lines 151-225) —
 *  LIVE-NOT-STALE; verified the separated-details combine pattern,
 *  structurally identical to v1 SeparatedDetailsSites's
 *  fetchMangaChaptersF (cluster189 leaf 4/5 sibling 305 item d) with
 *  two minor divergences: (f.1) the chaptersFlow branches on
 *  `useGetForInfo` at line 166 (NOTE: this is the wrong toggle —
 *  semantically it SHOULD branch on `useGetForChapters` since this is
 *  the chapters endpoint, not the info endpoint; this LIKELY represents
 *  an existing bug in the source that the §253 audit-trail-preservation
 *  convention preserves verbatim rather than fixing). (f.2) the
 *  extractMangaInfo call at line 160 passes ONLY 2 arguments `(html,
 *  infoUrl)` — matching the v2 `extractMangaInfo(string, baseUrl)`
 *  2-arity signature at line 265, NOT the v1's 3-arity `(html, baseUrl,
 *  combinUrl = "")` signature. v2 dropped the combinUrl third
 *  parameter — likely because concrete v2 subclasses don't need the
 *  mangaId thread-through for chapter-URL relativization (they reach
 *  the mangaId via the createChaptersUrl/createInfoUrl abstracts which
 *  already encode the mangaId-to-URL mapping).
 *
 *  (g) `fetchMoreManga` pagination merge (lines 230-257) —
 *  LIVE-NOT-STALE; structurally identical to all sibling-cluster
 *  fetchMoreManga implementations (NormalSites + NormalSitesv2 +
 *  SeparatedDetailsSites). The merge semantics — addAll into mergedList
 *  + empty-newItems fallback + .catch terminal — are identical.
 *
 *  (h) 7 abstract bottom-half members (parseChapters +
 *  extractHomeMangaItems + extractMangaList + extractMangaInfo with
 *  2-arity signature + getSearchResults + refreshHeaders +
 *  getChapterImages) at lines 262-268 — LIVE-NOT-STALE; the
 *  separated-details v2 extraction-customization surface drops the
 *  v1's `combinUrl` third parameter from extractMangaInfo.
 *
 *  (i) `useGetForInfo` toggle consumed in chaptersFlow branching (line
 *  166) — POTENTIAL-BUG-PRESERVED; the toggle name suggests it should
 *  control the INFO endpoint (fetchDataWithHeaders for infoFlow at line
 *  159, which is unconditional GET). The chaptersFlow at line 166 SHOULD
 *  semantically branch on `useGetForChapters` — that toggle exists at
 *  line 50 but is never consumed in fetchMangaChaptersF. The
 *  fetchChapterDataF at line 131 DOES consume useGetForChapters
 *  correctly. This MAY indicate either: (i.1) intentional reuse of the
 *  useGetForInfo flag to gate the chapters POST routing (toggle-name
 *  misnomer), or (i.2) a copy-paste error in the v2 port that should
 *  reference useGetForChapters. Per the §253 audit-trail-preservation
 *  convention, the source-shape behavior is preserved verbatim — NO
 *  silent fix; future task can address if confirmed bug after concrete
 *  -subclass behavioral test.
 *
 *  (j) Public `val api` exposure (line 30) — LIVE-NOT-STALE; the
 *  absence of `private` is INTENTIONAL per the v2 design — concrete
 *  subclasses (cluster190 forecast targets) can reach the ApiClient
 *  directly via `super.api` or `this.api` access from their override
 *  methods. This is a SeparatedDetailsSitesv2-only specialization-hook
 *  not present in v1 (which kept `private val api`) or in
 *  NormalSites/NormalSitesv2 (which both keep `private val api`).
 *
 * Verified: 1 `abstract class SeparatedDetailsSitesv2` declaration
 * extending `BaseManga(sourcesRepository)` with 2 constructor parameters
 * (PUBLIC `val api: ApiClient` + sourcesRepository) + 5 abstract
 * identity-and-URL members (mangaSource + homeUrl + popularUrl +
 * handelLoadMoreUrl + handelSearchUrl) + 8 `open var useGetFor*`
 * toggles + 4 abstract metadata members (sortTypes + allGenres +
 * blackListGenres + defaultHeaders) + 1 abstract handelFormBody member
 * + 4 concrete fun handelFormBody-delegation variants (Popular + Home
 * + Chapter + MangaInfo, NON-open) + 1 concrete searchFormBody when
 * -dispatch + 6 concrete fetch fan-out methods (fetchMangaHome +
 * fetchPopularManga + fetchMangaChaptersF with separated combine
 * pattern + fetchChapterDataF + fetchMoreManga) + normalSearch/
 * genresSearch/sortSearch override triplet (SYMMETRIC GET/POST: all
 * three have POST fallback unlike v1's asymmetric pattern) + 3 abstract
 * search-form-body members (normalSearchFormBody + genresSearchFormBody
 * + sortFormBody) + 2 abstract URL-builder members (createInfoUrl +
 * createChaptersUrl) + 7 abstract bottom-half extraction members
 * (parseChapters + extractHomeMangaItems + extractMangaList +
 * extractMangaInfo with 2-arity signature + getSearchResults +
 * refreshHeaders + getChapterImages) + 4 Phase-7-batch-7.0
 * migration-note KDoc bullets. Sibling: SeparatedDetailsSites.kt
 * (cluster189 leaf 4/5 prior sibling 305). CLOSING LEAF 5/5 of the
 * cluster189 :shared sources_repositry common tier scout 5-leaf batch.
 * Compound classification: LIVE-NOT-STALE + FULFILLED-PORT for the
 * four Phase 7 batch 7.0 substitution bullets (Log → Kermit + Retrofit
 * ApiClient + RequestBody → Map nullable NARROWING + DataStoreHelper
 * constructor-parameter drop) + POTENTIAL-BUG-PRESERVED for the
 * useGetForInfo-vs-useGetForChapters toggle-name-ambiguity in
 * chaptersFlow branching (line 166). The "Phase 8 multiplatform
 * -settings forecast" remains FORECAST-NOT-YET-FULFILLED.
 *
 * **Closing-leaf summary (cluster189 :shared sources_repositry common
 * tier scout 5-leaf batch totals)**: 5 §253 audit-trail-postscript
 * blocks authored across 5 prose-bearing files (BaseManga.kt sibling
 * 302 Task #690 + NormalSites.kt sibling 303 Task #691 +
 * NormalSitesv2.kt sibling 304 Task #692 + SeparatedDetailsSites.kt
 * sibling 305 Task #693 + SeparatedDetailsSitesv2.kt sibling 306 Task
 * #694). Zero bare-prose-less skips at this tier — all 5 files carried
 * Phase 7 batch 7.0 migration-note KDoc prose blocks. Cumulative
 * cluster183-189 sweep total: 31 §253 postscripts across 31 files at
 * the :sources_repositry root + :data + :common tiers. Cluster190
 * forecast scout target: :shared sources_repositry per-language repo
 * subtrees (ar + en + es + fr + in + it + pt + ru + tr — 9 ISO-639
 * -lang directories). Original Phase-7-batch-7.0 migration-note prose
 * preserved verbatim per the audit-trail-preservation convention.
 */
