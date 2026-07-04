package me.manga.yamiapk.sources_repositry.ar.dilar.v2


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

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
        ratingCount = ratesCount.toString(),
        description = summary ?: "",
        otherNames = altNames,
        author = author,
        artist = artist,
        genres = categories.map { it.name },
        tags = emptyList(),
        yearOfProduction = startDate?.take(4) ?: "",
        status = mapStatus(translationStatus),
        favoritesCount = seriesViews.toString(),
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

private fun parseIsoDate(dateString: String?): LocalDate {
    if (dateString.isNullOrBlank()) return LocalDate.now()

    return try {
        OffsetDateTime.parse(dateString, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDate()
    } catch (e: DateTimeParseException) {
        try {
            LocalDate.parse(dateString.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e2: Exception) {
            LocalDate.now()
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