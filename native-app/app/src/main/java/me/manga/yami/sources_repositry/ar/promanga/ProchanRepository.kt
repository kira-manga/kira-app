package me.manga.yamiapk.sources_repositry.ar.promanga



import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil3.ImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.size.Dimension
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.sources_repositry.ar.promanga.models.*
import me.manga.yamiapk.sources_repositry.ar.promanga.models.imgs.ProMangaChapterResponse
import me.manga.yamiapk.sources_repositry.ar.promanga.models.imgs.ProMangaImageCombiner
import me.manga.yamiapk.sources_repositry.ar.promanga.models.info.ProInfo
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject


class ProchanRepository @Inject constructor(
    private val api: IMangaDataApiServices,
    private val dataStore: DataStoreHelper,
    private val sourcesRepository: SourcesDao,
    @ApplicationContext private val context: Context,
    private val applicationScope: CoroutineScope,

    private val imageLoader: ImageLoader
) : BaseMangaRepository() {

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }
    }

    override val BASE_URL: String get() = MangaSource.PROCHAN.BASEURL
    override val URL_VERSION: Int get() = 0
    override var baseUrl: String = ""
    override val API: String get() = MangaSource.PROCHAN.API
    override val LANGUAGE: String get() = MangaSource.PROCHAN.LANGUAGE.Language
    override val ICON: Int get() = MangaSource.PROCHAN.ICON
    override val PRIORITY = MangaSource.PROCHAN.PRIORITY


    private val apiUrl by lazy {
        baseUrl.ifBlank { BASE_URL }
//        "https://prochan.net/"
    }
    override var imgBaseUrl: String = ""
    override var imgUrlVersion: Int = 0

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override fun buildItemsImageRequest(
        context: Context,
        url: String,
        screenWidthPx: Int
    ): ImageRequest {
        val coilHeaders = NetworkHeaders.Builder()
            .apply { defaultHeaders.forEach(::add) }
            .build()

        return ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(coilHeaders)
            .crossfade(true)
            .build()
    }

    override suspend fun getBaseUrl(): String = withContext(Dispatchers.IO) {

        val url = sourcesRepository.getBaseUrlFor(API) ?: BASE_URL
        baseUrl = url
        return@withContext url.ifBlank { BASE_URL }
    }

    override fun buildImageRequest(
        context: Context,
        url: String,
        screenWidthPx: Int
    ): ImageRequest {
        val coilHeaders = NetworkHeaders.Builder()

            .apply { defaultHeaders.forEach(::add) }
            .build()


        return ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(coilHeaders)
            .apply {
                if (screenWidthPx != 0) {
                    size(Dimension.Pixels(screenWidthPx), Dimension.Undefined)
                }
            }
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(true)
            .build()
    }

    override val blackListGenres: Set<String>
        get() = setOf(
//            "Ecchi",
            "Adult",
            "Mature",
            "Hentai",
            "Smut",
//            "Lolicon"
        )

    override val sortTypes: Set<String>
        get() = setOf(
//            "latest_chapter",
//            "newest",
//            "total_popularity",
//            "favorites"
        )

    override val allGenres: Set<String>
        get() = setOf(
//            "Action",
//            "Adventure",
//            "Comedy",
//            "Drama",
//            "Ecchi",
//            "Fantasy",
//            "Harem",
//            "Historical",
//            "Horror",
//            "Martial Arts",
//            "Mature",
//            "Mystery",
//            "Psychological",
//            "Romance",
//            "School Life",
//            "Sci-fi",
//            "Seinen",
//            "Shoujo",
//            "Shounen",
//            "Slice of Life",
//            "Sports",
//            "Supernatural",
//            "Tragedy",
//            "Adult",
//            "Hentai",
//            "Smut"
        )
    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> = flow {
        emit(State.Loading)

        Log.d("ProManga32423423422222", "Fetching URL: $url")
        try {
            val response = api.getData(url)
            if (response.isSuccessful) {
                val jsonContent = response.body().orEmpty()

                val chapterImages = mutableListOf<String>()

                // Collect images as they're emitted
                extractChapterImagesStreaming(jsonContent, context, imageLoader).collect { state ->
                    when (state) {
                        is ImageCombinerState.SingleImageReady -> {
                            // Add the new image to our list
                            chapterImages.add(state.imageUrl)

                            // Emit the updated list (this is what the reader expects)
                            emit(State.Success(chapterImages.toList()))
                        }
                        is ImageCombinerState.Complete -> {
                            // Final emission with all images
                            Log.d("ProManga", "Chapter complete with ${state.totalImagesEmitted} images")
                            emit(State.Success(chapterImages))
                        }
                        is ImageCombinerState.Error -> {
                            Log.e("ProManga", "Error during combining: ${state.message}")
                            // Continue with what we have
                            if (chapterImages.isNotEmpty()) {
                                emit(State.Success(chapterImages))
                            }
                        }
                    }
                }


            } else {
                val errorMessage = response.errorBody()?.string()?.ifEmpty { "Unexpected error" }
                    ?: "Unexpected error"
                Log.e("ProManga", "API Error: ${response.code()} - $errorMessage")
                emit(State.Error(response.code(), errorMessage))
            }
        } catch (e: Exception) {
            Log.e("ProManga", "Network error: ${e.message}", e)
            emit(State.Error(0, e.localizedMessage ?: "Unknown error occurred"))
        }
    }

    // Updated extractChapterImagesStreaming helper
    private fun extractChapterImagesStreaming(
        json: String,
        context: Context,
        imageLoader: ImageLoader
    ): Flow<ImageCombinerState> = flow {
        try {
            val response: ProMangaChapterResponse = jsonParser.decodeFromString(json)
            val metadata = response.metadata

            if (metadata.maps != null && metadata.maps.isNotEmpty()) {
                val combiner = ProMangaImageCombiner(
                    context = context,
                    imageLoader = imageLoader,
                    cdnPath = response.cdn_path,
                    headers = defaultHeaders,
                    applicationScope = applicationScope
                )

                val singleImages = metadata.images ?: emptyList()
                Log.e("prooooMAPS22222222", metadata.maps.toString())
                Log.e("prooooMAPSIMAGES222222", metadata.images.toString())
                // This now emits SingleImageReady states
                combiner.combineChapterImagesStreaming(metadata.maps, singleImages).collect { state ->
                    emit(state)
                }
            } else {
                // No maps, emit complete immediately
                emit(ImageCombinerState.Complete(totalImagesEmitted = 0))
            }
        } catch (e: Exception) {
            Log.e("ProManga", "Error parsing chapter images: ${e.message}", e)
            emit(ImageCombinerState.Error("Parse error: ${e.message}", 0))
        }
    }
    fun getFullImgs(url: String): Flow<State<List<String>>> =
        fetchData(url) { json ->
            extractChapterImages(json,context,imageLoader)
        }
    private suspend fun extractChapterImages(
        json: String,
        context: Context,
        imageLoader: ImageLoader
    ): List<String> {
        return try {
            val response: ProMangaChapterResponse = jsonParser.decodeFromString(json)
            val metadata = response.metadata

            // Check if we have combined images (maps)
            if (metadata.maps != null && metadata.maps.isNotEmpty()) {
                val combiner = ProMangaImageCombiner(
                    context = context,
                    imageLoader = imageLoader,
                    cdnPath = response.cdn_path,
                    headers = defaultHeaders,
                    applicationScope = applicationScope
                )

                val singleImages = metadata.images ?: emptyList()
                val allImages = mutableListOf<String>()

                Log.d("ProManga", "Collecting all combined images...")

                // Collect ALL images before returning
                combiner.combineChapterImagesStreaming(metadata.maps, singleImages).collect { state ->
                    when (state) {
                        is ImageCombinerState.SingleImageReady -> {
                            // Add each image as it's ready
                            allImages.add(state.imageUrl)
                            Log.d("ProManga", "Collected ${allImages.size} images so far...")
                        }
                        is ImageCombinerState.Complete -> {
                            Log.d("ProManga", "✅ All ${state.totalImagesEmitted} images collected")
                        }
                        is ImageCombinerState.Error -> {
                            Log.e("ProManga", "Error during collection: ${state.message}")
                            // Continue with what we have
                        }
                    }
                }

                Log.d("ProManga", "Returning ${allImages.size} total images")
                allImages


            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("ProManga", "Error parsing chapter images: ${e.message}", e)
            emptyList()
        }
    }
    override fun fetchMangaHomeF(baseUrl: String): Flow<State<MutableList<MangaItem>>> =
        fetchHome()

    fun fetchHome(page: Int = 1): Flow<State<MutableList<MangaItem>>> =
        fetchData("${apiUrl}api/public/series/search?status=approved&limit=28&page=$page&sort=latest_chapter") { json ->
            extractHomeMangaItems(json).filter { item ->
                !item.genres.any { it in blackListGenres }
            }.toMutableList()
        }

    override suspend fun fetchSearchDataF(searchType: SearchType): Flow<State<List<MangaItem>>> {
        return when (searchType) {
            is SearchType.Normal -> {
                val query = searchType.toNormalQuery()
                fetchData("${apiUrl}api/public/series/search?status=approved&limit=50&page=1&search=$query") { json ->
                    extractSearchMangaItems(json).filter { manga ->
                        !manga.genres.any { it in blackListGenres }
                    }
                }
            }

            is SearchType.GENRES -> {
                fetchData("${apiUrl}api/public/series/search?status=approved&limit=50&page=1&genres=${searchType.genres}") { json ->
                    extractSearchMangaItems(json).filter { manga ->
                        !manga.genres.any { it in blackListGenres }
                    }
                }
            }

            is SearchType.SORT -> {
                fetchData("${apiUrl}api/public/series/search?status=approved&limit=50&page=1&sort=${searchType.sortType}${if (searchType.query.isNotEmpty()) "&search=${searchType.query}" else ""}") { json ->
                    extractSearchMangaItems(json).filter { manga ->
                        !manga.genres.any { it in blackListGenres }
                    }
                }
            }
        }
    }

    override suspend fun fetchMangaChaptersF(url: String): Flow<State<MangaInfo>> {
//

        return fetchData(url) { json ->
            extractMangaInfo(json)?.toMangaInfo(API, LANGUAGE, url)
        }.map { state ->
            when (state) {
                is State.Success -> {
                    val mangaInfo = state.data
                    if (mangaInfo != null) {
                        State.Success(mangaInfo)
                    } else {

                        State.Error(0, "Failed to parse manga info")
                    }
                }
                is State.Error -> state
                is State.Loading -> state
            }
        }
    }

    override fun fetchMoreManga(
        page: Int,
        currentItems: List<MangaItem>?
    ): Flow<State<List<MangaItem>>> =
        flow {
            if (page > 50) return@flow
            emit(State.Loading)
            fetchHome(page).collect { state ->
                when (state) {
                    is State.Success -> {
                        val newItems = state.data ?: emptyList()
                        val mergedList = (currentItems?.toMutableList() ?: mutableListOf()).apply {
                            addAll(newItems)
                        }
                        emit(
                            State.Success(
                                if (newItems.isEmpty()) (currentItems ?: emptyList()) else mergedList
                            )
                        )
                    }
                    is State.Error -> emit(state)
                    else -> Unit
                }
            }
        }.catch { e ->
            emit(State.Error(0, e.localizedMessage ?: "Unknown error occurred"))
        }

    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
        fetchData("${apiUrl}api/public/series/search?status=approved&limit=28&page=1&sort=total_popularity") { json ->
            extractPopularMangaItems(json)
        }

    // Helper functions for data extraction
    private fun extractHomeMangaItems(json: String): MutableList<MangaItem> {
        return try {
            val response: ProMangaResponse = jsonParser.decodeFromString(json)
            val items =  response.data.filter {
                !it.isSensitiveImage
            }.toMangaItems(API, LANGUAGE).toMutableList()

            Log.i("sdfksadasdsadasdsdfkjsdfsfsdfsd",items.toString())

            items
        } catch (e: Exception) {
            Log.e("ProManga", "Error parsing home items: ${e.message}", e)
            mutableListOf()
        }
    }

    private fun extractSearchMangaItems(json: String): List<MangaItem> {
        return try {
            val response: ProMangaResponse = jsonParser.decodeFromString(json)
            response.data.filter {
                !it.isSensitiveImage
            }.toMangaItems(API, LANGUAGE)
        } catch (e: Exception) {
            Log.e("ProManga", "Error parsing search items: ${e.message}", e)
            emptyList()
        }
    }

    private fun extractPopularMangaItems(json: String): List<PopularManga> {
        return try {
            val response: ProMangaResponse = jsonParser.decodeFromString(json)
            response.data.filter { series ->
                !series.metadata.genres.any { it in blackListGenres }
            }.filter {
                !it.isSensitiveImage
            }.
            toPopularManga(API, LANGUAGE)
        } catch (e: Exception) {
            Log.e("ProManga", "Error parsing popular items: ${e.message}", e)
            emptyList()
        }
    }

    private fun extractMangaInfo(json: String): ProInfo? {
        return try {
            Log.e("asdsadaskljasdlaskdasds0", " parsing json: ${json}")

            val js=  jsonParser.decodeFromString<ProInfo>(json)
            Log.e("asdsadaskljasdlaskdasds1", " parsing json: ${json}")
            js
        } catch (e: Exception) {
            Log.e("asdsadaskljasdlaskdasds2", "Error parsing manga info with chapters: ${e.message}", e)
            null
        }
    }


    private fun extractSeriesIdFromUrl(url: String): String {
        // Extract ID from URL format: https://prochan.net/manga/123
        return url.split("/").lastOrNull()?.split("?")?.firstOrNull() ?: ""
    }

    // Extension functions for data conversion
    private fun List<ProMangaSeries>.toMangaItems(api: String, language: String): List<MangaItem> =
        map { series ->

            MangaItem(
                api = api,
                language = language,
                title = series.title,
                url = "${apiUrl}api/public/${series.type}/${series.id}",
                imageUrl = getFullImageUrl(series),
                rating = null, // No rating in this response
                chapters = emptyList(),
                genres = series.metadata.genres
            )
        }

    private fun List<ProMangaSeries>.toPopularManga(api: String, language: String): List<PopularManga> =
        map { series ->
            PopularManga(
                api = api,
                language = language,
                title = series.title,
                url = "${apiUrl}api/public/${series.type}/${series.id}",
                imageUrl = getFullImageUrl(series),
            )
        }

    private fun ProInfo.toMangaInfo(api: String, language: String, url: String): MangaInfo {
        val description = this.metadata?.descriptions?.ar?.ifEmpty {
            this.metadata.descriptions?.en
        }?.ifEmpty {
            this.description
        }

        // Parse chapters from the response
        val chapterItems = this.chapters?.map { chapterInfo ->
            ChapterItem(
                number = chapterInfo.chapter_number.toString(),
                name = chapterInfo.title?.ifEmpty { "Chapter ${chapterInfo.chapter_number}" }
                    ?: "Chapter ${chapterInfo.chapter_number}",
                url = "${apiUrl}api/public/chapters/${chapterInfo.id}",
                date = parseDate(chapterInfo.published_at.toString()) ?: LocalDate.now(),

                )
        }?.reversed() // Reverse to show newest first

        return MangaInfo(
            api = api,
            language = language,
            url = url,
            title = this.title?:" ",
            imageUrl = getFullImageUrlst(this.thumbnail.toString(),cdn_path),
            rating =  "0",
            ratingCount = 0.toString(),
            description = description?.replace(Regex("<[^>]*>"), "") ?:"", // Remove HTML tags
            otherNames = "",
            author =  "",
            artist =  "",
            genres = this.metadata?.genres
                ?.filterNotNull()
                ?: emptyList(),
            tags = emptyList(),
            yearOfProduction =  "",
            status = when (this.progress?.lowercase()) {
                "مستمر" -> "Ongoing"
                "مكتمل" -> "Completed"
                else -> this.progress
            }.toString(),
            favoritesCount = "0",
            chapters = chapterItems?.toMutableList() ?: mutableListOf()
        )
    }

    private fun parseDate(dateString: String): LocalDate? {
        return try {
            if (dateString.contains("T")) {
                LocalDateTime.parse(dateString, DateTimeFormatter.ISO_DATE_TIME).toLocalDate()
            } else {
                LocalDate.parse(dateString, DateTimeFormatter.ISO_DATE)
            }
        } catch (e: Exception) {
            null
        }
    }


    // Generic fetch data function
    private inline fun <T> fetchData(
        url: String,
        crossinline transform: suspend (json: String) -> T
    ): Flow<State<T>> = flow {
        emit(State.Loading)

        Log.d("ProManga", "Fetching URL: $url")
        try {
            val response = api.getData(url)
            if (response.isSuccessful) {
                val jsonContent = response.body().orEmpty()
                val parsedData = transform(jsonContent)
                emit(State.Success(parsedData))
            } else {
                val errorMessage = response.errorBody()?.string()?.ifEmpty { "Unexpected error" }
                    ?: "Unexpected error"
                Log.e("ProManga", "API Error: ${response.code()} - $errorMessage")
                emit(State.Error(response.code(), errorMessage))
            }
        } catch (e: Exception) {
            Log.e("ProManga", "Network error: ${e.message}", e)
            emit(State.Error(0, e.localizedMessage ?: "Unknown error occurred"))
        }
    }

    fun getFullImageUrl( series: ProMangaSeries): String {
        val image = series.coverImage ?: return ""

        // إذا الرابط كامل http أو https
        if (image.startsWith("http")) {
            return image
        }

        // لو relative path يبدأ بـ "/"
        val cdn = series.cdnPath ?: "cdn2"


        return "https://$cdn.prochan.net$image"
    }

    fun getFullImageUrlst( url: String,cdn: String?): String {

        // إذا الرابط كامل http أو https
        if (url.startsWith("http")) {
            return url
        }

        // لو relative path يبدأ بـ "/"
        val cdn = cdn ?: "cdn2"


        return "https://$cdn.prochan.net$url"
    }

}
