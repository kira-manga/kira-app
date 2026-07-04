package me.manga.yamiapk.sources_repositry.pt.manhastro

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.size.Dimension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import me.manga.yamiapk.sources_repositry.common.SeparatedDetailsSites
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.sources_repositry.pt.manhastro.models.chapters.ManhastroChaptersResponse
import me.manga.yamiapk.sources_repositry.pt.manhastro.models.home.Data
import me.manga.yamiapk.sources_repositry.pt.manhastro.models.home.manhastorHomeRespone
import me.manga.yamiapk.sources_repositry.pt.manhastro.models.imgs.ChapterPages
import me.manga.yamiapk.sources_repositry.pt.manhastro.models.imgs.ChaptersPagesv2
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.text.substringAfterLast
import me.manga.yamiapk.core.states.State

class ManhastroRepository @Inject constructor(
    private val api: IMangaDataApiServices,
    private val dataStore: DataStoreHelper,
    sourcesRepository: SourcesDao,
    private val dadosStore: ManhastroDadosStore

): SeparatedDetailsSites(dataStore,api,sourcesRepository)   {
    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        } }
    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    override val mangaSource: MangaSource
        get() = MangaSource.MANHASTRO
    override val homeUrl: String
        get() =  "${baseUrl.ifBlank { BASE_URL }}dados"
    override val popularUrl: String
        get() = "" +
//                "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php" +
                ""

    val chaptersUrl = "https://api.manhastro.net/dados/"
    val chaptersPagesUrl = "https://api2.manhastro.net/paginas/"

    override var imgBaseUrl: String = "https://capa.manhastro.net/"
    override var imgUrlVersion: Int = 0

    override var homeGet: Boolean= true
    override var searchGet: Boolean = false
    override fun handelLoadMoreUrl(page: Int): String {
        return "" +
//                "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php" +
                ""
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php"
    }

//    // 3) Build the form body
//    val formBody = FormBody.Builder()
//        .add("action", "madara_load_more")
//        .add("page", (1 - 1).toString())
//        .add("template", "madara-core/content/content-search")
//        .add("vars[orderby]", "meta_value_num")
//        .add("vars[paged]", "1")
//        .add("vars[template]", "archive")
//        .add("vars[post_type]", "wp-manga")
//        .add("vars[post_status]", "publish")
//        .add("vars[s]", "s")
//        .add("vars[order]", "desc")
//        .add("vars[sidebar]", "right")
//        .add("vars[manga_archives_item_layout]", "big_thumbnail")
//        .build()


    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()
    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            // Merge cached headers with Referer; if cached also contains "Referer", this ensures your value wins:
            return base
//            + refererHeader
        }

    private val refererHeader = "Accept" to "*/*"

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
       return FormBody.Builder()
            .add("action", "madara_load_more")
            .add("page", (page- 1).toString())
            .add("template", "madara-core/content/content-archive")
            .add("vars[orderby]", "meta_value_num")
            .add("vars[paged]", "1")
            .add("vars[post_type]", "wp-manga")
            .add("vars[post_status]", "publish")
            .add("vars[meta_key]", if (popular) "_wp_manga_views" else "_latest_update")
            .add("vars[order]", "desc")
            .add("vars[sidebar]", "right")
            .add("vars[manga_archives_item_layout]", "big_thumbnail")
            .build()

    }

    override fun createInfoUrl(mangaId: String): String {
        return mangaId
    }

    override fun createChaptersUrl(mangaId: String): String {
        val postId = mangaId.substringAfterLast("/")

       val chapterUrl = "${baseUrl.ifBlank { BASE_URL }}dados/$postId"

        return chapterUrl
    }

    override fun handelSearchFormBody(
        page: Int,
        searchType: SearchType.Normal
    ): FormBody? {
        // 3) Build the form body
        return FormBody.Builder()
            .add("action", "madara_load_more")
            .add("page", (page).toString())
            .add("template", "madara-core/content/content-archive")
            .add("vars[orderby]", "meta_value_num")
            .add("vars[paged]", "1")
//            .add("vars[template]", "archive")
            .add("vars[post_type]", "wp-manga")
            .add("vars[post_status]", "publish")
            .add("vars[s]", searchType.query)
            .add("vars[order]", "desc")
            .add("vars[sidebar]", "right")
            .add("vars[manga_archives_item_layout]", "big_thumbnail")
            .build()
    }

    override fun parseChapters(html: String): List<ChapterItem> {
        return try {
            Log.i("dslkjfsdfsdfsdfsasdadfsdfsdf0",html.toString())

            val apiResponse = jsonParser.decodeFromString<ManhastroChaptersResponse>(html)
            Log.i("dslkjfsdfsdfsdfsasdadfsdfsdf1",apiResponse.toString())

            apiResponse.toChapterItems { chapterId ->
                "${chaptersPagesUrl}$chapterId"
            }
        } catch (e: Exception) {
           Log.i("dslkjfsdfsdfsdfsasdadfsdfsdf",e.toString())
            emptyList()
        }}

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        return try {

            val apiResponse =
            jsonParser.decodeFromString<manhastorHomeRespone>(html)
            dadosStore.save(apiResponse)

        val items = apiResponse.data
            ?.filterNotNull()
            ?.map { data ->
                MangaItem(
                    api = API,
                    language = LANGUAGE,
                    title = data.titulo_brasil?.ifBlank { data.titulo ?: "" } ?: "",
                    url = buildMangaUrl(data.manga_id),
                    imageUrl = buildImageUrl(data.imagem),
                    rating = null, // API doesn't provide rating
                    chapters = null, // home response has no chapters
                    genres = parseGenres(data.generos)
                )
            }
            ?.toMutableList()
            ?: mutableListOf()

            Log.i("dsfljsdfslkdsfsdfdfsdfsdfsd",items.take(30).toString())

            return items
            }  catch (e: Exception) {
                Log.i("dsfkjlsdlfsjdfsdfsdfsdfsd",e.toString())
               return mutableListOf()
            }
    }
    private fun buildMangaUrl(mangaId: Int?): String {
        return if (mangaId != null && mangaId > 0) {
            "${baseUrl.ifBlank { BASE_URL }}dados/$mangaId"
        } else {
            ""
        }
    }
    private fun parseGenres(genres: String?): List<String> {
        return genres
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    private fun buildImageUrl(image: String?): String {
        if (image.isNullOrBlank()) return ""

        return if (image.startsWith("http")) {
            image
        } else {
            "https://$image"
        }
    }


    override fun extractMangaList(html: String): List<PopularManga> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<PopularManga>()

        doc.select(".page-item-detail").forEach { el ->
            // 1) find the thumbnail wrapper
            val thumbDiv = el.selectFirst(".item-thumb")!!
            // 2) pull out the post-id
            val id = thumbDiv.attr("data-post-id").trim()
            // 3) find the A tag around the IMG
            val imageAnchor = thumbDiv.selectFirst("a")!!
            // 4) get the real image URL
            val imageUrl = imageAnchor.selectFirst("img")!!
                .let { img ->
                    // prefer data-src if it’s lazy-loaded
                    img.attr("data-src").ifEmpty { img.absUrl("src") }
                }
            // 5) find title/link
            val titleAnchor = el.selectFirst(".post-title a")!!
            val link       = titleAnchor.absUrl("href").trim()
            val title     = titleAnchor.text().trim()

            // 6) pack into one string however you like:
            val combinedUrl = "$link|$id"


            items += PopularManga(
                api       = API,
                language  = LANGUAGE,
                title     = title,
                url       = combinedUrl,
                imageUrl  = imageUrl,
            )
        }

        return items
    }

    override fun extractMangaInfo(
        html: String,
        baseUrl: String,
        combinUrl: String
    ): MangaInfo {

        // combinUrl == ".../dados/{manga_id}"
        val mangaId = combinUrl.substringAfterLast("/").toIntOrNull()
            ?: error("Invalid mangaId: $combinUrl")

        val cached = dadosStore.get(mangaId)
            ?: error("Manga $mangaId not found in cached /dados")

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = baseUrl,
            title = cached.titulo_brasil ?: cached.titulo ?: "",
            imageUrl = buildImageUrl(cached.imagem),
            rating = "0",
            ratingCount = cached.views_mes.toString(),
            description = cached.descricao_brasil ?: cached.descricao ?: "",
            otherNames = cached.titulo ?: "",
            author = "",
            artist = "",
            genres = parseGenres(cached.generos),
            tags = emptyList(),
            yearOfProduction = "",
            status = "UN",
            favoritesCount = cached.views_mes ?: "0",
            chapters = mutableListOf() // ← filled later
        )
    }


//    override  fun extractMangaInfo(html: String, baseUrl: String, combinUrl: String): MangaInfo {
//        val document = Jsoup.parse(html)
//
//        // --- BASIC INFO ---
//        val title = document.select("div.summary_content h2")
//            .text().trim()
//
//        val otherNames = document.select("#Judul p.j2")
//            .text().trim()
//
//        val description = document.select("div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt")
//            .let {
//            if (it.select("p").text().isNotEmpty()) {
//               it.select("p").joinToString(separator = "\n\n") { p ->
//                    p.text().replace("<br>", "\n")
//                }
//            } else {
//               it.text()
//            }
//        }
//
//        val thumbnailUrl = document
//            .selectFirst("div.summary_image img")?.let {
//                imageFromElement(it)
//            }
//
//
//        // --- STATISTICS (rating, favorites) ---
//        // Example: <div class="rating"><span itemprop="ratingValue">8.5</span> (from 123 votes)</div>
//        val rating = document.select("span[itemprop=ratingValue]")
//            .text().trim()
//
//        val ratingCount = document.select("span[itemprop=ratingCount]")
//            .text().let {
//                // sometimes wrapped like "(123 votos)" → strip non-digits
//                it.filter { ch -> ch.isDigit() }
//            }
//
//
//
//        // --- METADATA TABLE ---
//        fun infoFromTable(label: String) = document
//            .select("table.inftable tr:has(td:contains($label)) td:nth-child(2)")
//            .text().trim()
//
//        val author = infoFromTable("Pengarang")
//        val artist = infoFromTable("Ilustrator")       // or "Artista", depending on site
//        val status = document.select("div.summary-heading:contains(Status) + div.summary-content")
//            .last()?.text()
//        val yearOfProduction = infoFromTable("Ano")     // or "Publicado em", adjust as needed
//
//        // --- GENRES & TAGS ---
//        val genres = document.select("ul.genre li span[itemprop=genre]")
//            .map { it.text().trim() }
//
//        val tags = document.select("div.tags a")   // e.g. a.tag-links → adjust selector
//            .map { it.text().trim() }
//
//
//
//
//        return MangaInfo(
//            api = API,
//            language = LANGUAGE,           // your constant
//            url = combinUrl,
//            title = title,
//            imageUrl = thumbnailUrl.toString(),
//            rating = rating,
//            ratingCount = ratingCount,
//            description = description,
//            otherNames = otherNames,
//            author = author,
//            artist = artist,
//            genres = genres,
//            tags = tags,
//            yearOfProduction = yearOfProduction,
//            status = status?: "UN",
//            favoritesCount = "0",
//            chapters = mutableListOf(),
//        )
//    }
      fun imageFromElement(element: Element): String? {
        return when {
            element.hasAttr("data-src") -> element.attr("abs:data-src")
            element.hasAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
            element.hasAttr("srcset") -> element.attr("abs:srcset").getSrcSetImage()
            element.hasAttr("data-cfsrc") -> element.attr("abs:data-cfsrc")
            else -> element.attr("abs:src")
        }
    }
   fun String.getSrcSetImage(): String? {
        return this.split(" ")
            .filter(URL_REGEX::matches)
            .maxOfOrNull(String::toString)
    }
    companion object {
        val URL_REGEX = """^(https?://[^\s/$.?#].[^\s]*)${'$'}""".toRegex()
    }



    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // Option A: merge here so that cache always has Referer, and if you persist headers you want to include it
        val merged = newHeaders
//        + refererHeader
        _cachedHeaders = merged

        dataStore.saveHeadersForApi(API, merged)

    }

    override fun getChapterImages(html: String): List<String> {
        return try {
            Log.d("ChapterImages", "Raw JSON length = ${html.length}")

            val result = jsonParser.decodeFromString<ChaptersPagesv2>(html)

            Log.d("ChapterImages", "Parse success = ${result.success}")

            val chapter = result.data?.chapter
            if (chapter == null) {
                Log.w("ChapterImages", "chapter == null")
                return emptyList()
            }

            val baseUrl = chapter.baseUrl
            val hash = chapter.hash

            if (baseUrl.isNullOrBlank() || hash.isNullOrBlank()) {
                Log.w(
                    "ChapterImages",
                    "Invalid baseUrl or hash | baseUrl=$baseUrl | hash=$hash"
                )
                return emptyList()
            }

            val files = chapter.data?.filterNotNull().orEmpty()

            Log.d("ChapterImages", "Pages count = ${files.size}")

            val images = files.map { filename ->
                "$baseUrl/$hash/$filename"
            }

            if (images.isNotEmpty()) {
                Log.d("ChapterImages", "First image = ${images.first()}")
            }

            images

        } catch (e: Exception) {
            Log.e("ChapterImages", "Failed to parse chapter images", e)
            emptyList()
        }
    }


    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    fun ManhastroChaptersResponse.toChapterItems(
        buildUrl: (chapterId: Int) -> String,
    ): List<ChapterItem> = this.data
        .orEmpty()
        .mapNotNull { cap ->
            val id   = cap?.capitulo_id ?: return@mapNotNull null
            val name = cap.capitulo_nome.orEmpty()
            val rawName = cap.capitulo_nome.orEmpty()

            val number = Regex("\\d+")
                .find(rawName)
                ?.value
                ?: rawName
            val rawDateTime = cap.capitulo_data.orEmpty()

            val parsedDate = runCatching {
                LocalDate.parse(
                    rawDateTime.substringBefore(" "),
                    DateTimeFormatter.ISO_LOCAL_DATE
                )
            }.getOrNull()
            ChapterItem(
                number = number,
                name   = name,
                url    = buildUrl(id),
                date   = parsedDate,
            )
        }.sortedBy { it.number.toIntOrNull() ?: Int.MIN_VALUE }.reversed()





    override suspend fun normalSearch(
        searchType: SearchType.Normal
    ): Flow<State<List<MangaItem>>> = flow {

        emit(State.Loading)

        // 1️⃣ Search locally first
        val localResults = dadosStore
            .search(searchType.query)
            .map { it.toMangaItem() }

        if (localResults.isNotEmpty()) {
            emit(State.Success(localResults))
            return@flow
        }

        // 2️⃣ Fallback to network search
        val url = handelSearchUrl(searchType)

        fetchDataWithHeaders({
            if (searchGet) {
                api.get(url, defaultHeaders)
            } else {
                api.post(url = url, handelSearchFormBody(0, searchType))
            }
        }) { html ->
            getSearchResults(html)
        }.collect { state ->
            emit(state)
        }
    }

    override fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<MangaItem>()

        doc.select(".page-item-detail").forEach { el ->
            if (!el.hasClass("manga")) return@forEach

            // 1) find the thumbnail wrapper
            val thumbDiv = el.selectFirst(".item-thumb")!!
            // 2) pull out the post-id
            val id = thumbDiv.attr("data-post-id").trim()
            // 3) find the A tag around the IMG
            val imageAnchor = thumbDiv.selectFirst("a")!!
            // 4) get the real image URL
            val imageUrl = imageAnchor.selectFirst("img")!!
                .let { img ->
                    // prefer data-src if it’s lazy-loaded
                    img.attr("data-src").ifEmpty { img.absUrl("src") }
                }
            // 5) find title/link
            val titleAnchor = el.selectFirst(".post-title a")!!
            val link       = titleAnchor.absUrl("href").trim()
            val title     = titleAnchor.text().trim()

            // 6) pack into one string however you like:
            val combinedUrl = "$link|$id"

            // 7) extract chapters as before
            val chapters = el.select(".list-chapter .chapter-item").mapNotNull { ch ->
                val a = ch.selectFirst("a") ?: return@mapNotNull null
                ChapterItem(
                    name   = a.text().trim(),
                    number = a.text().trim(),
                    url    = a.absUrl("href").trim()
                )
            }

            items += MangaItem(
                api       = API,
                language  = LANGUAGE,
                title     = title,
                url       = combinedUrl,
                imageUrl  = imageUrl,
                rating    = 0,
                chapters  = chapters,
                genres    = emptyList(),
                // if you want to store your combined string in the MangaItem:
            )
        }

        return items
    }






    override fun buildImageRequest(
        context: Context,
        url: String,
        screenWidthPx: Int

    ): ImageRequest {
        val coilHeaders = NetworkHeaders.Builder()
//            .apply {
//                defaultHeaders.forEach { (key, value) ->
//                    add(key, value)
//                    Log.i("AddingHeader", "$key: $value")
//                }
//            }
            .build()

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
            .crossfade(true)
            .build()
    }

    private fun Data.toMangaItem(): MangaItem {
        return MangaItem(
            api = API,
            language = LANGUAGE,
            title = titulo_brasil?.ifBlank { titulo ?: "" } ?: "",
            url = buildMangaUrl(manga_id),
            imageUrl = buildImageUrl(imagem),
            rating = null,
            chapters = null,
            genres = parseGenres(generos)
        )
    }


}