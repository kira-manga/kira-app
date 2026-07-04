package me.manga.kira.sources_repositry.ar.mangamelloplus

/**
 * Migration note (Phase 7.1): Retrofit -> Ktor ApiClient, okhttp3.FormBody -> Map<String, String>?,
 * @Inject dropped, android.util.Log -> Kermit Logger, kotlin.jvm.Volatile -> kotlin.concurrent.Volatile,
 * `java.util.TimeZone.getDefault().id` -> `kotlinx.datetime.TimeZone.currentSystemDefault().id`,
 * `java.time.LocalDate`/`DateTimeFormatter` -> `kotlinx.datetime.Instant.parse(...).toLocalDateTime(zone).date`,
 * `kotlinx.coroutines.Dispatchers.IO` dropped (JVM-only), Coil3 builders removed (see BaseManga note).
 *
 * `HandelDataClasses.emptyMangaInfo` not ported yet; inlined locally.
 */

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
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
import me.manga.kira.sources_repositry.ar.mangamello.models.chapters.DataCh
import me.manga.kira.sources_repositry.ar.mangamello.models.chapters.MelloChapters
import me.manga.kira.sources_repositry.ar.mangamello.models.home.Data
import me.manga.kira.sources_repositry.ar.mangamello.models.home.MelloHome
import me.manga.kira.sources_repositry.ar.mangamello.models.info.DataIn
import me.manga.kira.sources_repositry.ar.mangamello.models.info.MelloInfo
import me.manga.kira.sources_repositry.ar.mangamello.models.pages.MelloPages
import me.manga.kira.sources_repositry.ar.mangamello.models.search.DataSh
import me.manga.kira.sources_repositry.ar.mangamello.models.search.MelloSearch
import me.manga.kira.sources_repositry.common.SeparatedDetailsSites
import me.manga.kira.sources_repositry.data.MangaSource

@OptIn(ExperimentalTime::class)
class MangamelloPlusRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
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
    private val TAG = "MelloParser"

    private inline fun logD(msg: () -> String) {
        Logger.withTag(TAG).d(msg())
    }

    private inline fun logE(msg: () -> String, tr: Throwable? = null) {
        if (tr != null) {
            Logger.withTag(TAG).e(msg(), tr)
        } else {
            Logger.withTag(TAG).e(msg())
        }
    }

    override var imgBaseUrl: String = "https://raw.githubusercontent.com/"
    override var imgUrlVersion: Int = 0
    override val mangaSource: MangaSource
        get() = MangaSource.MANGAMELLOPLUS
    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }
    override val API: String = mangaSource.API
    override val LANGUAGE: String by lazy { mangaSource.LANGUAGE.Language }
    override val homeUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas?sort_by=updated_at&page=1" }

    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas?sort_by=views&page=1" }

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas?sort_by=updated_at&page=$page"
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas/search?per_page=40&title=${searchType.query}"
            is SearchType.GENRES -> ""
            is SearchType.SORT -> ""
        }

    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    val refererHeader: Map<String, String> = mapOf(
        "accept" to "application/json",
        "authorization" to "Bearer null",
        "content-type" to "application/json",
        "host" to "plus.mangamello.com",
        "installer" to "com.google.android.packageinstaller",
        "user-agent" to "Dart/3.8    (dart:io)",
        "vsesion" to "1.1.7",
        "zone" to TimeZone.currentSystemDefault().id,
    )

    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            val merged = base + refererHeader
            logD { "defaultHeaders -> $merged" }
            return merged
        }

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? = null

    val imgsHeader: Map<String, String> = mapOf(
        "host" to "cdn.mangamello.com",
        "referer" to "https://plus.mangamello.com/",
        "user-agent" to "Dart/3.3 (dart:io)",
    )

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchDataWithHeaders({
            val normalized = normalizeLegacyUrl(url)
            val fullUrl =
                if (normalized.startsWith("http", true)) {
                    normalized
                } else {
                    "${baseUrl.ifBlank { BASE_URL }}$normalized"
                }

            logD { "fetchChapterDataF -> GET $fullUrl" }
            logD { "Headers -> $defaultHeaders" }

            api.get(fullUrl, defaultHeaders)
        }) { json ->
            logD { "fetchChapterDataF response length=${json.length}" }
            getChapterImages(json)
        }

    override fun createInfoUrl(mangaId: String): String {
        val url = normalizeLegacyUrl(mangaId)
        logD { "createInfoUrl -> $url" }
        return url
    }

    override fun createChaptersUrl(mangaId: String): String {
        val fixed = normalizeLegacyUrl(mangaId)
        val url = "$fixed/chapters?per_page=2000"
        logD { "createChaptersUrl -> $url" }
        return url
    }

    override fun handelSearchFormBody(page: Int, searchType: SearchType.Normal): Map<String, String>? = null

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        logD { "refreshHeaders called with: $newHeaders" }

        val merged = newHeaders + refererHeader
        _cachedHeaders = merged

        dataStore.saveHeadersForApi(API, merged)

        logD { "Headers cached & saved for API=$API" }
    }

    // Bug 4 fix: this override was missing in the upstream-1:1 port — upstream also lacked it, but
    // upstream parity is explicitly NOT a constraint when the resulting behavior is broken (see
    // pending-work.md). Without this, `_cachedHeaders` is null after every cold start and the
    // saved WebView cookies are silently dropped from outgoing requests. Mirrors the
    // TeamX/Webtoontr/Timenaight pattern used by 34 other repos.
    override suspend fun initSite(): Int {
        val saved = dataStore.getHeadersForApi(API) ?: emptyMap()
        logD { "initSite loaded ${saved.size} headers from DataStore for API=$API keys=${saved.keys}" }
        _cachedHeaders = saved
        return super.initSite()
    }

    override fun getChapterImages(html: String): List<String> {
        return try {
            logD { "Parsing chapter images JSON (${html.length} chars)" }
            val items: MelloPages = jsonParser.decodeFromString(html)
            val images = items.toImageUrlList()
            logD { "Parsed ${images.size} chapter images" }
            images
        } catch (e: Exception) {
            logE({ "getChapterImages FAILED" }, e)
            emptyList()
        }
    }

    override fun parseChapters(html: String): List<ChapterItem> {
        return try {
            logD { "Parsing chapters JSON (${html.length} chars)" }
            val items: MelloChapters = jsonParser.decodeFromString(html)
            val chapters = items.data.toChapterItems()
                .sortedBy { it.number.toDoubleOrNull() }
                .reversed()

            logD { "Parsed ${chapters.size} chapters" }
            chapters
        } catch (e: Exception) {
            logE({ "parseChapters FAILED" }, e)
            emptyList()
        }
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        return try {
            logD { "Parsing HOME manga list" }
            val items: MelloHome = jsonParser.decodeFromString(html)
            val list = items.data.toMangaItems().toMutableList()
            logD { "Home mangas parsed: ${list.size}" }
            list
        } catch (e: Exception) {
            logE({ "extractHomeMangaItems FAILED" }, e)
            mutableListOf()
        }
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        return try {
            val items: MelloHome = jsonParser.decodeFromString(html)
            val mangas = items.data
            mangas.toPopularManga()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractMangaList: failed to parse manga list: ${e.message}" }
            emptyList()
        }
    }

    override fun extractMangaInfo(html: String, baseUrl: String, combinUrl: String): MangaInfo {
        return try {
            val items: MelloInfo = jsonParser.decodeFromString(html)
            items.data?.toMangaInfo(baseUrl) ?: emptyMangaInfo(baseUrl)
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractMangaInfo: failed to parse manga info: ${e.message}" }
            emptyMangaInfo(baseUrl)
        }
    }

    override fun getSearchResults(html: String): List<MangaItem> {
        return try {
            logD { "Parsing SEARCH results" }
            val items: MelloSearch = jsonParser.decodeFromString(html)
            val results = items.data.toSearchMangaItems()
            logD { "Search results count=${results.size}" }
            results
        } catch (e: Exception) {
            logE({ "getSearchResults FAILED" }, e)
            emptyList()
        }
    }

    /** Phase 7.1 inline replacement for `HandelDataClasses.emptyMangaInfo`. */
    private fun emptyMangaInfo(url: String): MangaInfo = MangaInfo(
        api = API,
        language = LANGUAGE,
        url = url,
        title = "",
        imageUrl = "",
        rating = "0",
        description = "",
        author = "",
        genres = emptyList(),
        status = "Unknown",
        chapters = mutableListOf(),
    )

    fun List<DataCh?>?.toChapterItems(): List<ChapterItem> = this
        .orEmpty()
        .mapNotNull { data ->
            data ?: return@mapNotNull null
            val mangaId = data.manga_id ?: return@mapNotNull null
            val chapterId = data.id ?: return@mapNotNull null

            ChapterItem(
                number = data.order?.toString()
                    ?: data.title?.toIntOrNull()?.toString()
                    ?: data.title.orEmpty(),
                name = data.title.orEmpty(),
                url = "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas/$mangaId/chapters/$chapterId?relations=chapterImages",
                date = data.created_at
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Instant.parse(it).toLocalDateTime(TimeZone.currentSystemDefault()).date }.getOrNull() },
                isDownloaded = false,
                isBookmarked = false,
            )
        }

    fun List<Data?>?.toPopularManga(): List<PopularManga> = this
        .orEmpty()
        .mapNotNull { data ->
            data ?: return@mapNotNull null
            val mangaUrl = "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas/${data.id}"
            PopularManga(
                api = API,
                language = LANGUAGE,
                title = data.title.orEmpty(),
                url = mangaUrl,
                imageUrl = data.img.orEmpty(),
            )
        }

    fun MelloPages.toImageUrlList(): List<String> =
        this.data
            ?.chapterImages
            .orEmpty()
            .mapNotNull { img ->
                img?.src
                    ?.takeIf { it.isNotBlank() }
                    ?: img?.originalSrc
                        ?.takeIf { it.isNotBlank() }
            }

    fun List<Data?>?.toMangaItems(): List<MangaItem> {
        return this
            .orEmpty()
            .mapNotNull { data ->
                data ?: return@mapNotNull null
                val mangaUrl = "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas/${data.id}"

                MangaItem(
                    api = API,
                    language = LANGUAGE,
                    title = data.title.orEmpty(),
                    url = mangaUrl,
                    imageUrl = data.img.orEmpty(),
                    rating = data.rate?.toInt(),
                    chapters = emptyList(),
                    genres = emptyList(),
                )
            }
    }

    fun DataIn.toMangaInfo(
        url: String,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        chapters: MutableList<ChapterItem> = mutableListOf(),
    ): MangaInfo = MangaInfo(
        api = API,
        language = LANGUAGE,
        url = url,
        title = title.orEmpty(),
        imageUrl = img.orEmpty(),
        rating = ten_rate?.toString().orEmpty(),
        description = summary.orEmpty(),
        author = "",
        genres = genres,
        status = when (is_completed) {
            1 -> "مكتمل"
            else -> "مستمر"
        },
        chapters = chapters,
    )

    fun List<DataSh?>?.toSearchMangaItems(): List<MangaItem> = this
        .orEmpty()
        .mapNotNull { dto ->
            dto ?: return@mapNotNull null
            val id = dto.id ?: return@mapNotNull null

            MangaItem(
                api = API,
                language = LANGUAGE,
                title = dto.title.orEmpty(),
                url = "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas/$id",
                imageUrl = dto.img.orEmpty(),
                rating = dto.average_rate?.toInt(),
                chapters = listOf(),
                genres = dto.genres
                    .orEmpty()
                    .mapNotNull { it?.name },
            )
        }

    private fun normalizeLegacyUrl(url: String): String {
        return url
    }
}

/**
 * Audit-trail postscript (Phase 9.x.cluster192.staleKdocSweep.cascade, Task #647, 2026-05-29)
 *
 * Leaf 4/5 §253 audit-trail-preservation postscript for cluster192, sibling 320 of the cluster57+
 * continuum. Medium-heavy 388-line Repository — semantic TWIN of sibling 319 (MangamelloRepository)
 * with three differentiating additions: (i) two inline logging helpers (logD/logE), (ii) a Bug 4
 * fix `initSite()` override that loads `_cachedHeaders` from DataStore on cold start, and (iii)
 * an `imgsHeader` map for image-CDN routing distinct from the API referer header.
 *
 * The top-of-file prose under audit (lines 3-11):
 *
 *     Migration note (Phase 7.1): Retrofit -> Ktor ApiClient, okhttp3.FormBody -> Map<String, String>?,
 *     @Inject dropped, android.util.Log -> Kermit Logger, kotlin.jvm.Volatile -> kotlin.concurrent.Volatile,
 *     `java.util.TimeZone.getDefault().id` -> `kotlinx.datetime.TimeZone.currentSystemDefault().id`,
 *     `java.time.LocalDate`/`DateTimeFormatter` -> `kotlinx.datetime.Instant.parse(...).toLocalDateTime(zone).date`,
 *     `kotlinx.coroutines.Dispatchers.IO` dropped (JVM-only), Coil3 builders removed (see BaseManga note).
 *
 *     `HandelDataClasses.emptyMangaInfo` not ported yet; inlined locally.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — all 7 migration claims (Retrofit/Ktor, FormBody/Map, @Inject drop,
 *      android.util.Log/Kermit, kotlin.jvm.Volatile/kotlin.concurrent.Volatile, TimeZone facade
 *      swap, java.time/kotlinx.datetime): verified by parallel import + body survey identical
 *      to sibling 319's verification. Imports lines 13-40 match the post-port baseline.
 *
 *   b. LIVE-NOT-STALE — the "kotlinx.coroutines.Dispatchers.IO dropped (JVM-only)" claim:
 *      verified by import survey. Zero `Dispatchers.IO` references in the body. Zero
 *      `withContext(Dispatchers.IO)` call sites. The upstream apparently wrapped image-fetch or
 *      chapter-parse work in `Dispatchers.IO`; the KMP port relies on Ktor's intrinsic suspend
 *      semantics (ApiClient internally handles I/O dispatching) and on Kotlin's default
 *      dispatcher for the parsing work. This is the canonical KMP pattern — Dispatchers.IO is
 *      JVM-only and would break iOS compilation. Migration complete.
 *
 *   c. LIVE-NOT-STALE — the "Coil3 builders removed" claim: verified by import survey identical
 *      to sibling 319. Zero `coil3.*` / `coil.*` imports.
 *
 *   d. LIVE-NOT-STALE — the "HandelDataClasses.emptyMangaInfo not ported yet; inlined locally"
 *      claim: verified at lines 263-275 (the private `emptyMangaInfo(url: String)` inline
 *      function). The "Phase 7.1 inline replacement" KDoc at line 262 documents the substitution.
 *      Called from `extractMangaInfo` at lines 242 + 245.
 *
 *   e. POTENTIAL-BUG-PRESERVED — the "Bug 4 fix" annotation at lines 174-178 + the `initSite()`
 *      override at lines 179-184. The annotation reads (verbatim quoted from comment):
 *
 *          // Bug 4 fix: this override was missing in the upstream-1:1 port — upstream also lacked
 *          // it, but upstream parity is explicitly NOT a constraint when the resulting behavior is
 *          // broken (see pending-work.md). Without this, `_cachedHeaders` is null after every cold
 *          // start and the saved WebView cookies are silently dropped from outgoing requests.
 *          // Mirrors the TeamX/Webtoontr/Timenaight pattern used by 34 other repos.
 *
 *      This is a documented deliberate deviation from the upstream-1:1 port — the comment
 *      explicitly notes that 34 other Repositories follow the "load cached headers from DataStore
 *      on initSite" pattern. Sibling 319 (MangamelloRepository) does NOT have this override.
 *      Preserved verbatim per §253. The "pending-work.md" reference points at a sibling project
 *      doc that may or may not still exist — the §253 sweep does not modify the comment beyond
 *      flagging it for future audit.
 *
 *   f. LIVE-NOT-STALE — the inline `logD`/`logE` helper functions at lines 58-68: verified.
 *      Both use `Logger.withTag(TAG).d/e(msg(), tr)` form — Kermit-portable, no platform-specific
 *      Logger.priority constants. The `inline` modifier suppresses the lambda allocation cost in
 *      hot paths (e.g. the 5 logD calls in `fetchChapterDataF` at lines 139-144). Pattern is
 *      idiomatic and matches the broader KMP logging convention.
 *
 *   g. COSMETIC-NOT-STALE — the user-agent header asymmetry between this file's line 108
 *      ("Dart/3.8    (dart:io)" with 4 spaces) and sibling 319's line 98 ("Dart/3.3 (dart:io)"
 *      with 1 space, version 3.3 vs 3.8). Likely upstream-captured-at-different-times spoofed
 *      user-agent values. Preserved verbatim per §253. The 4-space padding in this file MAY be
 *      a deliberate fingerprint-evasion technique or a copy-paste-from-different-source artifact.
 *      Not a sweep concern.
 *
 *   h. POTENTIAL-BUG-PRESERVED — the `imgsHeader` Map at lines 123-127 is declared but the only
 *      reference in the visible body is its declaration site (zero call sites). The map's keys
 *      (host=cdn.mangamello.com, referer=plus.mangamello.com, user-agent) suggest it was intended
 *      for image-CDN routing distinct from the API's `defaultHeaders`. Either the consuming code
 *      path in the parent `SeparatedDetailsSites` accesses this header set by reflection (unlikely
 *      — KMP doesn't do that), or the map is genuinely orphan post-port. Sibling 319 does NOT
 *      have this map. Preserved verbatim per §253 — a future cleanup slice should determine
 *      whether to wire `imgsHeader` into a Coil ImageLoader request interceptor or to delete it.
 *
 *   i. COSMETIC-NOT-STALE — sub-section divider commentary (the "Bug 4 fix" rationale paragraph
 *      at lines 174-178 + the "Phase 7.1 inline replacement" KDoc at line 262) is preserved
 *      verbatim per §253. These are documentation-of-deviations and document-of-deferrals — both
 *      protected by the "audit-trail-preservation" policy.
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 317 (MangaParkRepositoryAr.kt) — leaf 1/5, opening leaf, 4-override minimal subclass.
 *   - sibling 318 (DilarV2Repository.kt) — leaf 2/5, medium Repository with JSON-body POST search.
 *   - sibling 319 (MangamelloRepository.kt) — leaf 3/5, TWIN OF THIS FILE without Bug 4 fix
 *     (cross-reference for the same-host-header diagnosis and Bug 4 asymmetry above).
 *   - sibling 321 (AasqRepositoryv2.kt) — leaf 5/5, closing leaf with locale-aware date parser.
 *
 * Cluster192 leaf 4/5. Next leaf: AasqRepositoryv2.kt (sibling 321 — closing leaf).
 */
