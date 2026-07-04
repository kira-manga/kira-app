package me.manga.yamiapk.sources_repositry.ar.dilar

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.ar.dilar.models.DilarResponse
import me.manga.yamiapk.sources_repositry.ar.dilar.models.chapter.ChaptersResponse
import me.manga.yamiapk.sources_repositry.ar.dilar.models.chapter.Release
import me.manga.yamiapk.sources_repositry.ar.dilar.models.images.ReleaseInfo
import me.manga.yamiapk.sources_repositry.ar.dilar.models.images.Root
import me.manga.yamiapk.sources_repositry.ar.dilar.models.info.InfoResponse
import me.manga.yamiapk.sources_repositry.ar.dilar.models.search.BrowseManga
import me.manga.yamiapk.sources_repositry.ar.dilar.models.search.EncryptedResponse
import me.manga.yamiapk.sources_repositry.ar.dilar.models.search.SearchMangaDto
import me.manga.yamiapk.sources_repositry.common.SeparatedDetailsSitesv2
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

class DilarRepository @Inject constructor(
    dataStore: DataStoreHelper,
    api: IMangaDataApiServices,

    sourcesRepository: SourcesDao, )
    : SeparatedDetailsSitesv2(dataStore, api,sourcesRepository) {

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        } }

    override val mangaSource: MangaSource
        get() = MangaSource.DILAR
    override val homeUrl: String by lazy { "${baseUrl.ifBlank { mangaSource.BASEURL  }}api/releases?page=1" }


    override val popularUrl: String
        get() = ""
    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0
    override val BASE_URL: String
        get() = MangaSource.DILAR.BASEURL
    override val API: String
        get() = MangaSource.DILAR.API
    override val LANGUAGE: String
        get() = MangaSource.DILAR.LANGUAGE.Language


    override suspend fun initSite(): Int {

        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    override var useGetForNormalSearch: Boolean = false
    override var useGetForGenresSearch: Boolean = false
    override var useGetForSortSearch: Boolean = false
    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            // Merge cached headers with Referer; if cached also contains "Referer", this ensures your value wins:
            return base + refererHeader
        }

    private val refererHeader = "Referer" to baseUrl


    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // Option A: merge here so that cache always has Referer, and if you persist headers you want to include it
        val merged = newHeaders + refererHeader
        _cachedHeaders = merged

        dataStore.saveHeadersForApi(API, merged)

    }

    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()


//    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
//        val url = handelSearchUrl(searchType)
//
//        return fetchDataWithHeaders({
//                api.post(url, body = requestBody,headers = defaultHeaders) }){  html -> getSearchResults(html)}
//        }

    override fun handelLoadMoreUrl(page: Int): String {
       return "${baseUrl.ifBlank { BASE_URL }}api/releases?page=$page"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "${baseUrl.ifBlank { BASE_URL }}api/mangas/search"
    }



    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
        val builder = FormBody.Builder()
            .add("oneshot[value]", "false")
            .add("title", "a")
            .add("page", "1")

        // Required fields only if values are present
        listOf("1", "2").forEach {
            builder.add("manga_types[include][]", it)
        }

        // Only add exclude if not empty
        builder.add("manga_types[exclude][]", "3") // Remove this line if you want it empty

        builder.add("story_status[include][]", "1")
        // Omit this if there's no value:
        // builder.add("story_status[exclude][]", "2")

        builder.add("translation_status[include][]", "1")

        builder.add("categories[include][]", "")
        builder.add("categories[include][]", "10")
        builder.add("categories[exclude][]", "20")

        builder.add("chapters[min]", "")
        builder.add("chapters[max]", "")

        builder.add("dates[start]", "")
        builder.add("dates[end]", "")

        return builder.build()
    }

    override fun normalSearchFormBody(searchType: SearchType.Normal): RequestBody {
        val jsonBody = """
{
  "oneshot": {
    "value": false
  },
  "title": "${searchType.query}",
  "page": 1,
  "manga_types": {
    "include": [],
    "exclude": []
  },
  "story_status": {
    "include": [],
    "exclude": []
  },
  "translation_status": {
    "include": [],
    "exclude": []
  },
  "categories": {
    "include": [ ],
    "exclude": []
  },
  "chapters": {
    "min": "",
    "max": ""
  },
  "dates": {
    "start": "",
    "end": ""
  }
}
""".trimIndent()

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonBody.toRequestBody(mediaType)
        return requestBody
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? {
        return  FormBody.Builder()
            .add("oneshot[value]", "false")
            .add("title", "a")
            .add("page", "1")

            // manga_types.include and exclude as indexed arrays
            .add("manga_types[include][]", "1")
            .add("manga_types[include][]", "2")
            .add("manga_types[exclude][]", "3")

            // story_status
            .add("story_status[include][]", "1")
            .add("story_status[exclude][]", "2")

            // translation_status
            .add("translation_status[include][]", "1")

            // categories
            .add("categories[include][]", "") // null as empty string
            .add("categories[include][]", "10")
            .add("categories[exclude][]", "20")

            // chapters
            .add("chapters[min]", "")
            .add("chapters[max]", "")

            // dates
            .add("dates[start]", "")
            .add("dates[end]", "")
            .build()
    }

    override fun sortFormBody(searchType: SearchType.SORT): FormBody? {
        return  FormBody.Builder()
            .add("oneshot[value]", "false")
            .add("title", "a")
            .add("page", "1")

            // manga_types.include and exclude as indexed arrays
            .add("manga_types[include][]", "1")
            .add("manga_types[include][]", "2")
            .add("manga_types[exclude][]", "3")

            // story_status
            .add("story_status[include][]", "1")
            .add("story_status[exclude][]", "2")

            // translation_status
            .add("translation_status[include][]", "1")

            // categories
            .add("categories[include][]", "") // null as empty string
            .add("categories[include][]", "10")
            .add("categories[exclude][]", "20")

            // chapters
            .add("chapters[min]", "")
            .add("chapters[max]", "")

            // dates
            .add("dates[start]", "")
            .add("dates[end]", "")
            .build()
    }

    override fun createInfoUrl(mangaId: String): String {
        return mangaId
    }

    override fun createChaptersUrl(mangaId: String): String {
        return "${mangaId}/releases"
    }

    override fun parseChapters(json: String): List<ChapterItem> {

        return try {
            Log.i("sdghsfdlgdsfjlgsfdlkgjdsflkgkdslfgdsfgsd",json)
            val chapters: ChaptersResponse = jsonParser.decodeFromString(json)


            chapters.toChapterItems("${baseUrl.ifBlank { BASE_URL }}r")

        } catch (e: Exception) {
            mutableListOf()
        }
    }

    override fun extractHomeMangaItems(json: String): MutableList<MangaItem> {

        return try {
            val dilarItems: DilarResponse = jsonParser.decodeFromString(json)
           val items = dilarItems.toMangaItems(API,LANGUAGE)

            items
        } catch (e: Exception) {
            mutableListOf()
        }

    }

    override fun extractMangaList(string: String): List<PopularManga> {
        return listOf()
    }

    override suspend fun extractMangaInfo(
        string: String,
        baseUrl: String
    ): MangaInfo {

        return try {
            val dilarItems: InfoResponse = jsonParser.decodeFromString(string)


            val items = dilarItems.toMangaInfo(baseUrl)

            items
        } catch (e: Exception) {
            MangaInfo(
                api = "",
                language = "",
                url = "",
                title = "",
                imageUrl = "",
                rating = "",
                ratingCount = "",
                description = "",
                otherNames = "",
                author = "",
                artist = "",
                genres =listOf(),
                tags = listOf(),
                yearOfProduction = "",
                status = "",
                favoritesCount = "",
                chapters = mutableListOf()
            )
        }

    }

    override suspend fun getSearchResults(string: String): List<MangaItem> {
        val data =  jsonParser.decodeFromString<EncryptedResponse>(string)
        val parts = string.split("|")
        if (parts.size < 4) {
            return emptyList()
        }

        val dData = decrypt(data.data)

        return try {
            val manga  = jsonParser.decodeFromString<SearchMangaDto>(dData)



            val mangas = manga.mangas.map {
                it.toMangaItems()
            }

            mangas
        }catch (e: Exception){

            emptyList()
        }




    }


    override fun getChapterImages(string: String): List<String> {
        val html = Jsoup.parse(string)

        return try {
            val componentHtml  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                html.select(".js-react-on-rails-component").first.data()
            } else {
                html.select(".js-react-on-rails-component").html()
            }

            val release: ReleaseInfo = jsonParser
                .decodeFromString<Root>(componentHtml)
                .readerDataAction
                .readerData
                .release


                // Decide whether to use webp or png/jpg pages
            val (pages, directory) = if (release.webpPages.isNotEmpty()) {
                release.webpPages to "hq_webp"

            } else {
                release.pages to "hq"


            }


            return pages
                .sortedWith(pageSort)
                .map {
                       "${baseUrl.ifBlank { BASE_URL }}uploads/releases/${release.storageKey}/$directory/$it"

                }
        } catch (e: Exception) {
            listOf()
        }


    }
    private val pageSort =
        compareBy<String>({ parseNumber(0, it) ?: Double.MAX_VALUE }, { parseNumber(1, it) }, { parseNumber(2, it) })

    private fun parseNumber(index: Int, string: String): Double? =
        Regex("\\d+").findAll(string).map { it.value }.toList().getOrNull(index)?.toDoubleOrNull()




    fun InfoResponse.toMangaInfo(
        url: String,
        imgUrl :String = "${baseUrl.ifBlank { BASE_URL }}uploads/manga/cover"
    ): MangaInfo {
        val data = this.mangaData
        val library = this.mangaLibrary

        // Basic info fallback
        val title = data?.title.orEmpty()
        val imageUrl = "${imgUrl}/${data?.id}/${data?.cover.orEmpty()}"
        val rating = data?.rating.orEmpty()
        val ratingCount = data?.rates_count?.toString().orEmpty()
        val description = data?.summary.orEmpty()
        val otherNames = data?.synonyms.orEmpty()
        val author = data?.creator_nick.orEmpty()
        val artist = data?.editor_nick.orEmpty()

        // Categories as genres; tags can be same or empty
        val genres = data?.categories
            ?.mapNotNull { it?.name }
            ?: emptyList()
        val tags = emptyList<String>()

        // Year of production: extract year from timestamp if present
        val yearOfProduction = data?.time_stamp?.let { ts ->
            Instant.ofEpochSecond(ts.toLong())
                .atZone(ZoneId.systemDefault())
                .year
                .toString()
        }.orEmpty()

        // Status mapping from translation_status or story_status
        val status = when (data?.translation_status) {
            1 -> "Ongoing"
            2 -> "Completed"
            else -> "Unknown"
        }

        // Favorites count from library
        val favoritesCount = library?.favorite?.toString().orEmpty()

        // Initialize chapters empty; populate elsewhere
        val chapters = mutableListOf<ChapterItem>()

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = url,
            title = title,
            imageUrl = imageUrl,
            rating = rating,
            ratingCount = ratingCount,
            description = description,
            otherNames = otherNames,
            author = author,
            artist = artist,
            genres = genres,
            tags = tags,
            yearOfProduction = yearOfProduction,
            status = status,
            favoritesCount = favoritesCount,
            chapters = chapters
        )
    }

    // 2) Convert a Release → ChapterItem
    fun ChaptersResponse.toChapterItems(baseUrl: String): List<ChapterItem> {
        return releases
            .orEmpty()
            .filterNotNull()
//            .filter { release ->
//                val isPaid = (release.has_rev_link ?: false)
//                        || (release.support_link?.isNotBlank() ?: false)
//
//                Log.i("chapters", "id=${release.id} has_rev_link=${release.has_rev_link} support_link='${release.support_link}' isPaid=$isPaid")
//                !isPaid // keep only free chapters
//            }
            .map { it.toChapterItem(baseUrl) }
    }

    // 1) First, your existing “Release → ChapterItem” mapper:
    private fun Release.toChapterItem(baseUrl: String): ChapterItem {
        val num    = (chapter ?: 0).toString()
        val name   = title.orEmpty()
        val url    = "$baseUrl/${id}"
        val date   = created_at
            ?.let { Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            ?: time_stamp
                ?.let { Instant.ofEpochSecond(it.toLong()).atZone(ZoneId.systemDefault()).toLocalDate() }

        return ChapterItem(
            number          = num,
            name            = name,
            url             = url,
            date            = date
        )
    }

    fun BrowseManga.toMangaItems(

        api: String = API,
        language: String = LANGUAGE,
        url: String = "${baseUrl.ifBlank { BASE_URL }}api/mangas",  // adjust to your real base URL
        imgUrl :String = "${baseUrl.ifBlank { BASE_URL }}uploads/manga/cover"

    ): MangaItem{

       return MangaItem(
            api            = api,
            language       = language,
            title          = this.title.orEmpty(),
            // construct URL from base + id
            url            = "${url}/${this.id}",
            imageUrl       = "${imgUrl}/${this.id}/${this.cover.orEmpty()}",
            // parse rating string to Int if possible
            rating         = 0,
            // here we don’t have chapter details—return null or empty list
            chapters       = null,
            // extract names from categories
            genres         = this.categories
                ?.mapNotNull { it?.name }
                .orEmpty()
        )
    }



    fun DilarResponse.toMangaItems(
        api: String = API,
        language: String = LANGUAGE,
        url: String = "${baseUrl.ifBlank { BASE_URL }}api/mangas",  // adjust to your real base URL
        imgUrl :String = "${baseUrl.ifBlank { BASE_URL }}uploads/manga/cover"
    ): MutableList<MangaItem> {
        return this.releases
            // skip null releases or releases without a manga
            .orEmpty()
            .mapNotNull { it?.manga }
            .filter { it.is_novel == false }

            .distinctBy { it.id }
            .map { manga ->
                MangaItem(
                    api            = api,
                    language       = language,
                    title          = manga.title.orEmpty(),
                    // construct URL from base + id
                    url            = "${url}/${manga.id ?: 0}",
                    imageUrl       = "${imgUrl}/${manga.id}/${manga.cover.orEmpty()}",
                    // parse rating string to Int if possible
                    rating         = manga.rating?.toIntOrNull(),
                    // here we don’t have chapter details—return null or empty list
                    chapters       = null,
                    // extract names from categories
                    genres         = manga.categories
                        ?.mapNotNull { it?.name }
                        .orEmpty()
                )
            }
            .toMutableList()
    }

}