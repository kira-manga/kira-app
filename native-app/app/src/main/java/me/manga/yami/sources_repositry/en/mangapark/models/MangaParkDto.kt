package me.manga.yamiapk.sources_repositry.en.mangapark.models

// File: models/MangaParkModels.kt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// GraphQL Request Models
@Serializable
data class GraphQL<T>(
    private val variables: T,
    private val query: String,
)

@Serializable
data class SearchVariables(
    private val select: SearchPayload
)

@Serializable
data class SearchPayload(
    @SerialName("word") private val query: String? = null,
    private val incGenres: List<String>? = null,
    private val excGenres: List<String>? = null,
    private val incTLangs: List<String>? = null,
    private val incOLangs: List<String>? = null,
    private val sortby: String? = null,
    private val chapCount: String? = null,
    private val origStatus: String? = null,
    private val siteStatus: String? = null,
    private val page: Int,
    private val size: Int,
)

@Serializable
data class IdVariables(
    private val id: String
)

// Response Models
@Serializable
data class Data<T>(val data: T)

@Serializable
data class Items<T>(val items: List<T>)

// Search Response Models
typealias SearchResponse = Data<SearchComics>
typealias DetailsResponse = Data<ComicNode>
typealias ChapterListResponse = Data<ChapterList>
typealias PageListResponse = Data<ChapterPages>

@Serializable
data class SearchComics(
    @SerialName("get_searchComic") val searchComics: Items<Data<MangaParkManga>>,
)

@Serializable
data class ComicNode(
    @SerialName("get_comicNode") val comic: Data<MangaParkManga>,
)

@Serializable
data class MangaParkManga(
    val id: String,
    val name: String?,
    val altNames: List<String>? = null,
    val authors: List<String>? = null,
    val artists: List<String>? = null,
    val genres: List<String>? = null,
    val originalStatus: String? = null,
    val uploadStatus: String? = null,
    val summary: String? = null,
    val extraInfo: String? = null,
    @SerialName("urlCoverOri") val cover: String? = null,
    val urlPath: String,
    @SerialName("max_chapterNode") val latestChapter: Data<ImageFiles>? = null,
    @SerialName("first_chapterNode") val firstChapter: Data<ImageFiles>? = null,
)

@Serializable
data class ChapterList(
    @SerialName("get_comicChapterList") val chapterList: List<Data<MangaParkChapter>>,
)

@Serializable
data class MangaParkChapter(
    val id: String,
    @SerialName("dname") val displayName: String? = null,
    val title: String? = null,
    val dateCreate: Long? = null,
    val dateModify: Long? = null,
    val urlPath: String,
    val srcTitle: String? = null,
    val userNode: Data<Name>? = null,
    val dupChapters: List<Data<MangaParkChapter>> = emptyList(),
)

@Serializable
data class Name(val name: String)

@Serializable
data class ChapterPages(
    @SerialName("get_chapterNode") val chapterPages: Data<ImageFiles>,
)

@Serializable
data class ImageFiles(
    val imageFile: UrlList,
)

@Serializable
data class UrlList(
    val urlList: List<String>,
)