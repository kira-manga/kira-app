package me.manga.yamiapk.sources_repositry.ar.azora

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

@Serializable
data class AzoraChapterNav(
    val id: Int,
    val slug: String,
    val number: Double
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
        ratingCount = postDetail.totalRatings.toString(),
        description = cleanHtmlContent(postDetail.postContent),
        otherNames = postDetail.alternativeTitles ?: "",
        author = postDetail.author ?: "",
        artist = postDetail.artist ?: "",
        genres = postDetail.genres.map { it.name },
        tags = emptyList(),
        yearOfProduction = "",
        status = postDetail.seriesStatus ?: "Unknown",
        favoritesCount = postDetail.count?.bookmarks?.toString() ?: "0",
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

private fun parseIsoDate(dateString: String?): LocalDate {
    if (dateString.isNullOrBlank()) return LocalDate.now()

    return try {
        OffsetDateTime.parse(dateString, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDate()
    } catch (e: DateTimeParseException) {
        try {
            LocalDate.parse(dateString.take(10), DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e2: Exception) {
            LocalDate.now()
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
        ratingCount = "0",
        description = "",
        otherNames = "",
        author = "",
        artist = "",
        genres = emptyList(),
        tags = emptyList(),
        yearOfProduction = "",
        status = "Unknown",
        favoritesCount = "0",
        chapters = mutableListOf()
    )
}