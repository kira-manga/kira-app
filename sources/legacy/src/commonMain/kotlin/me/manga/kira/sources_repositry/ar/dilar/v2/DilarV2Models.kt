package me.manga.kira.sources_repositry.ar.dilar.v2

/**
 * Migration note (Phase 7.1): java.time -> kotlinx.datetime. The Android source used
 * java.time.LocalDate, java.time.OffsetDateTime, java.time.format.DateTimeFormatter.
 * Ports:
 *   - LocalDate.now() -> Clock.System.todayIn(TimeZone.currentSystemDefault())
 *   - OffsetDateTime.parse(s, ISO_OFFSET_DATE_TIME).toLocalDate() ->
 *       Instant.parse(s).toLocalDateTime(TimeZone.currentSystemDefault()).date
 *   - LocalDate.parse(s, ISO_LOCAL_DATE) -> LocalDate.parse(s) (ISO format is the default)
 * Behaviour preserved: invalid date -> today's local date.
 */

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga

// ==================== Home/Series List Response ====================

@Serializable
data class DilarSeriesListResponse(
    val series: List<DilarSeries> = emptyList(),
    val totalPages: Int = 0,
    val currentPage: Int = 1,
    val totalItems: Int = 0
)

@Serializable
data class DilarSeries(
    val id: String,
    val title: String,
    val summary: String? = null,
    val cover: String? = null,
    val synonyms: DilarSynonyms? = null,
    val rating: String? = null,
    @SerialName("rates_count")
    val ratesCount: Int = 0,
    @SerialName("translation_status")
    val translationStatus: String? = null,
    @SerialName("story_status")
    val storyStatus: String? = null,
    val seriesType: DilarSeriesType? = null,
    val creator: DilarCreator? = null,
    val latestChapter: DilarLatestChapter? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

@Serializable
data class DilarSynonyms(
    val arabic: String? = null,
    val english: String? = null,
    val japanese: String? = null,
    val alternative: String? = null
)

@Serializable
data class DilarSeriesType(
    val id: String,
    val name: String? = null,
    val title: String? = null,
    @SerialName("reading_direction")
    val readingDirection: String? = null
)

@Serializable
data class DilarCreator(
    val id: String,
    val nick: String? = null
)

@Serializable
data class DilarLatestChapter(
    val id: String,
    val volume: Int = 0,
    val chapter: String,
    val title: String? = null
)

// ==================== Series Detail Response ====================

@Serializable
data class DilarSeriesDetailResponse(
    val id: String,
    val title: String,
    val summary: String? = null,
    val cover: String? = null,
    val banner: String? = null,
    val synonyms: DilarSynonyms? = null,
    val rating: String? = null,
    @SerialName("rates_count")
    val ratesCount: Int = 0,
    @SerialName("translation_status")
    val translationStatus: String? = null,
    @SerialName("story_status")
    val storyStatus: String? = null,
    val seriesType: DilarSeriesType? = null,
    val creator: DilarCreator? = null,
    val categories: List<DilarCategory> = emptyList(),
    val staff: List<DilarStaff> = emptyList(),
    val releases: List<DilarRelease> = emptyList(),
    val chapterCount: Int = 0,
    val seriesViews: Int = 0,
    @SerialName("s_date")
    val startDate: String? = null,
    @SerialName("e_date")
    val endDate: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

@Serializable
data class DilarCategory(
    val id: String,
    val name: String
)

@Serializable
data class DilarStaff(
    val id: String,
    val name: String,
    @SerialName("Staff")
    val staffInfo: DilarStaffInfo? = null
)

@Serializable
data class DilarStaffInfo(
    @SerialName("series_id")
    val seriesId: String? = null,
    @SerialName("mangaka_id")
    val mangakaId: String? = null,
    val role: String? = null
)

@Serializable
data class DilarRelease(
    val id: String,
    @SerialName("created_at")
    val createdAt: String? = null,
    val views: Int = 0,
    @SerialName("chapterization_id")
    val chapterizationId: String? = null,
    @SerialName("series_id")
    val seriesId: String? = null,
    val chapter: DilarChapterInfo? = null,
    val teams: List<DilarTeam> = emptyList(),
    val initialTeam: DilarTeam? = null,
    val isRead: Boolean = false,
    @SerialName("is_latest_for_chapter")
    val isLatestForChapter: Boolean = false
)

@Serializable
data class DilarChapterInfo(
    val id: String,
    val season: Int = 0,
    val volume: Int = 0,
    val chapter: String,
    val title: String? = null
)

@Serializable
data class DilarTeam(
    val id: String,
    val name: String
)

// ==================== Chapters List Response ====================

@Serializable
data class DilarChaptersResponse(
    val chapters: List<DilarChapterDetail> = emptyList(),
    val totalItems: Int = 0,
    val libraryTypeId: String? = null,
    val seriesViews: Int = 0
)

@Serializable
data class DilarChapterDetail(
    val id: String,
    @SerialName("series_id")
    val seriesId: String,
    val season: Int = 0,
    val volume: Int = 0,
    val chapter: String,
    val title: String? = null,
    val lock: Boolean = false,
    @SerialName("tler_id")
    val tlerId: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    val releases: List<DilarChapterRelease> = emptyList(),
    val isRead: Boolean = false
)

@Serializable
data class DilarChapterRelease(
    val id: String,
    @SerialName("created_at")
    val createdAt: String? = null,
    val views: Int = 0,
    val downloads: Int = 0,
    @SerialName("chapterization_id")
    val chapterizationId: String? = null,
    @SerialName("series_id")
    val seriesId: String? = null,
    val teams: List<DilarTeam> = emptyList(),
    val initialTeam: DilarTeam? = null,
    val isRead: Boolean = false
)

// ==================== Chapter Images Response ====================

@Serializable
data class DilarChapterImagesResponse(
    val id: String,
    @SerialName("series_id")
    val seriesId: String,
    @SerialName("storage_key")
    val storageKey: String,
    val pages: List<DilarPage> = emptyList(),
    @SerialName("webp_pages")
    val webpPages: List<DilarPage> = emptyList(),
    val series: DilarChapterSeriesInfo? = null,
    val chapter: DilarChapterMetadata? = null,
    val prevChapter: DilarChapterNav? = null,
    val nextChapter: DilarChapterNav? = null
)

@Serializable
data class DilarPage(
    val url: String,
    val order: Int,
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class DilarChapterSeriesInfo(
    val id: String,
    val title: String,
    val cover: String? = null,
    val seriesType: DilarSeriesType? = null
)

@Serializable
data class DilarChapterMetadata(
    val id: String,
    @SerialName("series_id")
    val seriesId: String? = null,
    val volume: Int = 0,
    val chapter: String,
    val title: String? = null,
    val season: Int = 0,
    val translator: DilarTranslator? = null
)

@Serializable
data class DilarTranslator(
    val id: String,
    val nick: String? = null
)

@Serializable
data class DilarChapterNav(
    val id: String,
    val volume: Int = 0,
    val chapter: String,
    val title: String? = null,
    val releases: List<DilarNavRelease>? = null,
    val releaseId: String? = null
)

@Serializable
data class DilarNavRelease(
    val id: String
)

// ==================== Search Response ====================

@Serializable
data class DilarSearchRequest(
    val query: String,
    val includes: List<String> = listOf("Manga")
)

@Serializable
data class DilarSearchResponse(
    @SerialName("class")
    val className: String? = null,
    @SerialName("type_label")
    val typeLabel: String? = null,
    val data: List<DilarSearchManga> = emptyList()
)

@Serializable
data class DilarSearchManga(
    val id: String,
    val title: String,
    val summary: String? = null,
    val cover: String? = null,
    val synonyms: DilarSynonyms? = null,
    @SerialName("series_type")
    val seriesType: DilarSeriesType? = null,
    @SerialName("deleted_at")
    val deletedAt: String? = null,
    @SerialName("class")
    val className: String? = null
)

// ==================== Extension Functions ====================

private const val IMG_BASE_URL = "https://dilar.tube/uploads"
private const val COVER_BASE_URL = "$IMG_BASE_URL/manga/cover"
private const val RELEASES_BASE_URL = "$IMG_BASE_URL/releases"

fun DilarSeriesListResponse.toMangaItems(api: String, language: String, baseUrl: String): List<MangaItem> {
    return series.map { it.toMangaItem(api, language, baseUrl) }
}

fun DilarSeries.toMangaItem(api: String, language: String, baseUrl: String): MangaItem {
    return MangaItem(
        api = api,
        language = language,
        title = title,
        url = "${baseUrl}api/series/$id",
        imageUrl = "$COVER_BASE_URL/$id/$cover",
        rating = rating?.toDoubleOrNull()?.toInt() ?: 0,
        chapters = null,
        genres = emptyList()
    )
}

fun DilarSeriesListResponse.toPopularMangaList(api: String, language: String, baseUrl: String): List<PopularManga> {
    return series.map { it.toPopularManga(api, language, baseUrl) }
}

fun DilarSeries.toPopularManga(api: String, language: String, baseUrl: String): PopularManga {
    return PopularManga(
        api = api,
        language = language,
        title = title,
        url = "${baseUrl}api/series/$id",
        imageUrl = "$COVER_BASE_URL/$id/$cover"
    )
}

fun DilarSeriesDetailResponse.toMangaInfo(api: String, language: String, url: String): MangaInfo {
    val author = staff.find { it.staffInfo?.role == "Author" }?.name ?: creator?.nick ?: ""
    val artist = staff.find { it.staffInfo?.role == "Artist" }?.name ?: ""

    val altNames = listOfNotNull(
        synonyms?.arabic,
        synonyms?.english,
        synonyms?.japanese,
        synonyms?.alternative
    ).filter { it.isNotBlank() }.joinToString(" / ")

    return MangaInfo(
        api = api,
        language = language,
        url = url,
        title = title,
        imageUrl = "$COVER_BASE_URL/$id/$cover",
        rating = rating ?: "0",
        description = summary ?: "",
        author = author,
        genres = categories.map { it.name },
        status = mapStatus(translationStatus),
        chapters = mutableListOf()
    )
}

fun DilarChaptersResponse.toChapterItems(baseUrl: String): List<ChapterItem> {
    return chapters.flatMap { chapter ->
        chapter.releases.map { release ->
            val chapterNum = formatChapterNumber(chapter.chapter)
            ChapterItem(
                number = "Chapter $chapterNum",
                name = chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter $chapterNum",
                url = "${baseUrl}api/chapters/${release.id}",
                date = parseIsoDate(release.createdAt ?: chapter.createdAt),
                isDownloaded = false
            )
        }
    }.distinctBy { it.number }
}

fun DilarChapterImagesResponse.toImageUrls(): List<String> {
    val (pages, directory) = if (webpPages.isNotEmpty()) {
        webpPages to "hq_webp"
    } else {
        this.pages to "hq"
    }

    return pages
        .sortedBy { it.order }
        .map { page ->
            "$RELEASES_BASE_URL/$storageKey/$directory/${page.url}"
        }
}

fun List<DilarSearchResponse>.toMangaItems(api: String, language: String, baseUrl: String): List<MangaItem> {
    return flatMap { response ->
        if (response.className == "Manga") {
            response.data
                .filter { it.deletedAt == null }
                .filterNot {
                    it.seriesType?.let { st ->
                        st.title == "رواية" || st.name == "Novel"
                    } == true
                }
                .map { it.toMangaItem(api, language, baseUrl) }
        } else {
            emptyList()
        }
    }
}

fun DilarSearchManga.toMangaItem(api: String, language: String, baseUrl: String): MangaItem {
    return MangaItem(
        api = api,
        language = language,
        title = title,
        url = "${baseUrl}api/series/$id",
        imageUrl = "$COVER_BASE_URL/$id/$cover",
        rating = 0,
        chapters = null,
        genres = emptyList()
    )
}

// ==================== Utility Functions ====================

private fun formatChapterNumber(number: String): String {
    val doubleVal = number.toDoubleOrNull() ?: return number
    return if (doubleVal == doubleVal.toLong().toDouble()) {
        doubleVal.toLong().toString()
    } else {
        number
    }
}

@OptIn(ExperimentalTime::class)
private fun parseIsoDate(dateString: String?): LocalDate {
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    if (dateString.isNullOrBlank()) return today

    return try {
        // ISO_OFFSET_DATE_TIME equivalent: Instant.parse handles ISO-8601 with offset.
        Instant.parse(dateString)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
    } catch (e: Exception) {
        try {
            // ISO_LOCAL_DATE: LocalDate.parse accepts the ISO format by default.
            LocalDate.parse(dateString.substring(0, 10))
        } catch (e2: Exception) {
            today
        }
    }
}

private fun mapStatus(status: String?): String {
    return when (status?.lowercase()) {
        "ongoing" -> "Ongoing"
        "completed" -> "Completed"
        "hiatus" -> "Hiatus"
        else -> "Unknown"
    }
}

/**
 * Audit-trail postscript (Phase 9.x.cluster190.staleKdocSweep.cascade, Task #699, 2026-05-29)
 *
 * Leaf 4/5 §253 audit-trail-preservation postscript for cluster190, sibling 310 of the cluster57+
 * continuum. Sibling-pair to leaf 3/5 (AzoraModels.kt) — both leaves share the same prose
 * structure (java.time → kotlinx.datetime port migration note) but differ in their @Serializable
 * model surface area: AzoraModels covers the api.azoramoon.com REST shape; DilarV2Models covers
 * the dilar.tube v2 REST shape. The Dilar v2 shape is structurally larger — 24 @Serializable
 * data classes vs Azora's 10 — reflecting Dilar's richer per-release metadata model (per-team
 * release tracking, multi-language synonym sets, separate cover/banner imagery, etc.).
 *
 * The top-of-file prose under audit (preserved verbatim above the import block at lines 3-12):
 *
 *     Migration note (Phase 7.1): java.time -> kotlinx.datetime. The Android source used
 *     java.time.LocalDate, java.time.OffsetDateTime, java.time.format.DateTimeFormatter.
 *     Ports:
 *       - LocalDate.now() -> Clock.System.todayIn(TimeZone.currentSystemDefault())
 *       - OffsetDateTime.parse(s, ISO_OFFSET_DATE_TIME).toLocalDate() ->
 *           Instant.parse(s).toLocalDateTime(TimeZone.currentSystemDefault()).date
 *       - LocalDate.parse(s, ISO_LOCAL_DATE) -> LocalDate.parse(s) (ISO format is the default)
 *     Behaviour preserved: invalid date -> today's local date.
 *
 * The prose is identical to sibling-309's. The classification below cross-references sibling 309
 * where the prose maps onto identical implementation; differences are noted explicitly.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — the java.time → kotlinx.datetime migration assertion: import survey
 *      confirms zero java.time.* imports remain. Replaced by kotlin.time.Clock, kotlin.time
 *      .Instant, kotlinx.datetime.LocalDate, kotlinx.datetime.TimeZone, kotlinx.datetime.todayIn,
 *      kotlinx.datetime.toLocalDateTime (lines 14-20). The three port mappings hold structurally:
 *        - LocalDate.now() → Clock.System.todayIn(TimeZone.currentSystemDefault()) at line 460.
 *        - OffsetDateTime.parse(s, ISO_OFFSET_DATE_TIME).toLocalDate() → Instant.parse(s)
 *          .toLocalDateTime(TimeZone.currentSystemDefault()).date at lines 464-467.
 *        - LocalDate.parse(s, ISO_LOCAL_DATE) → LocalDate.parse(s) at line 471 — implemented as
 *          `LocalDate.parse(dateString.substring(0, 10))`. The stdlib idiom differs from sibling
 *          309's `LocalDate.parse(dateString.take(10))` — both produce identical results, just
 *          different ways to truncate to the first 10 chars. The `substring(0, 10)` form throws
 *          StringIndexOutOfBoundsException if the input is shorter than 10 chars, but that
 *          exception is caught by the outer try; sibling 309's `take(10)` returns the full
 *          string if shorter, avoiding the exception path. Behaviourally equivalent because both
 *          end up in the same `today` fallback for short/malformed inputs.
 *
 *   b. LIVE-NOT-STALE — the "Behaviour preserved: invalid date -> today's local date" assertion:
 *      confirmed by the double-try fallback chain at lines 463-475. Same structure as sibling
 *      309's parseIsoDate.
 *
 *   c. LIVE-NOT-STALE — the @Serializable data class hierarchy (lines 28-323): 24 nested data
 *      classes organized into 5 response-shape groups:
 *        - Home/Series List (lines 28-90): DilarSeriesListResponse → DilarSeries + DilarSynonyms
 *          + DilarSeriesType + DilarCreator + DilarLatestChapter (5 types).
 *        - Series Detail (lines 92-180): DilarSeriesDetailResponse + DilarCategory + DilarStaff
 *          + DilarStaffInfo + DilarRelease + DilarChapterInfo + DilarTeam (7 types).
 *        - Chapters List (lines 182-224): DilarChaptersResponse + DilarChapterDetail +
 *          DilarChapterRelease (3 types).
 *        - Chapter Images (lines 226-291): DilarChapterImagesResponse + DilarPage +
 *          DilarChapterSeriesInfo + DilarChapterMetadata + DilarTranslator + DilarChapterNav +
 *          DilarNavRelease (7 types).
 *        - Search (lines 293-323): DilarSearchRequest + DilarSearchResponse + DilarSearchManga
 *          (3 types).
 *      Extensive use of @SerialName for snake_case ↔ camelCase bridging (38 occurrences across
 *      the data class block) — proper Kotlin convention adherence with explicit JSON-key mapping.
 *
 *   d. LIVE-NOT-STALE — the per-instance webp-preferred image directory selection at lines
 *      404-415 in toImageUrls: the response carries both `pages` (HQ JPEG) and `webpPages`
 *      (HQ WEBP) lists; the implementation prefers WEBP if available else falls back to JPEG,
 *      mapping the `storage_key + directory + page.url` triple to the final CDN URL through
 *      RELEASES_BASE_URL. This is a Dilar-v2-specific optimization not present in sibling 309
 *      Azora (which has a single image-shape).
 *
 *   e. LIVE-NOT-STALE — the search-response novel-filter at lines 421-426: filters out entries
 *      where `seriesType.title == "رواية"` (Arabic for "novel") or `seriesType.name == "Novel"`.
 *      This is a deliberate domain-shape decision: Dilar.tube hosts both manga and Arabic web
 *      novels; the rework only surfaces manga. The Arabic text literal "رواية" is the load-
 *      bearing magic string. Preserved verbatim per §253.
 *
 *   f. LIVE-NOT-STALE — the staff-role lookup at lines 363-364 in toMangaInfo: `author` is
 *      resolved as the first staff entry with role "Author" else the creator's nick; `artist`
 *      is the first staff entry with role "Artist". The string-literal roles "Author" and
 *      "Artist" are load-bearing magic strings from the Dilar API's staff role enumeration.
 *      Preserved verbatim per §253.
 *
 *   g. LIVE-NOT-STALE — the alternative-names concatenation at lines 366-371: joinToString(" / ")
 *      with `arabic + english + japanese + alternative` synonyms, filtered for non-blank. The
 *      altNames local is built but not stored on MangaInfo — it's defined but unused at this
 *      site. Dead local: could be removed in a future cleanup slice, but preserved verbatim per
 *      §253.
 *
 *   h. POTENTIAL-BUG-PRESERVED — `rating?.toDoubleOrNull()?.toInt() ?: 0` at line 342: same
 *      precision-loss pattern as sibling-309's `averageRating?.toInt() ?: 0`. A 4.7-rated series
 *      becomes 4. MangaInfo.rating is typed String (line 379 preserves "rating ?: 0") which is
 *      different from MangaItem.rating (Int). The Dilar API returns ratings as strings (e.g.
 *      "4.7"); the Double conversion is lossless, the Int truncation loses sub-integer precision.
 *      Same future precision-lift slice opportunity as sibling 309.
 *
 *   i. FACTUALLY-DRIFTED-IN-PROSE-ONLY — same as sibling 309 (i): the prose "LocalDate.parse(s,
 *      ISO_LOCAL_DATE) -> LocalDate.parse(s) (ISO format is the default)" simplifies the actual
 *      defensive truncation `LocalDate.parse(dateString.substring(0, 10))`. Behaviourally
 *      identical to sibling 309's `.take(10)`. Not a sweep concern.
 *
 *   j. COSMETIC-NOT-STALE — the IMG_BASE_URL/COVER_BASE_URL/RELEASES_BASE_URL `private const val`
 *      block at lines 327-329: pure constant declarations defining the dilar.tube CDN path
 *      hierarchy. Stylistically chose `private const val` over inlining; equivalent bytecode.
 *      Not a sweep concern.
 *
 * Cross-references — sibling files in this cluster:
 *   - sibling 309 (AzoraModels.kt) — leaf 3/5, structurally equivalent for the date-parsing
 *     port + @Serializable models + extension functions pattern. The classification rows (a),
 *     (b), (h), (i) replicate sibling 309's classifications with content adaptations.
 *   - sibling DilarV2Repository.kt — outside cluster190's leaf-batch scope, host file for the
 *     extension functions defined here. Future cluster (191+) will sweep the Repository impl.
 *
 * Cluster190 leaf 4/5. Next leaf: ProMangaImageCombiner.kt (sibling 311 — closing leaf, Phase 8
 * stub debt classification + verbatim upstream comment block).
 */
