package me.manga.kira.sources_repositry.ar.promanga.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Main response for series list (home/search/popular)
@Serializable
data class ProMangaResponse(
    val data: List<ProMangaSeries> = emptyList(),
    val meta: ProMangaMeta? = null
)

@Serializable
data class ProMangaMeta(
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 28,
    val pages: Int = 1
)

// Main series/manga model
@Serializable
data class ProMangaSeries(
    val id: Int = 0,
    val title: String = "",
    val slug: String = "",
    val description: String = "",
    val type: String = "manhua", // manhua, manga, manhwa, novel
    val progress: String = "", // مستمر (ongoing), مكتمل (completed)
    val status: String = "approved",
    val thumbnail: String = "",
    @SerialName("slider_image")
    val sliderImage: String? = null,
    @SerialName("is_sensitive_image")
    val isSensitiveImage: Boolean = false,
    @SerialName("cdn_path")
    val cdnPath: String = "",
    @SerialName("google_drive_folder_id")
    val googleDriveFolderId: String = "",
    val metadata: ProMangaMetadata = ProMangaMetadata(),
    val likes: Int = 0,
    @SerialName("favorites_count")
    val favoritesCount: Int = 0,
    @SerialName("support_popularity")
    val supportPopularity: Int = 0,
    @SerialName("support_total")
    val supportTotal: Int = 0,
    @SerialName("supporter_count")
    val supporterCount: Int = 0,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
    @SerialName("series_views")
    val seriesViews: Int = 0,
    @SerialName("chapter_views")
    val chapterViews: Int = 0,
    val coverImage: String = "",
    val coverImageApp: ProMangaCoverImageApp? = null
)

@Serializable
data class ProMangaMetadata(
    val originalTitle: String? = null,
    val altTitles: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val viewStatus: String = "public",
    val descriptions: ProMangaDescriptions = ProMangaDescriptions(),
    val coverImage: String? = null,
    val socialImage: String? = null,
    val volumes: List<ProMangaVolume> = emptyList(),
    val coverImageApp: ProMangaCoverImageApp? = null,
    @SerialName("cover_image_app")
    val coverImageAppAlt: ProMangaCoverImageApp? = null,
    val exclusiveLockStrategy: String? = null,
    val exclusiveLockMode: String? = null,
    val exclusiveLockCount: Int? = null,
    val exclusivePrice: ProMangaExclusivePrice? = null
)

@Serializable
data class ProMangaDescriptions(
    val ar: String = "",
    val en: String = ""
)

@Serializable
data class ProMangaVolume(
    val number: String = "",
    val nameAr: String? = null,
    val nameEn: String? = null
)

@Serializable
data class ProMangaCoverImageApp(
    val mobile: String = "",
    val desktop: String = "",
    val card: ProMangaCardImages? = null
)

@Serializable
data class ProMangaCardImages(
    val mobile: String = "",
    val desktop: String = ""
)

@Serializable
data class ProMangaExclusivePrice(
    val ar: Int = 0,
    val en: Int = 0
)

// Response for single series details
@Serializable
data class ProMangaSeriesResponse(
    val series: ProMangaSeries? = null,
    val chapters: List<ProMangaChapterInfo> = emptyList(),
    val totalChapters: Int = 0
)

// Response for chapter content

@Serializable
data class ProMangaChapterInfo(
    val id: Int = 0,
    val number: String = "0", // Changed to String to handle decimal chapters
    val title: String = "",
    val slug: String = "",
    @SerialName("series_id")
    val seriesId: Int = 0,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
    val status: String = "approved",
    @SerialName("is_locked")
    val isLocked: Boolean = false,
    val views: Int = 0
)

@Serializable
data class ProMangaChapterRef(
    val id: Int = 0,
    val number: String = "0",
    val slug: String = ""
)

@Serializable
data class ProMangaChapter(
    val id: Int = 0,
    val number: String = "0",
    val title: String = "",
    val slug: String = "",
    @SerialName("series_id")
    val seriesId: Int = 0,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
    val status: String = "approved",
    val images: List<ProMangaImage> = emptyList(),
    val views: Int = 0,
    @SerialName("is_locked")
    val isLocked: Boolean = false
)

@Serializable
data class ProMangaImage(
    val id: Int = 0,
    val url: String = "",
    val order: Int = 0,
    val width: Int = 0,
    val height: Int = 0
)

sealed class ImageCombinerState {
    data class SingleImageReady(
        val imageUrl: String,
        val currentIndex: Int,
        val totalImages: Int
    ) : ImageCombinerState()

    data class Complete(
        val totalImagesEmitted: Int
    ) : ImageCombinerState()

    data class Error(
        val message: String,
        val imagesEmittedSoFar: Int
    ) : ImageCombinerState()
}