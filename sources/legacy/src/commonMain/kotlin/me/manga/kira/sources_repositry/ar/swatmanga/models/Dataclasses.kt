package me.manga.kira.sources_repositry.ar.swatmanga.models


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Base response for paginated results
@Serializable
data class SwatSeriesListResponse(
    val count: Int,
    val next: String? = null,
    val previous: String? = null,
    val results: List<SwatMangaSeries>
)

@Serializable
data class SwatSearchResponse(
    val count: Int,
    val next: String? = null,
    val previous: String? = null,
    val results: List<SwatMangaDetails>
)

@Serializable
data class SwatChaptersResponse(
    val count: Int,
    val next: String? = null,
    val previous: String? = null,
    val results: List<SwatChapter>
)

// Series model for releases/home page
@Serializable
data class SwatMangaSeries(
    @SerialName("serie_id")
    val serieId: Long,
    val title: String,
    @SerialName("latest_chapter_updated_at")
    val latestChapterUpdatedAt: String,
    val slug: String,
    val type: SwatType,
    val status: SwatStatus,
    val genres: List<SwatGenre>,
    val poster: SwatPoster,
    @SerialName("is_hot")
    val isHot: Boolean,
    @SerialName("views_count")
    val viewsCount: Int,
    val rating: String,
    val chapters: List<SwatChapterSummary>? = null
)

// Detailed series model for search/details
@Serializable
data class SwatMangaDetails(
    val id: Long,
    val title: String,
    val slug: String,
    val letter: String? = null,
    val alternative: String? = null,
    val story: String? = null,
    val type: SwatType,
    val status: SwatStatus,
    val author: SwatAuthor? = null,
    val artist: SwatArtist? = null,
    val published: String? = null,
    val genres: List<SwatGenre>,
    val poster: SwatPoster,
    val cover: SwatCover? = null,
    @SerialName("is_hot")
    val isHot: Boolean,
    val rating: String,
    @SerialName("is_favorite")
    val isFavorite: Boolean,
    @SerialName("is_followed")
    val isFollowed: Boolean,
    @SerialName("chapters_count")
    val chaptersCount: Int? = null,
    @SerialName("ratings_count")
    val ratingsCount: Int? = null,
    @SerialName("views_count")
    val viewsCount: Int,
    @SerialName("followers_count")
    val followersCount: Int? = null,
    @SerialName("favorites_count")
    val favoritesCount: Int? = null,
    @SerialName("donations_count")
    val donationsCount: Int? = null,
    @SerialName("allow_comments")
    val allowComments: Boolean? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("created_at_humanized")
    val createdAtHumanized: String,
    @SerialName("created_by")
    val createdBy: SwatUser,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("updated_at_humanized")
    val updatedAtHumanized: String,
    @SerialName("updated_by")
    val updatedBy: SwatUser? = null,
    @SerialName("my_rating")
    val myRating: Int? = null,
    val translator: String? = null,
    val editor: String? = null
)

// Chapter models
@Serializable
data class SwatChapter(
    val id: Long,
    val title: String,
    val slug: String,
    val chapter: String,
    val serie: Long,
    val published: String? = null,
    @SerialName("views_count")
    val viewsCount: Int,
    @SerialName("is_read")
    val isRead: Boolean,
    @SerialName("created_at_humanized")
    val createdAtHumanized: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("created_by")
    val createdBy: SwatUser,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("updated_by")
    val updatedBy: SwatUser? = null
)

@Serializable
data class SwatChapterSummary(
    val id: Long,
    val title: String,
    val chapter: String
)

@Serializable
data class SwatChapterDetails(
    val id: Long,
    val title: String,
    val slug: String,
    val chapter: String,
    val serie: SwatSerieInfo,
    val published: String? = null,
    @SerialName("views_count")
    val viewsCount: Int,
    @SerialName("next_chapter_id")
    val nextChapterId: Long? = null,
    @SerialName("previous_chapter_id")
    val previousChapterId: Long? = null,
    @SerialName("images_count")
    val imagesCount: Int,
    @SerialName("is_read")
    val isRead: Boolean,
    @SerialName("allow_comments")
    val allowComments: Boolean,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("created_by")
    val createdBy: SwatUser,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("updated_by")
    val updatedBy: SwatUser? = null,
    val images: List<SwatChapterImage>,
    val translator: String? = null,
    val editor: String? = null
)

@Serializable
data class SwatSerieInfo(
    val id: Long,
    val title: String,
    val slug: String,
    val type: SwatType,
    @SerialName("type_id")
    val typeId: Int,
    val status: SwatStatus,
    @SerialName("status_id")
    val statusId: Int,
    val genres: List<SwatGenreWithCount>,
    val poster: SwatPoster,
    @SerialName("is_hot")
    val isHot: Boolean,
    @SerialName("views_count")
    val viewsCount: Int,
    val rating: String,
    @SerialName("is_favorite")
    val isFavorite: Boolean,
    @SerialName("is_followed")
    val isFollowed: Boolean,
    val editor: String? = null,
    val translator: String? = null
)

@Serializable
data class SwatChapterImage(
    val image: String,
    val order: Int
)

// Supporting models
@Serializable
data class SwatType(
    val id: Int,
    val name: String
)

@Serializable
data class SwatStatus(
    val id: Int,
    val name: String
)

@Serializable
data class SwatGenre(
    val id: Int,
    val name: String
)

@Serializable
data class SwatGenreWithCount(
    val id: Int,
    val name: String,
    @SerialName("series_count")
    val seriesCount: Int
)

@Serializable
data class SwatAuthor(
    val id: Int,
    val name: String
)

@Serializable
data class SwatArtist(
    val id: Int,
    val name: String
)

@Serializable
data class SwatPoster(
    val thumbnail: String,
    val medium: String
)

@Serializable
data class SwatCover(
    val thumbnail: String,
    val medium: String
)

@Serializable
data class SwatUser(
    val id: Int,
    val username: String,
    val name: String,
    val avatar: SwatAvatar = SwatAvatar()
)

@Serializable
data class SwatAvatar(
    val medium: String? = null,
    val small: String? = null,
    val thumbnail: String? = null
)