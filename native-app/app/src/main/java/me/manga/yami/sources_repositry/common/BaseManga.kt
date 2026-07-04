package me.manga.yamiapk.sources_repositry.common

import android.R.attr.bitmap
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.drawable.toDrawable
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.size.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.OkHttpClient
import retrofit2.Response
import javax.net.ssl.HttpsURLConnection

abstract class BaseManga (
    private val sourcesRepository: SourcesDao,
) : BaseMangaRepository() {
    abstract val mangaSource :MangaSource

     override var baseUrl : String = mangaSource.BASEURL
    abstract override val BASE_URL : String

    abstract override val API : String
    override val URL_VERSION: Int
        get() = 0

    abstract override val LANGUAGE : String
    override val ICON : Int= mangaSource.ICON
    override val PRIORITY : Int= mangaSource.PRIORITY

    abstract override val sortTypes: Set<String>
    abstract override val allGenres: Set<String>
    abstract override val blackListGenres: Set<String>
    abstract override val defaultHeaders: Map<String, String>

    override suspend fun getBaseUrl(): String = withContext(Dispatchers.IO) {

        Log.i("dfgdfgdfsgdffdgsdf",API)
       val url = sourcesRepository.getBaseUrlFor(API) ?: BASE_URL

        Log.i("dfgdfgdfsgdffdgsdf2",url)

        baseUrl = url
        return@withContext url.ifBlank { BASE_URL }
    }

    // in your abstract base class / interface:
    override suspend fun fetchSearchDataF(searchType: SearchType): Flow<State<List<MangaItem>>> =
        when (searchType) {
            is SearchType.Normal  -> normalSearch(searchType)
            is SearchType.GENRES  -> genresSearch(searchType)
            is SearchType.SORT    -> sortSearch(searchType)
        }

    // now each handler only ever sees the subtype it cares about:
    protected abstract suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>>
    protected abstract suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>>
    protected abstract suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>>






    abstract override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>>


    abstract override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>>

    abstract override fun fetchMoreManga(page: Int, currentItems: List<MangaItem>? ): Flow<State<List<MangaItem>>>


    abstract override suspend fun fetchMangaChaptersF(query: String): Flow<State<MangaInfo>>


    abstract override fun fetchChapterDataF(url: String): Flow<State<List<String>>>




    override fun buildImageRequest(
        context: Context,
        url: String,
        screenWidthPx: Int

    ): ImageRequest {
        val coilHeaders = NetworkHeaders.Builder()
            .apply {
                defaultHeaders.forEach { (key, value) ->
                    add(key, value)
                    Log.i("AddingHeader", "$key: $value")
                }
            }
            .build()
//        // Log the URL and headers
//        Log.i("CoilRequestHKJKH", "========== IMAGE REQUEST ==========")
//        Log.i("CoilRequestHKJKH", "URL: $url")
//        Log.i("CoilRequestHKJKH", "Screen Width: $screenWidthPx")
//        Log.i("CoilRequestHKJKH", "Headers: ${coilHeaders.asMap()}")
//        defaultHeaders.forEach { (key, value) ->
//            Log.i("CoilRequestHeader", "$key: $value")
//        }
//        Log.i("CoilRequestHKJKH", "===================================")
        return ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(coilHeaders)
            .apply {
                if (screenWidthPx != 0){
                    size(Dimension.Pixels(screenWidthPx), Dimension.Undefined)

                }
            }
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
//            .decoderFactory { result, options, imageLoader ->
//                object : Decoder {
//                    override suspend fun decode(): DecodeResult {
//                        return try {
//                            val source = result.source
//                            val bytes = source.source().readByteArray()
//
//                            Log.i("CustomDecoder", "Bytes size: ${bytes.size}")
//
//                            // Log first few bytes to check if it's valid image data
//                            if (bytes.isNotEmpty()) {
//                                val header = bytes.take(16).joinToString(" ") { "%02X".format(it) }
//                                Log.i("CustomDecoder", "First 16 bytes: $header")
//                            }
//
//                            // Try to decode with options for better error info
//                            val options = BitmapFactory.Options().apply {
//                                inJustDecodeBounds = true
//                            }
//                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
//
//                            Log.i("CustomDecoder", "Image info - Width: ${options.outWidth}, Height: ${options.outHeight}, MimeType: ${options.outMimeType}")
//
//                            // Now decode for real
//                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//
//                            if (bitmap == null) {
//                                Log.e("CustomDecoder", "BitmapFactory returned null")
//                                Log.e("CustomDecoder", "Detected MIME type: ${options.outMimeType}")
//                                Log.e("CustomDecoder", "Image dimensions: ${options.outWidth}x${options.outHeight}")
//
//                                // Check if it's a valid image format
//                                if (options.outWidth == -1 || options.outHeight == -1) {
//                                    throw IllegalStateException("Invalid image data - not a recognized image format")
//                                }
//
//                                throw IllegalStateException("Failed to decode image despite valid format")
//                            }
//
//                            Log.i("CustomDecoder", "Successfully decoded bitmap: ${bitmap.width}x${bitmap.height}")
//
//                            DecodeResult(
//                                image = bitmap.asImage(),
//                                isSampled = false
//                            )
//                        } catch (e: Exception) {
//                            Log.e("CustomDecoder", "Error decoding image: ${e.message}", e)
//                            throw e
//                        }
//                    }                }
//            }
            .crossfade(true)
            .build()
    }
    override fun buildItemsImageRequest(
        context: Context,
        url: String,
        screenWidthPx: Int
    ): ImageRequest {
        val coilHeaders = NetworkHeaders.Builder()
            .apply {
                defaultHeaders.forEach { (key, value) ->
                    add(key, value)
                }
            }
            .build()

        return ImageRequest.Builder(context)
            .data(url)

            .httpHeaders(coilHeaders)

            .crossfade(true)
            .build()
    }

    abstract override suspend fun  refreshHeaders(newHeaders:  Map<String, String> )







     inline fun <T> fetchDataWithHeaders(
        crossinline apiCall: suspend () -> Response<String>,
        crossinline transform: suspend (htmlContent: String) -> T
    ): Flow<State<T>> = flow {
        emit(State.Loading)
        try {

            val response = apiCall()
            Log.i("fghdsflgsdfgdfgdfsgsdfgdfg5",response.toString())

            if (response.isSuccessful) {
                Log.i("fghdsflgsdfgdfgdfsgsdfgdfg4","isSuccessful")

                val htmlContent = response.body().orEmpty()

                Log.i("fghdsflgsdfgdfgdfsgsdfgdfg3",htmlContent)
                val parsedData = transform(htmlContent)
                emit(State.Success(parsedData))
            } else {
                val errorCode =
                    response.code()
                emit(State.Error.fromCode(errorCode))
            }
        } catch (e: Exception) {
            emit(State.Error(0, e.localizedMessage ?: "Unknown error occurred"))
        }
    }


    protected fun String.dropTrailingSlash(): String =
        if (this.endsWith("/")) this.dropLast(1) else this

    fun List<String>.hasBlacklistedGenre(): Boolean =
        this.any { it in blackListGenres }
}
