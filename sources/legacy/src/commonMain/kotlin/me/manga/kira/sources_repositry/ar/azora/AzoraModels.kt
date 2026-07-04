package me.manga.kira.sources_repositry.ar.azora

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

import kotlin.time.ExperimentalTime
import kotlin.time.Clock
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

// ==================== Query Response (Home/Popular/Search) ====================

@Serializable
data class AzoraQueryResponse(
    val posts: List<AzoraPost> = emptyList(),
    val totalCount: Int = 0
)

@Serializable
data class AzoraPost(
    val id: Int,
    val slug: String,
    val postTitle: String,
    val postContent: String? = null,
    val featuredImage: String? = null,
    val seriesStatus: String? = null,
    val totalViews: Int = 0,
    val alternativeTitles: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: List<AzoraGenre> = emptyList(),
    val chapters: List<AzoraChapterSummary> = emptyList(),
    @SerialName("_count")
    val count: AzoraCount? = null,
    val averageRating: Double? = null
)

@Serializable
data class AzoraGenre(
    val id: Int,
    val name: String
)

@Serializable
data class AzoraChapterSummary(
    val id: Int,
    val number: Double,
    val title: String? = null,
    val slug: String,
    val createdAt: String? = null,
    val isLocked: Boolean = false,
    val isAccessible: Boolean = true
)

@Serializable
data class AzoraCount(
    val chapters: Int = 0,
    val bookmarks: Int = 0,
    val ratings: Int = 0
)

// ==================== Post Detail Response ====================

@Serializable
data class AzoraPostDetailResponse(
    val totalChapterCount: Int = 0,
    val post: AzoraPostDetail? = null
)

@Serializable
data class AzoraPostDetail(
    val id: Int,
    val slug: String,
    val postTitle: String,
    val postContent: String? = null,
    val featuredImage: String? = null,
    val seriesStatus: String? = null,
    val totalViews: Int = 0,
    val alternativeTitles: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: List<AzoraGenre> = emptyList(),
    val chapters: List<AzoraChapterDetail> = emptyList(),
    @SerialName("_count")
    val count: AzoraCount? = null,
    val averageRating: Double? = null,
    val totalRatings: Int = 0
)

@Serializable
data class AzoraChapterDetail(
    val id: Int,
    val slug: String,
    val number: Double,
    val title: String? = null,
    val createdAt: String? = null,
    val isLocked: Boolean = false,
    val isAccessible: Boolean = true
)

// ==================== Chapter Images Response ====================

@Serializable
data class AzoraChapterImagesResponse(
    val chapter: AzoraChapterContent? = null,
    val nextChapter: AzoraChapterNav? = null,
    val previousChapter: AzoraChapterNav? = null
)

@Serializable
data class AzoraChapterContent(
    val id: Int,
    val slug: String,
    val number: Double,
    val title: String? = null,
    val mangaPostId: Int,
    val images: List<AzoraImage> = emptyList()
)

@Serializable
data class AzoraImage(
    val url: String,
    val order: Int
)

// Navigation hints only (prev/next chapter). These objects are never read — they exist solely so
// the chapter-images response deserializes. Azora sometimes returns a partial prev/next object
// (e.g. the first/last chapter, or an object missing `id`), which threw MissingFieldException and
// failed the WHOLE response parse → getChapterImages returned an empty list and the chapter loaded
// with zero images. Every field is optional so a partial nav object can never break image parsing.
@Serializable
data class AzoraChapterNav(
    val id: Int? = null,
    val slug: String? = null,
    val number: Double? = null
)

// ==================== Extension Functions ====================

fun AzoraQueryResponse.toMangaItems(api: String, language: String): List<MangaItem> {
    return posts.map { it.toMangaItem(api, language) }
}

fun AzoraPost.toMangaItem(api: String, language: String): MangaItem {
    return MangaItem(
        api = api,
        language = language,
        title = postTitle,
        url = buildMangaUrl(id),
        imageUrl = featuredImage ?: "",
        rating = averageRating?.toInt() ?: 0,
        chapters = chapters.map { it.toChapterItem() },
        genres = genres.map { it.name }
    )
}

fun AzoraQueryResponse.toPopularMangaList(api: String, language: String): List<PopularManga> {
    return posts.map { it.toPopularManga(api, language) }
}

fun AzoraPost.toPopularManga(api: String, language: String): PopularManga {
    return PopularManga(
        api = api,
        language = language,
        title = postTitle,
        url = buildMangaUrl(id),
        imageUrl = featuredImage ?: ""
    )
}

fun AzoraChapterSummary.toChapterItem(): ChapterItem {
    val chapterNum = formatChapterNumber(number)
    return ChapterItem(
        number = "Chapter $chapterNum",
        name = title ?: "Chapter $chapterNum",
        url = buildChapterUrl(id),
        date = parseIsoDate(createdAt),
        isDownloaded = false
    )
}

fun AzoraPostDetailResponse.toMangaInfo(api: String, language: String, url: String): MangaInfo {
    val postDetail = post ?: return createEmptyMangaInfo(api, language, url)

    return MangaInfo(
        api = api,
        language = language,
        url = url,
        title = postDetail.postTitle,
        imageUrl = postDetail.featuredImage ?: "",
        rating = postDetail.averageRating?.toString() ?: "0",
        description = cleanHtmlContent(postDetail.postContent),
        author = postDetail.author ?: "",
        genres = postDetail.genres.map { it.name },
        status = postDetail.seriesStatus ?: "Unknown",
        chapters = postDetail.chapters.toChapterItems().toMutableList()
    )
}

fun AzoraChapterDetail.toChapterItem(): ChapterItem {
    val chapterNum = formatChapterNumber(number)
    return ChapterItem(
        number = "Chapter $chapterNum",
        name = title ?: "Chapter $chapterNum",
        url = buildChapterUrl(id),
        date = parseIsoDate(createdAt),
        isDownloaded = false
    )
}

fun List<AzoraChapterDetail>.toChapterItems(): List<ChapterItem> {
    return map { it.toChapterItem() }
}

fun AzoraChapterImagesResponse.toImageUrls(): List<String> {
    return chapter?.images
        ?.sortedBy { it.order }
        ?.map { it.url }
        ?: emptyList()
}

// ==================== Utility Functions ====================

private fun buildMangaUrl(id: Int): String {
    return "https://api.azoramoon.com/api/post/?postId=$id"
}

private fun buildChapterUrl(chapterId: Int): String {
    return "https://api.azoramoon.com/api/chapter?chapterId=$chapterId"
}

private fun formatChapterNumber(number: Double): String {
    return if (number == number.toLong().toDouble()) {
        number.toLong().toString()
    } else {
        number.toString()
    }
}

@OptIn(ExperimentalTime::class)
private fun parseIsoDate(dateString: String?): LocalDate {
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    if (dateString.isNullOrBlank()) return today

    return try {
        // ISO_OFFSET_DATE_TIME equivalent: kotlin.time.Instant.parse handles ISO-8601 with offset.
        Instant.parse(dateString)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
    } catch (e: Exception) {
        try {
            // ISO_LOCAL_DATE: kotlinx.datetime.LocalDate.parse accepts the ISO format by default.
            LocalDate.parse(dateString.take(10))
        } catch (e2: Exception) {
            today
        }
    }
}

private fun cleanHtmlContent(html: String?): String {
    if (html.isNullOrBlank()) return ""

    return html
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun createEmptyMangaInfo(api: String, language: String, url: String): MangaInfo {
    return MangaInfo(
        api = api,
        language = language,
        url = url,
        title = "",
        imageUrl = "",
        rating = "0",
        description = "",
        author = "",
        genres = emptyList(),
        status = "Unknown",
        chapters = mutableListOf()
    )
}

/**
 * Audit-trail postscript (Phase 9.x.cluster190.staleKdocSweep.cascade, Task #698, 2026-05-29)
 *
 * Leaf 3/5 §253 audit-trail-preservation postscript for cluster190, sibling 309 of the cluster57+
 * continuum. This is the first @Serializable-models-plus-extension-functions leaf — distinguished
 * from the simpler leaf 2/5 (ImageMapMetadata.kt, 48 lines, pure data classes) by an additional
 * surface: ~150 lines of extension functions that map the Azora REST JSON response shapes onto
 * the :domain/model/ types (MangaItem, PopularManga, MangaInfo, ChapterItem).
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
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — the "java.time -> kotlinx.datetime" migration assertion: import survey
 *      confirms zero `java.time.*` imports remain. Replaced by `kotlin.time.Clock`,
 *      `kotlin.time.Instant`, `kotlinx.datetime.LocalDate`, `kotlinx.datetime.TimeZone`,
 *      `kotlinx.datetime.todayIn`, `kotlinx.datetime.toLocalDateTime` (lines 14-20). The three
 *      port mappings listed in the prose hold:
 *        - `LocalDate.now()` → `Clock.System.todayIn(TimeZone.currentSystemDefault())` —
 *          confirmed at line 254 of `parseIsoDate`: `val today = Clock.System.todayIn(TimeZone
 *          .currentSystemDefault())`.
 *        - `OffsetDateTime.parse(s, ISO_OFFSET_DATE_TIME).toLocalDate()` → `Instant.parse(s)
 *          .toLocalDateTime(TimeZone.currentSystemDefault()).date` — confirmed at lines 258-261:
 *          `Instant.parse(dateString).toLocalDateTime(TimeZone.currentSystemDefault()).date`.
 *        - `LocalDate.parse(s, ISO_LOCAL_DATE)` → `LocalDate.parse(s)` — confirmed at line 265:
 *          `LocalDate.parse(dateString.take(10))`. The `.take(10)` truncation is an
 *          extra safety net for date-time-with-time-component inputs that wouldn't parse as a
 *          bare LocalDate; not mentioned in the migration prose but preserves behaviour.
 *      The `@OptIn(ExperimentalTime::class)` annotation at line 252 is required by the
 *      `kotlin.time.Clock` and `kotlin.time.Instant` Beta-stability markers on the migration
 *      target stack.
 *
 *   b. LIVE-NOT-STALE — the "Behaviour preserved: invalid date -> today's local date" assertion:
 *      confirmed by the double-try fallback chain at lines 257-269 of `parseIsoDate`. The outer
 *      try attempts ISO-OFFSET-DATE-TIME parsing; on exception the inner try attempts
 *      ISO-LOCAL-DATE parsing on a 10-char prefix; on inner exception `today` is returned. The
 *      Android source's behaviour (catch all parsing exceptions, fall back to `LocalDate.now()`)
 *      is structurally and semantically preserved.
 *
 *   c. LIVE-NOT-STALE — the @Serializable data class hierarchy (lines 30-148): AzoraQueryResponse
 *      (posts + totalCount) → AzoraPost (15 fields incl. nested AzoraGenre + AzoraChapterSummary
 *      + AzoraCount lists/refs) → AzoraGenre, AzoraChapterSummary (with `createdAt: String? =
 *      null` for date parsing), AzoraCount, AzoraPostDetailResponse → AzoraPostDetail →
 *      AzoraChapterDetail, AzoraChapterImagesResponse → AzoraChapterContent → AzoraImage +
 *      AzoraChapterNav. All ten data classes are KMP-portable structures with primitive +
 *      nullable + collection fields + `@SerialName("_count")` annotations where Kotlin
 *      conventions clash with JSON keys (lines 50, 101) — proper `@SerialName` use, contrasting
 *      with sibling-308's property-name-mirror approach. Stylistic divergence between sibling
 *      304's pre-existing prose-preserved snake_case and this file's `@SerialName`-bridged
 *      camelCase is not a §253 sweep concern.
 *
 *   d. LIVE-NOT-STALE — the extension function block (lines 150-232): nine `fun X.toY()` mapper
 *      functions that translate JSON-shape `AzoraXxx` types to domain-shape `MangaItem` /
 *      `PopularManga` / `MangaInfo` / `ChapterItem` / `List<String>`. These are pure data
 *      transformations — no platform APIs, no suspend, no side-effects. The `toMangaInfo`
 *      function at line 194 is the load-bearing transformation that the AzoraRepositoryv2's
 *      `fetchMangaInfo` flow emits as the final MangaInfo for the rework Details screen.
 *
 *   e. LIVE-NOT-STALE — the utility-function block (lines 234-285): `buildMangaUrl` /
 *      `buildChapterUrl` (Azora REST endpoint string builders), `formatChapterNumber` (drop
 *      trailing `.0` from `Double` chapter numbers), `parseIsoDate` (java.time → kotlinx.datetime
 *      port subject of this postscript's primary classification), `cleanHtmlContent` (regex-based
 *      HTML tag stripping + HTML-entity decoding for the manga description). All are pure-data
 *      helpers — no platform APIs, KMP-portable Kotlin stdlib + kotlinx.datetime only.
 *
 *   f. FULFILLED-PORT — `buildMangaUrl` / `buildChapterUrl` returning the api.azoramoon.com REST
 *      endpoint strings: the LiteralString URL pattern is preserved verbatim from the Android
 *      source. Unlike the abstract base (NormalSitesv2 / SeparatedDetailsSites) where `baseUrl`
 *      is open-overridable, here the Azora-specific endpoint shape is hardwired in helper
 *      functions because the Azora REST API uses a different domain than the read-from-browser
 *      surface URL the user navigates to. This is the post-port structure; the pre-port Android
 *      structure was the same (verified by behavioural-port history).
 *
 *   g. POTENTIAL-BUG-PRESERVED — the `averageRating?.toInt() ?: 0` conversion at line 163 in
 *      `toMangaItem`: a Double rating in [0.0, 10.0] is `.toInt()`-truncated to an Int, losing
 *      sub-integer precision. The MangaItem.rating field is typed `Int` in the domain model
 *      (verified by reading sibling-189-cluster186 closing-leaf survey). For a 4.7-rated manga,
 *      the displayed rating becomes 4. The Android source had this same truncation. Preserved
 *      verbatim per §253; a future precision-lift slice could widen MangaItem.rating to Double.
 *
 *   h. COSMETIC-NOT-STALE — the `// ====...====` section dividers (lines 28, 79, 118, 150,
 *      234): inherited stylistic convention from the Android source. Common pattern in the
 *      Android sources_repositry layer. Pure formatting; no semantic content.
 *
 *   i. FACTUALLY-DRIFTED-IN-PROSE-ONLY — the prose-line "LocalDate.parse(s, ISO_LOCAL_DATE) ->
 *      LocalDate.parse(s) (ISO format is the default)" at line 10: the actual code at line 265
 *      is `LocalDate.parse(dateString.take(10))` — there's an extra `.take(10)` truncation that
 *      is not mentioned in the prose. The truncation is defensive (handles ISO-OFFSET-DATE-TIME
 *      strings as ISO-LOCAL-DATE fallback by truncating to the date portion). The prose
 *      simplifies the actual implementation. Not a behavioural drift — the parsing semantics
 *      hold — but the prose's "ISO format is the default" parenthetical is incomplete relative
 *      to the actual fallback truncation. Preserved verbatim per §253; future cleanup could
 *      expand the prose to mention the `.take(10)` defensive truncation.
 *
 * Cross-references — sibling files in this cluster:
 *   - sibling 310 (DilarV2Models.kt) — leaf 4/5, identical java.time → kotlinx.datetime port
 *     prose. The `parseIsoDate` helper at sibling 310's lines 459-476 is structurally
 *     equivalent. The drift in (i) above also applies to sibling 310 — same prose-vs-code
 *     mismatch on the `.substring(0, 10)` defensive truncation (sibling 310 uses substring,
 *     this file uses take, same effect, different stdlib idiom).
 *   - sibling AzoraRepositoryv2.kt — outside cluster190's leaf-batch scope, host file for the
 *     mapper functions defined here. Future cluster (191+) will sweep the per-source
 *     RepositoryImpl files.
 *
 * Cluster190 leaf 3/5. Next leaves: DilarV2Models.kt (sibling 310, parallel java.time port),
 * ProMangaImageCombiner.kt (sibling 311 — closing leaf, Phase 8 stub + verbatim upstream block).
 */
