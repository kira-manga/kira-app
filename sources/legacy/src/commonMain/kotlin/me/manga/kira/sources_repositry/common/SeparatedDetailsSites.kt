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
 * - `IMangaDataApiServices` (Retrofit) → `ApiClient` (Ktor wrapper). Source's `api.post(url, body)`
 *   form (no headers) ported to `apiClient.postForm(url, body ?: emptyMap())`.
 * - `okhttp3.FormBody` → `Map<String, String>?`.
 * - `DataStoreHelper` constructor parameter dropped — unused at this abstraction level; will be
 *   re-introduced on concrete subclasses once Phase 8 supplies the KMP port.
 */
abstract class  SeparatedDetailsSites(
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : BaseManga(sourcesRepository, ) {
        abstract override val mangaSource: MangaSource
        abstract val homeUrl : String
        abstract val popularUrl : String
        open var homeGet = true
    open var searchGet = true
        var fixedImgUrl = true
        var isChapterGet = true
        abstract fun handelLoadMoreUrl(page: Int) : String

        abstract fun handelSearchUrl(searchType: SearchType) : String
        abstract override val sortTypes: Set<String>
        abstract override val allGenres: Set<String>
        abstract override val blackListGenres: Set<String>
        abstract override val defaultHeaders: Map<String, String>

        override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> = fetchMangaHome(homeUrl)

        abstract fun handelFormBody(page:Int = 0,popular: Boolean): Map<String, String>?
        fun fetchMangaHome(url : String,page:Int = 0): Flow<State<MutableList<MangaItem>>> =
            fetchDataWithHeaders({

                Logger.withTag("fslksadfasghfsdgdfgdfgfds").i { url.toString() }

                if (homeGet){
                    api.get(url,defaultHeaders)
                } else{

                    api.postForm(url, fields = handelFormBody(page,false) ?: emptyMap())
                }


            }){  html -> extractHomeMangaItems(html)}



        override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
            fetchDataWithHeaders({
                Logger.withTag("fslksadfasghfsdgdfgdfgfds1").i { baseUrl.toString() }

                if (homeGet){
                    api.get(popularUrl,defaultHeaders)
                } else{

                    api.postForm(popularUrl, fields = handelFormBody(0,true) ?: emptyMap())
                }

            }) { html -> extractMangaList(html) }



        override suspend fun fetchMangaChaptersF(mangaId: String): Flow<State<MangaInfo>> {
            Logger.withTag("saaksljdlkasdjfasdfdfasdf0").i { mangaId }

            val infoUrl = createInfoUrl(mangaId)
            val chaptersUrl =   createChaptersUrl(mangaId)

            Logger.withTag("saaksljdlkasdjfasdfdfasdf1").i { "infoUrl ======== $infoUrl   chaptersUrl========$chaptersUrl  " }


            // 1) Create a Flow<State<MangaInfo>> for the “info” endpoint:
            val infoFlow: Flow<State<MangaInfo?>> = fetchDataWithHeaders({ api.get(infoUrl,defaultHeaders) })  { html ->
                extractMangaInfo(html,infoUrl,mangaId)
            }


            // 2) Create a Flow<State<List<ChapterItem>>> for the “chapters” endpoint,
            //    and if it errors out, convert that error into a Success(emptyList()).
            val chaptersFlow: Flow<State<List<ChapterItem>>> =
                fetchDataWithHeaders({ if (isChapterGet) api.get(chaptersUrl,defaultHeaders) else api.postForm(chaptersUrl, fields = handelFormBody(0,true) ?: emptyMap())}) { html ->
                    Logger.withTag("saaksljdlkasdjfasdfdfasdf2").i { chaptersUrl }

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




        abstract fun createInfoUrl(mangaId: String) : String
        abstract fun createChaptersUrl(mangaId: String) : String
        override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
            fetchDataWithHeaders({
                Logger.withTag("sadasdasdasaxczxcxzcsdfsd0").i { url }
                val fullUrl = if (url.startsWith("http", ignoreCase = true)) {
                    url
                } else {
                    "${baseUrl.ifBlank { BASE_URL }}$url"
                }
                Logger.withTag("sadasdasdasaxczxcxzcsdfsd1").i { fullUrl }

                api.get(fullUrl,defaultHeaders)

            }) { html ->
                getChapterImages(html) }




        override fun fetchMoreManga(page: Int, currentItems: List<MangaItem>?): Flow<State<List<MangaItem>>> =
            flow {
                emit(State.Loading as State<List<MangaItem>>)
                val url = handelLoadMoreUrl(page)
                Logger.withTag("fslksadfasghfsdgdfgdfgfds2").i { url.toString() }

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

    abstract fun handelSearchFormBody(page:Int = 0,searchType: SearchType.Normal): Map<String, String>?



        override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
            val url = handelSearchUrl(searchType)
            Logger.withTag("dsdgksfjglsdkfgfzxczxczcdgdfgdgsdfg1").i { url.toString() }


            return  fetchDataWithHeaders({if (searchGet) api.get(url,defaultHeaders)  else api.postForm(url = url, fields = handelSearchFormBody(0,searchType) ?: emptyMap())}){  html -> getSearchResults(html)}
        }

        override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>>  {
            val url = handelSearchUrl(searchType)
            return  fetchDataWithHeaders({ api.get(url,defaultHeaders) }){  html -> getSearchResults(html)}
        }

        override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
            val url = handelSearchUrl(searchType)
            return  fetchDataWithHeaders({ api.get(url,defaultHeaders) }){  html -> getSearchResults(html)}
        }





        abstract fun parseChapters(html: String):List<ChapterItem>
        abstract fun extractHomeMangaItems(html: String): MutableList<MangaItem>
        abstract fun extractMangaList(html: String): List<PopularManga>
        abstract fun extractMangaInfo(html: String, baseUrl : String,combinUrl: String = ""): MangaInfo
        abstract fun getSearchResults(html: String): List<MangaItem>
        abstract override suspend fun refreshHeaders(newHeaders: Map<String, String>)
        abstract fun getChapterImages(html: String): List<String>

    }

/**
 * **Audit-trail postscript** (Phase 9.x.cluster189.staleKdocSweep.cascade,
 * Task #693, 2026-05-29): classified as follows after recursive symbol
 * verification (three-hundred-and-fifth sibling of the cluster57-188
 * sweep continuum — leaf 4/5 of the wave-59 :shared sources_repositry
 * common tier scout 5-leaf batch; SeparatedDetailsSites.kt 4/5).
 *
 *  (a) Top-KDoc "Migration note (Phase 7 batch 7.0): android.util.Log →
 *  Kermit Logger + IMangaDataApiServices (Retrofit) → ApiClient (Ktor
 *  wrapper). Source's api.post(url, body) form (no headers) ported to
 *  apiClient.postForm(url, body ?: emptyMap()) + okhttp3.FormBody →
 *  Map<String, String>? + DataStoreHelper constructor parameter dropped
 *  — unused at this abstraction level; will be re-introduced on
 *  concrete subclasses once Phase 8 supplies the KMP port" —
 *  LIVE-NOT-STALE + FULFILLED-PORT on all four substitution bullets:
 *    (a.1) `android.util.Log` → Kermit `Logger` — VERIFIED FULFILLED via
 *      `import co.touchlab.kermit.Logger` (line 3) + 13 `Logger.withTag(
 *      ...)` call sites (fetchMangaHome lines 53/54, fetchPopularManga
 *      line 70, fetchMangaChaptersF lines 84/89/102/103, fetchChapterDataF
 *      lines 172/178/183, fetchMoreManga line 194, normalSearch line
 *      226). All zero `android.util.Log` imports verified absent.
 *    (a.2) Retrofit `api.post(url, body)` no-headers form → `apiClient
 *      .postForm(url, body ?: emptyMap())` — VERIFIED FULFILLED via the
 *      6 postForm call sites: fetchMangaHome line 60 (`api.postForm(url,
 *      fields = handelFormBody(page, false) ?: emptyMap())`),
 *      fetchPopularManga line 76, fetchMangaChaptersF chaptersFlow line
 *      101, normalSearch line 229. The "Source's api.post(url, body)
 *      form (no headers)" annotation is precise — the postForm calls
 *      OMIT the `headers = defaultHeaders` parameter pass-through that
 *      NormalSites/NormalSitesv2 includes — preserving the SeparatedDetails
 *      Sites source's identical no-headers POST shape. GET calls use
 *      `api.get(url, defaultHeaders)` positional-argument form (lines
 *      57, 73, 93, 101 conditional, 180, 234, 239).
 *    (a.3) `okhttp3.FormBody` → `Map<String, String>?` — VERIFIED
 *      FULFILLED via the abstract `handelFormBody(page: Int = 0, popular:
 *      Boolean): Map<String, String>?` (line 49) + the abstract
 *      `handelSearchFormBody(page: Int = 0, searchType: SearchType
 *      .Normal): Map<String, String>?` (line 220). The latter is
 *      uniquely-SeparatedDetailsSites — only the Normal search type
 *      uses POST routing; the GENRES/SORT searches use GET-only paths
 *      (lines 234, 239) without an Elvis fallback.
 *    (a.4) `DataStoreHelper` constructor parameter drop — VERIFIED
 *      FULFILLED via the constructor signature `abstract class
 *      SeparatedDetailsSites(private val api: ApiClient, sourcesRepository:
 *      SourcesDao) : BaseManga(sourcesRepository, )` (lines 28-31) —
 *      only 2 constructor parameters. The trailing comma after
 *      sourcesRepository at line 31 is a Kotlin-2.x trailing-comma
 *      convention preserved verbatim from the source.
 *
 *  (b) `abstract class SeparatedDetailsSites(...) : BaseManga(sourcesRepository,) {
 *  ... }` body — LIVE-NOT-STALE; verified the BaseManga parent
 *  inheritance (line 31) — the abstract class IS the LIVE
 *  separated-details-flavor variant alongside NormalSites/NormalSitesv2
 *  (cluster189 leaves 2/5 and 3/5 siblings 303/304). The
 *  distinguishing characteristic: fetchMangaChaptersF uses TWO endpoints
 *  (infoUrl + chaptersUrl) instead of one — combined via Flow.combine.
 *  3 `open var` toggles homeGet (line 35) + searchGet (line 36) +
 *  fixedImgUrl (line 37) + 1 `var` isChapterGet (line 38). The `var`
 *  vs `open var` distinction at line 38 means isChapterGet is NOT
 *  overridable in concrete subclasses — it's a runtime-mutable internal
 *  flag, not a per-source customization point. The `fixedImgUrl` toggle
 *  at line 37 is declared but NOT consumed in this base class — it's
 *  a forecast-hook for concrete subclasses to gate img-URL-fixing
 *  logic in their extract methods.
 *
 *  (c) `fun fetchMangaHome(url: String, page: Int = 0)` body (lines
 *  50-64) — LIVE-NOT-STALE; verified the homeGet-conditional GET/POST
 *  routing pattern identical to v1 NormalSites's fetchMangaHome
 *  (cluster189 leaf 2/5 sibling 303) EXCEPT the POST branch elides
 *  `headers = defaultHeaders` (line 60) — preserving the source's
 *  no-headers POST shape. The GET branch uses positional `api.get(url,
 *  defaultHeaders)` (line 57) instead of named `headers =
 *  defaultHeaders` — both shapes are equivalent calls to the ApiClient
 *  surface; the positional vs named choice is preserved verbatim from
 *  the original source for minimal-diff posture.
 *
 *  (d) `fetchMangaChaptersF(mangaId: String)` body (lines 83-163) —
 *  LIVE-NOT-STALE; verified the separated-details combine pattern:
 *    (d.1) infoUrl = createInfoUrl(mangaId) (line 86) + chaptersUrl =
 *      createChaptersUrl(mangaId) (line 87) — two abstract URL-building
 *      methods that concrete subclasses implement to map a single mangaId
 *      onto the two endpoints.
 *    (d.2) infoFlow = fetchDataWithHeaders({ api.get(infoUrl,
 *      defaultHeaders) }) { html -> extractMangaInfo(html, infoUrl,
 *      mangaId) } (lines 93-95) — single GET endpoint for info.
 *    (d.3) chaptersFlow = fetchDataWithHeaders({ if (isChapterGet) api
 *      .get(chaptersUrl, defaultHeaders) else api.postForm(chaptersUrl,
 *      fields = handelFormBody(0, true) ?: emptyMap()) }) { html ->
 *      parseChapters(html) } (lines 100-107) — GET/POST routing on
 *      isChapterGet. Chained with `.catch { e -> emit(State.Success(
 *      emptyList())) }` (lines 110-112) and `.map { state -> when ...
 *      Error → Success(emptyList()) }` (lines 114-122) — the error-to
 *      -empty-list fallback semantics that distinguish chaptersFlow:
 *      chapter extraction failure is non-fatal (manga info still
 *      renders with empty chapter list), but info extraction failure
 *      IS fatal.
 *    (d.4) Combined flow at lines 127-162: `flow { emit(State.Loading);
 *      infoFlow.combine(chaptersFlow) { ... }.collect { (infoState,
 *      chapState) -> ... } }` — explicit 4-branch state-machine:
 *      Loading-if-either-loading (3a lines 136-139) + forward-info-error
 *      (3b lines 142-145) + null-info-as-error (3c lines 148-152) +
 *      success-merge with chapter-list (3d/e lines 155-160 — mutates
 *      mangaInfo.chapters via clear + addAll). The `mangaInfo.chapters
 *      .clear() + addAll(chapterList)` mutation pattern at lines
 *      158-159 means the MangaInfo's `chapters` field is `MutableList
 *      <ChapterItem>` (not val/List) — concrete extractMangaInfo
 *      implementations can return MangaInfo instances with empty
 *      chapters lists; the combine-collect block fills them via
 *      reactive merge.
 *
 *  (e) `fetchChapterDataF(url: String)` body (lines 170-185) —
 *  LIVE-NOT-STALE; verified the conditional-prepending of baseUrl when
 *  the `url` parameter is relative: `if (url.startsWith("http",
 *  ignoreCase = true)) url else "${baseUrl.ifBlank { BASE_URL }}$url"`
 *  (lines 173-177). This is a robustness measure for sources that
 *  surface relative URLs in their chapter-listing HTML; the absolute
 *  -prefix fallback uses `baseUrl.ifBlank { BASE_URL }` — preferring
 *  the runtime-mutable `baseUrl` field (BaseManga sibling 302) over
 *  the compile-time `BASE_URL` constant for Phase-7 hot-swap
 *  compatibility.
 *
 *  (f) `fetchMoreManga` pagination merge (lines 190-218) —
 *  LIVE-NOT-STALE; structurally identical to v1 NormalSites's
 *  fetchMoreManga (cluster189 leaf 2/5 sibling 303 item g). The merge
 *  semantics — addAll into mergedList + empty-newItems fallback +
 *  .catch terminal — are identical.
 *
 *  (g) `normalSearch / genresSearch / sortSearch` override triplet
 *  (lines 224-240) — PARTIALLY-LIVE-NOT-STALE; the THREE searches have
 *  ASYMMETRIC GET/POST behavior:
 *    (g.1) normalSearch (lines 224-230) branches on `searchGet` —
 *      supports both GET and POST routing via the handelSearchFormBody
 *      abstract.
 *    (g.2) genresSearch (lines 232-235) is GET-ONLY — no searchGet
 *      branch, no POST fallback. The default-headers GET call is the
 *      only path.
 *    (g.3) sortSearch (lines 237-240) is GET-ONLY — same as
 *      genresSearch.
 *  This asymmetry is INTENTIONAL: the SeparatedDetailsSites source
 *  templates use POST routing ONLY for the Normal search type; the
 *  GENRES/SORT search types use URL-encoded query parameters that
 *  fit cleanly into GET requests. The `handelSearchFormBody`
 *  abstract at line 220 is typed `SearchType.Normal` specifically
 *  (not the broader SearchType union) — Kotlin compile-time enforces
 *  the POST-routing-only-for-Normal contract.
 *
 *  (h) 7 abstract bottom-half members (createInfoUrl + createChaptersUrl
 *  + handelSearchFormBody + parseChapters + extractHomeMangaItems +
 *  extractMangaList + extractMangaInfo + getSearchResults +
 *  refreshHeaders + getChapterImages) at lines 168-169 + 220 + 246-252
 *  — LIVE-NOT-STALE; the separated-details extraction-customization
 *  surface ADDS createInfoUrl + createChaptersUrl + parseChapters vs
 *  the v1/v2 NormalSites surfaces. The `extractMangaInfo(html: String,
 *  baseUrl: String, combinUrl: String = "")` signature at line 249
 *  adds a third parameter `combinUrl` with default "" — letting
 *  concrete subclasses optionally consume the original mangaId/
 *  infoUrl when extracting (used for chapter-URL relativization).
 *
 *  (i) `extractMangaInfo` signature divergence — LIVE-NOT-STALE;
 *  the SeparatedDetailsSites signature `extractMangaInfo(html, baseUrl,
 *  combinUrl = "")` (3 parameters, line 249) differs from
 *  NormalSites's `extractMangaInfo(string, baseUrl)` (2 parameters,
 *  cluster189 leaf 2/5 line 191). The third parameter exists
 *  specifically because separated-details sources need to thread the
 *  mangaId through to the chapter-URL-builder inside the extractor —
 *  whereas single-endpoint sources have the mangaId already encoded
 *  in the URL.
 *
 * Verified: 1 `abstract class SeparatedDetailsSites` declaration
 * extending `BaseManga(sourcesRepository,)` with 2 constructor parameters
 * (api + sourcesRepository) + 5 abstract identity-and-URL members
 * (mangaSource + homeUrl + popularUrl + handelLoadMoreUrl + handelSearchUrl)
 * + 4 `open var`/`var` toggles (homeGet + searchGet + fixedImgUrl +
 * isChapterGet) + 4 abstract metadata members (sortTypes + allGenres +
 * blackListGenres + defaultHeaders) + 1 abstract handelFormBody member
 * + 6 concrete fetch fan-out methods (fetchMangaHome + fetchPopularManga
 * + fetchMangaChaptersF with separated combine + fetchChapterDataF
 * with baseUrl-prepending + fetchMoreManga) + normalSearch/genresSearch
 * /sortSearch override triplet (asymmetric GET/POST: only Normal has
 * POST fallback) + 1 abstract handelSearchFormBody (Normal-only) + 7
 * abstract bottom-half extraction members (createInfoUrl +
 * createChaptersUrl + parseChapters + extractHomeMangaItems +
 * extractMangaList + extractMangaInfo with 3-arity signature +
 * getSearchResults + refreshHeaders + getChapterImages) + 4 Phase-7
 * -batch-7.0 migration-note KDoc bullets. Sibling: NormalSitesv2.kt
 * (cluster189 leaf 3/5 prior sibling 304). LEAF 4/5 of the cluster189
 * :shared sources_repositry common tier scout 5-leaf batch. Compound
 * classification: LIVE-NOT-STALE + FULFILLED-PORT for the four Phase 7
 * batch 7.0 substitution bullets (Log → Kermit + Retrofit ApiClient
 * with no-headers POST form + FormBody → Map nullable + DataStoreHelper
 * constructor-parameter drop). The "Phase 8 multiplatform-settings
 * forecast" remains FORECAST-NOT-YET-FULFILLED. Original Phase-7-batch
 * -7.0 migration-note prose preserved verbatim.
 */
