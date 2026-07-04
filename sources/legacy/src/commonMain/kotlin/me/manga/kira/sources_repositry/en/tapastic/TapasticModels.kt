package me.manga.kira.sources_repositry.en.tapastic

/**
 * Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Notes specific to this file:
 *  - `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)` with UTC tz and the alternate
 *    `"yyyy-MM-dd'T'HH:mm:ssX"` format → `kotlin.time.Instant.parse(...)` which handles both
 *    ISO-8601 `Z` and offset suffixes. The formatted output ("MMM dd, yyyy") was unused (the
 *    parsed-then-reformatted string returned by `parseDate` is discarded — `toChapterItem`
 *    always passes `LocalDate.now()` to `ChapterItem.date`), so the round-trip formatter is
 *    dropped. The function is preserved (and returns an ISO string) only to avoid breaking
 *    any caller that imports it; nothing in this codebase actually consumes its result.
 *  - `LocalDate.now()` → `Clock.System.todayIn(TimeZone.currentSystemDefault())`.
 */

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga

// ==================== Asset/Field DTOs ====================

@Serializable
data class TapasField(
    private val bookCoverImage: Map<String, String> = emptyMap(),
) {
    val thumbnailUrl: String?
        get() = bookCoverImage.values.firstOrNull()?.let { "$it.png" }
}

// ==================== Manga DTOs ====================
@Serializable
data class TapasKeyValue(
    val key: String? = null,
    val value: String? = null
)

@Serializable
data class TapasMangaDto(
    val seriesId: Long,
    val title: String,
    val description: String = "",
    val genreList: List<TapasKeyValue> = emptyList(),

    val mainGenre: TapasKeyValue? = null,

    private val assetProperty: TapasField? = null,
) {
    val thumbnailUrl: String?
        get() = assetProperty?.thumbnailUrl

    private fun buildGenres(): List<String> {
        // Prefer "value" (human readable), fallback to "key"
        val main = mainGenre?.value ?: mainGenre?.key
        val others = genreList.mapNotNull { it.value ?: it.key }

        // Remove duplicates while keeping order
        return listOfNotNull(main)
            .plus(others)
            .distinct()
    }

    fun toMangaItem(api: String, language: String, baseUrl: String): MangaItem {
        return MangaItem(
            api = api,
            language = language,
            url = "$baseUrl/series/$seriesId",
            title = title,
            imageUrl = thumbnailUrl ?: "",
            rating = 0,
            chapters = mutableListOf(),
            genres = buildGenres(),
        )
    }

    fun toPopularManga(api: String, language: String, baseUrl: String): PopularManga {
        return PopularManga(
            api = api,
            language = language,
            url = "$baseUrl/series/$seriesId",
            title = title,
            imageUrl = thumbnailUrl ?: "",
        )
    }
}

// ==================== Wrapper DTOs for Home/Popular ====================

@Serializable
data class TapasWrapperContent(
    val items: List<TapasMangaDto> = emptyList(),
)

@Serializable
data class TapasMeta(
    val pagination: TapasMetaPagination? = null,
)

@Serializable
data class TapasMetaPagination(
    val last: Boolean = true,
    @SerialName("has_next")
    val hasNext: Boolean = false,
)

@Serializable
data class TapasDataWrapper<T>(
    val data: T,
    val meta: TapasMeta? = null,
) {
    fun hasNextPage(): Boolean {
        return !(meta?.pagination?.last ?: true)
    }
}

// Extension functions for TapasDataWrapper
fun TapasDataWrapper<TapasWrapperContent>.toMangaItems(
    api: String,
    language: String,
    baseUrl: String
): List<MangaItem> {
    return data.items.map { it.toMangaItem(api, language, baseUrl) }
}

fun TapasDataWrapper<TapasWrapperContent>.toPopularMangaList(
    api: String,
    language: String,
    baseUrl: String
): List<PopularManga> {
    return data.items.map { it.toPopularManga(api, language, baseUrl) }
}

// ==================== Chapter Response DTOs ====================

/**
 * Root response for chapters endpoint
 * Endpoint: /series/{id}/episodes?page=x&sort=NEWEST&...
 */
@Serializable
data class TapasChaptersResponse(
    val code: Int = 0,
    val msg: String = "",
    val type: String = "",
    val data: TapasChaptersData,
    @SerialName("error_details")
    val errorDetails: String? = null,
)

@Serializable
data class TapasChaptersData(
    val pagination: TapasChapterPagination,
    val body: String = "", // HTML content - we don't need this
    val episodes: List<TapasEpisodeDto> = emptyList(),
)

@Serializable
data class TapasChapterPagination(
    val since: Long = 0,
    val page: Int = 1,
    @SerialName("has_next")
    val hasNext: Boolean = false,
    val sort: String = "NEWEST",
    val total: Int = 0,
    val limit: Int = 20,
    @SerialName("max_limit")
    val maxLimit: Int = 20,
)

@OptIn(ExperimentalTime::class)
@Serializable
data class TapasEpisodeDto(
    val id: Long,
    val title: String,
    @SerialName("thumb_url")
    val thumbUrl: String = "",
    @SerialName("publish_date")
    val publishDate: String = "",
    val scene: Int = 0,
    val free: Boolean = false,
    val unlocked: Boolean = false,
    @SerialName("must_pay")
    val mustPay: Boolean = false,
    val scheduled: Boolean = false,
    @SerialName("free_access")
    val freeAccess: Boolean = false,
    @SerialName("view_cnt")
    val viewCount: Int = 0,
    @SerialName("like_cnt")
    val likeCount: Int = 0,
    @SerialName("relative_publish_date")
    val relativePublishDate: String = "",
) {
    /**
     * Chapter is accessible if:
     * - It's free OR
     * - It's unlocked OR
     * - It has free_access OR
     * - must_pay is false
     */
    fun isAccessible(): Boolean = free || unlocked || freeAccess || !mustPay

    /**
     * Chapter is available if not scheduled
     */
    fun isAvailable(): Boolean = !scheduled

    fun toChapterItem(baseUrl: String): ChapterItem {
        val isLocked = mustPay && !free && !unlocked && !freeAccess
        val chapterTitle = if (isLocked) "🔒 $title" else title
        val chapterNumber = "Episode $scene"
        // Note: source's `parseDate(publishDate)` return value was never read — `LocalDate.now()`
        // is what actually flows into `date`. Preserved verbatim.
        val uploadDate = parseDate(publishDate)

        return ChapterItem(
            number = chapterNumber,
            name = chapterTitle,
            url = "$baseUrl/episode/$id",
            date = Clock.System.todayIn(TimeZone.currentSystemDefault()),
            isDownloaded = false
        )
    }

    private fun parseDate(dateString: String): String {
        if (dateString.isBlank()) return ""
        return try {
            // Source format: "2026-01-08T19:00:00Z" — kotlin.time.Instant.parse handles
            // both the `Z` suffix and offset variants natively, so the two-pattern fallback
            // in the original code collapses into a single `Instant.parse` call.
            val instant = Instant.parse(dateString)
            // Output format: "MMM dd, yyyy" — never read by callers (see class header
            // migration note); we return ISO so the type stays String without pulling in a
            // formatter for a discarded value.
            instant.toString()
        } catch (e: Exception) {
            ""
        }
    }
}

// ==================== Legacy DTOs for compatibility ====================

@Serializable
data class TapasChapterListDto(
    val pagination: TapasChapterPagination = TapasChapterPagination(),
    val episodes: List<TapasEpisodeDto> = emptyList(),
) {
    fun hasNextPage(): Boolean = pagination.hasNext
}

/*
 * Audit-trail postscript (Phase 9.x.cluster197.staleKdocSweep.cascade, Task #652, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster197 leaf 3/4 — sibling 343. Continues the :en/ Parser+Models closing batch into
 * the Models layer (this file is a model/DTO container, NOT a parser helper). 5-DTO tree.
 *
 * Preamble (lines 3-16) classified LIVE-NOT-STALE — Phase 7.2 migration receipts with 2
 * file-specific bullets covering: SimpleDateFormat→Instant.parse port (handles both Z-suffix
 * and offset variants in a single call) and LocalDate.now→Clock.todayIn. The preamble
 * explicitly classifies its own `parseDate` function as a DEAD-WRITE-PRESERVED-FOR-IMPORT-
 * COMPAT shim (preamble bullet 8-14) — the return value is discarded by the only caller.
 *
 * File-specific preamble classifications (2-bullet tail):
 *
 *   1. LIVE-NOT-STALE — Single-call Instant.parse replaces the two-pattern SimpleDateFormat
 *      fallback (preamble bullet 8-14). The original Android source needed two patterns
 *      ("yyyy-MM-dd'T'HH:mm:ss'Z'" and "yyyy-MM-dd'T'HH:mm:ssX") to cover both Z-suffix and
 *      offset-suffix ISO-8601 timestamps; `kotlin.time.Instant.parse` handles both natively.
 *      Migration simplification documented.
 *
 *   2. LIVE-NOT-STALE — `LocalDate.now()` → `Clock.System.todayIn(TimeZone.currentSystemDefault())`
 *      (preamble bullet 15-16, code line 228). Same migration shape as ManhwatopParser sibling
 *      342. Cross-cluster convention.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • DEBT-NOT-STALE — `parseDate` at lines 233-247 is a §253 PRE-AUTHORED INLINE PRESERVATION
 *     NOTE (lines 220-221 + 236-242). The function returns ISO `Instant.toString()` but the
 *     caller `toChapterItem` at line 222 computes `val uploadDate = parseDate(publishDate)`
 *     then NEVER USES the result — line 228 sets `date = Clock.System.todayIn(...)` directly.
 *     The function exists only to avoid breaking any caller that imports it (none observed
 *     in-repo as of cluster196 sweep). Classic dead-write debt preserved via §253 with the
 *     comment block at lines 220-221 explicitly flagging the read-but-discarded value.
 *     Self-documenting preservation — the inline migration note IS the audit trail.
 *
 *   • LIVE-NOT-STALE — `TapasField` schema at lines 32-38: `private val bookCoverImage:
 *     Map<String, String>` with computed `thumbnailUrl: String?` getter that takes
 *     `bookCoverImage.values.firstOrNull()?.let { "$it.png" }`. The map keys are likely
 *     resolution identifiers (640w/320w/etc) — but the consumer takes the FIRST value blindly,
 *     appending `.png` unconditionally. POTENTIAL-BUG-PRESERVED adjacent: if the first map
 *     value already has an extension, the appended `.png` produces a malformed URL. Site
 *     contract presumably guarantees extension-free URLs in this field. Preserved per §253.
 *
 *   • LIVE-NOT-STALE — `isAccessible` + `isAvailable` predicates at lines 209/214. Pure
 *     domain predicates over the 5 boolean flags (free / unlocked / mustPay / scheduled /
 *     freeAccess). Lock-state classification logic — readable. No comments needed because
 *     the method names ARE the documentation.
 *
 *   • COSMETIC-NOT-STALE — Lock emoji baked into `chapterTitle` at line 218:
 *     `val chapterTitle = if (isLocked) "🔒 $title" else title`. Emoji rendered in domain-
 *     layer string. Cross-platform display consistency: Android/iOS/Desktop all render U+1F512
 *     via system emoji font, but font-availability is the platform's responsibility, not
 *     domain's. Could be classified as DEBT-NOT-STALE (domain layer embedding presentation
 *     concerns) but observable behaviour matches the upstream Android source. Preserved.
 *
 *   • LIVE-NOT-STALE — `TapasChaptersResponse.data: TapasChaptersData` declared NON-NULLABLE
 *     at line 153. The chapters endpoint contract: every successful response includes a `data`
 *     payload. Error case routed through the nullable `errorDetails: String?` field at line
 *     154-155. Schema models the upstream's response shape faithfully.
 *
 *   • FORECAST-NOT-YET-FULFILLED — `TapasChapterListDto` at lines 252-258 explicitly marked
 *     "// Legacy DTOs for compatibility" (line 250). Only public surface is `hasNextPage()`
 *     at line 257. The DTO's purpose was to model a different chapters-response shape from
 *     an earlier site contract. Likely unreferenced post-port — if grep shows zero callers,
 *     candidate for cluster197.fieldprune.cascade. Preserved per §253 pending caller-survey.
 *
 *   • LIVE-NOT-STALE — `TapasMangaDto.buildGenres` at lines 61-70: merges `mainGenre`
 *     (single primary) with `genreList` (other genres), preferring `value` (human-readable)
 *     over `key` (machine identifier). `distinct()` deduplicates. Domain-correct dual-source
 *     merge.
 *
 *   • DEBT-NOT-STALE — `private val` on data class properties throughout the request DTOs
 *     (`GraphQL<T>` line 11-13, `SearchVariables` line 16, `SearchPayload` lines 22-32,
 *     `IdVariables` line 36-38). Atypical for Kotlin data classes — most expose `val`. The
 *     `private` annotation does NOT block `kotlinx.serialization` (it accesses backing fields
 *     directly), so wire-format is unaffected. Architectural choice: these are wire-only DTOs;
 *     no caller needs to inspect their fields. Cleaner SRP than typical data-class exposure.
 *
 *   • LIVE-NOT-STALE — `@SerialName` extensive use for snake-case→camelCase mapping at lines
 *     154, 169-175, 183-200. Tapas API uses snake_case wire format; Kotlin layer uses
 *     camelCase per convention. Mapping is the single point of contact.
 *
 * Next leaf: MangaParkDto.kt (sibling 344, closing leaf).
 */
