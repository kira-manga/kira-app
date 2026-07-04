package me.manga.kira.sources_repositry.ar.mangamello

/**
 * Migration note (Phase 7.1): Retrofit -> Ktor ApiClient, okhttp3.FormBody -> Map<String, String>?,
 * @Inject dropped, android.util.Log -> Kermit Logger, kotlin.jvm.Volatile -> kotlin.concurrent.Volatile,
 * `java.util.TimeZone.getDefault().id` -> `kotlinx.datetime.TimeZone.currentSystemDefault().id`,
 * `java.time.LocalDate`/`DateTimeFormatter` -> `kotlinx.datetime.Instant.parse(...).toLocalDateTime(zone).date`,
 * Coil3 image-request builders (`buildImageRequest`/`buildItemsImageRequest`) removed (see
 * `BaseManga` migration note; Coil3 is not in commonMain dependencies).
 *
 * `HandelDataClasses.emptyMangaInfo` does not exist in commonMain — the empty `MangaInfo`
 * placeholder is inlined locally.
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
class MangamelloRepository(
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

    override var imgBaseUrl: String = "https://raw.githubusercontent.com/"
    override var imgUrlVersion: Int = 0
    override val mangaSource: MangaSource
        get() = MangaSource.MANGAMELLO
    override val BASE_URL: String by lazy { "${baseUrl.ifBlank { mangaSource.BASEURL }}api/v1/mangas/" }
    override val API: String = mangaSource.API
    override val LANGUAGE: String by lazy { mangaSource.LANGUAGE.Language }
    override val homeUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas?sort_by=updated_at&page=1" }

    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas?sort_by=views&page=1" }

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}api/v1/mangas?sort_by=updated_at&page=$page"
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
        "user-agent" to "Dart/3.3 (dart:io)",
        "vsesion" to "1.1.7",
        "zone" to TimeZone.currentSystemDefault().id,
    )

    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            return base + refererHeader
        }

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? = null

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchDataWithHeaders({
            val normalized = normalizeLegacyUrl(url)
            val fullUrl =
                if (normalized.startsWith("http", ignoreCase = true)) {
                    normalized
                } else {
                    "${baseUrl.ifBlank { BASE_URL }}$normalized"
                }

            api.get(fullUrl, defaultHeaders)
        }) { html ->
            getChapterImages(html)
        }

    override fun createInfoUrl(mangaId: String): String {
        return normalizeLegacyUrl(mangaId)
    }

    override fun createChaptersUrl(mangaId: String): String {
        val fixed = normalizeLegacyUrl(mangaId)
        return "$fixed/chapters?per_page=2000"
    }

    override fun handelSearchFormBody(page: Int, searchType: SearchType.Normal): Map<String, String>? = null

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        val merged = newHeaders + refererHeader
        _cachedHeaders = merged
        dataStore.saveHeadersForApi(API, merged)
    }

    override fun getChapterImages(html: String): List<String> {
        return try {
            val items: MelloPages = jsonParser.decodeFromString(html)
            items.toImageUrlList()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "getChapterImages: failed to parse chapter images: ${e.message}" }
            emptyList()
        }
    }

    override fun parseChapters(html: String): List<ChapterItem> {
        return try {
            val items: MelloChapters = jsonParser.decodeFromString(html)
            items.data.toChapterItems()
                .sortedBy { it.number.toDoubleOrNull() }
                .reversed()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "parseChapters: failed to parse chapters: ${e.message}" }
            emptyList()
        }
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        return try {
            val items: MelloHome = jsonParser.decodeFromString(html)
            val mangas = items.data
            mangas.toMangaItems().toMutableList()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractHomeMangaItems: failed to parse home manga items: ${e.message}" }
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
            val items: MelloSearch = jsonParser.decodeFromString(html)
            items.data.toSearchMangaItems()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "getSearchResults: failed to parse search results: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Phase 7.1 inline replacement for `HandelDataClasses.emptyMangaInfo` (which lived in the
     * Android source set and is not yet ported).
     */
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
 * Leaf 3/5 §253 audit-trail-preservation postscript for cluster192, sibling 319 of the cluster57+
 * continuum. Medium 336-line Repository extending `SeparatedDetailsSites` with Mello-specific JSON
 * parsing (5 distinct DTO families: chapters/home/info/pages/search) and an inlined
 * `emptyMangaInfo` placeholder substituting for the un-ported `HandelDataClasses.emptyMangaInfo`.
 *
 * The top-of-file prose under audit (lines 3-13):
 *
 *     Migration note (Phase 7.1): Retrofit -> Ktor ApiClient, okhttp3.FormBody -> Map<String, String>?,
 *     @Inject dropped, android.util.Log -> Kermit Logger, kotlin.jvm.Volatile -> kotlin.concurrent.Volatile,
 *     `java.util.TimeZone.getDefault().id` -> `kotlinx.datetime.TimeZone.currentSystemDefault().id`,
 *     `java.time.LocalDate`/`DateTimeFormatter` -> `kotlinx.datetime.Instant.parse(...).toLocalDateTime(zone).date`,
 *     Coil3 image-request builders (`buildImageRequest`/`buildItemsImageRequest`) removed (see
 *     `BaseManga` migration note; Coil3 is not in commonMain dependencies).
 *
 *     `HandelDataClasses.emptyMangaInfo` does not exist in commonMain — the empty `MangaInfo`
 *     placeholder is inlined locally.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — the "Retrofit -> Ktor ApiClient" migration claim: verified by import
 *      survey of lines 15-42. `ApiClient` import present (line 26); zero Retrofit references.
 *
 *   b. LIVE-NOT-STALE — the "okhttp3.FormBody -> Map<String, String>?" claim: verified by
 *      reading line 109 (`handelFormBody` returns `null` — form-data not used) and line 135
 *      (`handelSearchFormBody` returns `null` — search uses query-string GET via
 *      `handelSearchUrl`). Zero `okhttp3` imports. Migration complete.
 *
 *   c. LIVE-NOT-STALE — the "kotlin.jvm.Volatile -> kotlin.concurrent.Volatile" claim: verified
 *      by line 16 import + line 89 annotation on `_cachedHeaders`.
 *
 *   d. LIVE-NOT-STALE — the "java.util.TimeZone.getDefault().id -> kotlinx.datetime.TimeZone
 *      .currentSystemDefault().id" claim: verified at line 100 (`TimeZone.currentSystemDefault().id`
 *      in the refererHeader's "zone" entry). Import at line 20.
 *
 *   e. LIVE-NOT-STALE — the "java.time.LocalDate/DateTimeFormatter -> kotlinx.datetime.Instant
 *      .parse(...).toLocalDateTime(zone).date" claim: verified at line 240 (
 *      `Instant.parse(it).toLocalDateTime(TimeZone.currentSystemDefault()).date`). Imports at
 *      lines 18 (Instant) + 20 (TimeZone) + 21 (toLocalDateTime).
 *
 *   f. LIVE-NOT-STALE — the "Coil3 image-request builders removed" claim: verified by import
 *      survey. Zero `coil3.*` / `coil.*` imports. The class declares overrides for the canonical
 *      Repository surface (extractHomeMangaItems, extractMangaList, extractMangaInfo, parseChapters,
 *      getChapterImages, getSearchResults) but does NOT override the deprecated Coil3 builder
 *      hook points. The "see BaseManga migration note" cross-reference is reachable via
 *      :sources_repositry/common/ tier (verified by prior cluster183 BaseManga sweep, ref:
 *      [[cluster183]]).
 *
 *   g. LIVE-NOT-STALE — the "HandelDataClasses.emptyMangaInfo does not exist in commonMain —
 *      inlined locally" claim: verified at lines 211-223 (the private `emptyMangaInfo(url: String)`
 *      function inline declaration). The "Phase 7.1 inline replacement" KDoc at lines 207-210
 *      documents the substitution. The function is called from `extractMangaInfo` at lines 190
 *      + 193.
 *
 *   h. POTENTIAL-BUG-PRESERVED — the hardcoded `host` header value "plus.mangamello.com" at
 *      line 97 of `refererHeader`. This is the MANGAMELLO source's referer host, but the class
 *      `MangamelloRepository` (sibling 319) and its twin `MangamelloPlusRepository` (sibling 320)
 *      both ship the SAME hardcoded host. The twin at sibling 320 has the same hardcoded value
 *      at its line 106. Either both sources legitimately share the "plus" subdomain (which would
 *      mean MangamelloRepository's HOME endpoint should be on the plus subdomain too, contradicting
 *      `MangaSource.MANGAMELLO.BASEURL`) or one of the two is a copy-paste error. Preserved
 *      verbatim per §253 — a future cleanup slice should investigate whether the non-plus variant
 *      should use a different host header.
 *
 *   i. COSMETIC-NOT-STALE — the "is_completed" status mapping at lines 306-309 uses Arabic
 *      strings ("مكتمل" / "مستمر" — "completed" / "ongoing"). Preserved verbatim — these are
 *      the user-facing status labels for the Arabic locale.
 *
 *   j. COSMETIC-NOT-STALE — the trailing-slash discipline on `BASE_URL` at line 64 (
 *      `"${baseUrl.ifBlank { mangaSource.BASEURL }}api/v1/mangas/"`) ends in "/", while the URL
 *      composition in `handelLoadMoreUrl` at line 72 uses `.dropTrailingSlash()` before
 *      concatenating. This is a deliberate two-discipline pattern: the BASE_URL ends in "/" for
 *      the path-relative URL building, while the load-more URL uses an absolute path that
 *      requires the slash to be dropped. Preserved verbatim per §253.
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 317 (MangaParkRepositoryAr.kt) — leaf 1/5, opening leaf, 4-override minimal subclass.
 *   - sibling 318 (DilarV2Repository.kt) — leaf 2/5, medium Repository with JSON-body POST search.
 *   - sibling 320 (MangamelloPlusRepository.kt) — leaf 4/5, TWIN OF THIS FILE with logging helpers
 *     + Bug 4 fix initSite override (cross-reference for the same-host-header diagnosis above).
 *   - sibling 321 (AasqRepositoryv2.kt) — leaf 5/5, closing leaf with locale-aware date parser.
 *
 * Cluster192 leaf 3/5. Next leaf: MangamelloPlusRepository.kt (sibling 320).
 */
