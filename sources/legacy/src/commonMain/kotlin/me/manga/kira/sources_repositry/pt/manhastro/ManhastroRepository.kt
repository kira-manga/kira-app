package me.manga.kira.sources_repositry.pt.manhastro

/**
 * Migration note (Phase 7.7): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * Gson -> kotlinx.serialization, @Inject dropped, android.util.Log -> Kermit Logger,
 * java.time -> kotlinx.datetime.
 *
 * - `buildImageRequest` override from the Android source is dropped because the base class
 *   (`BaseManga`) no longer exposes that hook in commonMain (Coil3 is not on the KMP shared
 *   classpath; image-request building is delegated back to the platform side via
 *   `defaultHeaders`). See `BaseManga.kt` header for rationale.
 * - The chapter date branch parses ISO_LOCAL_DATE (numeric `yyyy-MM-dd`), which kotlinx.datetime
 *   handles directly via `LocalDate.parse(...)` — no locale concerns.
 * - The home-response model's `data` was widened in the port to `List<Capitulo>` (non-nullable
 *   elements with default ctor values), so the `mapNotNull { cap -> cap?.capitulo_id ?: ... }`
 *   pattern from the Android source is folded to direct member access on `cap`.
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlin.concurrent.Volatile
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import me.manga.kira.core.states.State
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.common.SeparatedDetailsSites
import me.manga.kira.sources_repositry.data.MangaSource
import me.manga.kira.sources_repositry.pt.manhastro.models.chapters.ManhastroChaptersResponse
import me.manga.kira.sources_repositry.pt.manhastro.models.home.Data
import me.manga.kira.sources_repositry.pt.manhastro.models.home.manhastorHomeRespone
import me.manga.kira.sources_repositry.pt.manhastro.models.imgs.ChaptersPagesv2

@OptIn(ExperimentalTime::class)
class ManhastroRepository(
    private val api: ApiClient,
    private val dataStore: DataStoreHelper,
    sourcesRepository: SourcesDao,
    private val dadosStore: ManhastroDadosStore,
): SeparatedDetailsSites(api, sourcesRepository)   {
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
     * Just like your old `defaultHeaders` – will block once on first call,
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

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
       return mapOf(
            "action" to "madara_load_more",
            "page" to (page - 1).toString(),
            "template" to "madara-core/content/content-archive",
            "vars[orderby]" to "meta_value_num",
            "vars[paged]" to "1",
            "vars[post_type]" to "wp-manga",
            "vars[post_status]" to "publish",
            "vars[meta_key]" to if (popular) "_wp_manga_views" else "_latest_update",
            "vars[order]" to "desc",
            "vars[sidebar]" to "right",
            "vars[manga_archives_item_layout]" to "big_thumbnail",
        )

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
    ): Map<String, String>? {
        // 3) Build the form body
        return mapOf(
            "action" to "madara_load_more",
            "page" to (page).toString(),
            "template" to "madara-core/content/content-archive",
            "vars[orderby]" to "meta_value_num",
            "vars[paged]" to "1",
//            "vars[template]" to "archive",
            "vars[post_type]" to "wp-manga",
            "vars[post_status]" to "publish",
            "vars[s]" to searchType.query,
            "vars[order]" to "desc",
            "vars[sidebar]" to "right",
            "vars[manga_archives_item_layout]" to "big_thumbnail",
        )
    }

    override fun parseChapters(html: String): List<ChapterItem> {
        return try {
            Logger.withTag("dslkjfsdfsdfsdfsasdadfsdfsdf0").i { html }

            val apiResponse = jsonParser.decodeFromString<ManhastroChaptersResponse>(html)
            Logger.withTag("dslkjfsdfsdfsdfsasdadfsdfsdf1").i { apiResponse.toString() }

            apiResponse.toChapterItems { chapterId ->
                "${chaptersPagesUrl}$chapterId"
            }
        } catch (e: Exception) {
           Logger.withTag("dslkjfsdfsdfsdfsasdadfsdfsdf").i { e.toString() }
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

            Logger.withTag("dsfljsdfslkdsfsdfdfsdfsdfsd").i { items.take(30).toString() }

            return items
            }  catch (e: Exception) {
                Logger.withTag("dsfkjlsdlfsjdfsdfsdfsdfsd").i { e.toString() }
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
        val doc = Ksoup.parse(html)
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
                    // prefer data-src if it's lazy-loaded
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
            description = cached.descricao_brasil ?: cached.descricao ?: "",
            author = "",
            genres = parseGenres(cached.generos),
            status = "UN",
            chapters = mutableListOf() // ← filled later
        )
    }


//    override  fun extractMangaInfo(html: String, baseUrl: String, combinUrl: String): MangaInfo {
//        val document = Ksoup.parse(html)
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
            Logger.withTag("ChapterImages").d { "Raw JSON length = ${html.length}" }

            val result = jsonParser.decodeFromString<ChaptersPagesv2>(html)

            Logger.withTag("ChapterImages").d { "Parse success = ${result.success}" }

            val chapter = result.data?.chapter
            if (chapter == null) {
                Logger.withTag("ChapterImages").w { "chapter == null" }
                return emptyList()
            }

            val baseUrl = chapter.baseUrl
            val hash = chapter.hash

            if (baseUrl.isNullOrBlank() || hash.isNullOrBlank()) {
                Logger.withTag("ChapterImages").w {
                    "Invalid baseUrl or hash | baseUrl=$baseUrl | hash=$hash"
                }
                return emptyList()
            }

            val files = chapter.data?.filterNotNull().orEmpty()

            Logger.withTag("ChapterImages").d { "Pages count = ${files.size}" }

            val images = files.map { filename ->
                "$baseUrl/$hash/$filename"
            }

            if (images.isNotEmpty()) {
                Logger.withTag("ChapterImages").d { "First image = ${images.first()}" }
            }

            images

        } catch (e: Exception) {
            Logger.withTag("ChapterImages").e(e) { "Failed to parse chapter images" }
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
        .mapNotNull { cap ->
            val id   = cap.capitulo_id
            val name = cap.capitulo_nome
            val rawName = cap.capitulo_nome

            val number = Regex("\\d+")
                .find(rawName)
                ?.value
                ?: rawName
            val rawDateTime = cap.capitulo_data

            // The Android source parsed the date with `DateTimeFormatter.ISO_LOCAL_DATE`. The
            // upstream feed delivers `yyyy-MM-dd[ HH:mm:ss]`, so we take the leading date segment
            // (matching `substringBefore(" ")`) and let kotlinx.datetime's ISO LocalDate parser
            // handle it. `IllegalArgumentException` replaces `DateTimeParseException`.
            val parsedDate: LocalDate? = runCatching {
                LocalDate.parse(rawDateTime.substringBefore(" "))
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
                api.postForm(url = url, fields = handelSearchFormBody(0, searchType) ?: emptyMap())
            }
        }) { html ->
            getSearchResults(html)
        }.collect { state ->
            emit(state)
        }
    }

    override fun getSearchResults(html: String): List<MangaItem> {
        val doc = Ksoup.parse(html)
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
                    // prefer data-src if it's lazy-loaded
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


    // Migration note: `buildImageRequest(context, url, screenWidthPx)` from the Android source has
    // been dropped. `BaseManga` no longer exposes that hook in commonMain (Coil3 / `Context` /
    // `Bitmap` are Android-only). The original implementation built a Coil ImageRequest with no
    // headers (the cache-headers code path was commented out upstream) and an `RGB_565` bitmap
    // config; once we have a platform-side image loader (Phase 8/9), `defaultHeaders` is enough to
    // reconstruct the same request from the UI layer.



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
