package me.manga.yamiapk.sources_repositry.en.tapastic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

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
            genres = buildGenres(), // ✅ HERE
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
        val uploadDate = parseDate(publishDate)

        return ChapterItem(
            number = chapterNumber,
            name = chapterTitle,
            url = "$baseUrl/episode/$id",
            date = LocalDate.now(),
            isDownloaded = false
        )
    }

    private fun parseDate(dateString: String): String {
        if (dateString.isBlank()) return ""
        return try {
            // Format: "2026-01-08T19:00:00Z"
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ROOT)
            val parsedDate = inputFormat.parse(dateString)
            parsedDate?.let { outputFormat.format(it) } ?: ""
        } catch (e: Exception) {
            // Try with timezone offset format
            try {
                val altFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.ROOT).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ROOT)
                val parsedDate = altFormat.parse(dateString)
                parsedDate?.let { outputFormat.format(it) } ?: ""
            } catch (e2: Exception) {
                ""
            }
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