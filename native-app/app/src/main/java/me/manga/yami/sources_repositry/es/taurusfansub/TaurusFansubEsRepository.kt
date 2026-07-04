package me.manga.yamiapk.sources_repositry.es.taurusfansub

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.common.NormalSitesv2
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import javax.inject.Inject

class TaurusFansubEsRepository  @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
    ): NormalSitesv2(dataStore,api,sourcesRepository){
    override val mangaSource: MangaSource
        get() = MangaSource.TAURUSFANSUB
    override val homeUrl: String by lazy {  BASE_URL}


    override val popularUrl: String by lazy {"${baseUrl.ifBlank { BASE_URL }}manga/?m_orderby=trending"}

    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0
    override var useGetForPopular: Boolean = false

    override var useGetForHome: Boolean = false
    override var useGetForSearch: Boolean =false
    override var useGetForNormalSearch: Boolean = false
    override var useGetForGenresSearch: Boolean = false
    override var useGetForSortSearch: Boolean = false

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null


    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()


    override fun handelFormBodyPopular(page: Int, popular: Boolean): FormBody? {
        return FormBody.Builder().apply {
            add("vars[post_type]", "wp-manga")
            add("vars[post_status]", "publish")
            add("vars[order]", "desc")
            add("vars[paged]", 1.toString())
            add("vars[posts_per_page]", "20")
            add("template", "madara-core/content/content-archive")
            add("page", 0.toString())
            add("vars[meta_key]", "_wp_manga_views")
            add("vars[orderby]", "wp-manga")
            add("vars[paged]", "1")
            add("action",  "madara_load_more")
            add("vars[orderby]", "meta_value_num")
            add("vars[sidebar]", "right")
        }.build()
    }
    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // persist in background
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

        // update the cache immediately
    }


    override fun handelFormBodyHome(page: Int, popular: Boolean): FormBody? {
      return FormBody.Builder().apply {

          add("vars[post_type]", "wp-manga")
          add("vars[post_status]", "publish")
          add("vars[order]", "desc")
          add("vars[paged]", page.toString())
        add("vars[posts_per_page]", "20")
        add("template", "madara-core/content/content-archive")
        add("page", (page -1).toString())
        add("vars[meta_key]", "_latest_update")
        add("vars[paged]", "1")
        add("action",  "madara_load_more")
        add("vars[orderby]", "meta_value_num")
        add("vars[sidebar]", "right")
    }.build()
    }
    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}manga/page/$page/"
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal  -> normalSearchUrl(searchType.query)
            is SearchType.GENRES  -> genresSearchUrl(searchType.query,searchType.genres)
            is SearchType.SORT    -> sortSearchUrl(searchType.query,searchType.genres,searchType.sortType)

        }
    fun normalSearchUrl(q : String): String = "${baseUrl.ifBlank { BASE_URL }}?s=${q}&post_type=wp-manga"
    fun genresSearchUrl(q : String,genres : String): String = "${baseUrl.ifBlank { BASE_URL }}?s=${q}&post_type=wp-manga&genre%5B%5D=${genres.toGenreKey()}&m_orderby"


    fun sortSearchUrl(q : String,genres : String,sort : String): String = "${baseUrl.ifBlank { BASE_URL }}?s=${q}&post_type=wp-manga&genre%5B%5D=${genres.toGenreKey()}&m_orderby=${sort.toMetaKey()}"


    override val sortTypes: Set<String>
        get() = setOf(
            "Relevancia" ,
            "A-Z",
            "Último",
            "Más Vistos",
            "Tendencia",
            "Clasificación",
        )


    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
        return FormBody.Builder().apply {
            add("vars[paged]", page.toString())
            add("vars[posts_per_page]", "20")
            add("template", "madara-core/content/content-archive")
            add("page", (page -1).toString())
            add("vars[orderby]", "wp-manga")
            add("vars[paged]", "1")
            add("vars[meta_key]", "_latest_update")
            add("action",  "madara_load_more")
            add("vars[orderby]", "meta_value_num")
            add("vars[sidebar]", "right")
        }.build()
    }

    private val genreToMetaKeyMap = mapOf(
        "Relevancia" to "",
        "Último" to "latest",
        "A-Z"   to "alphabet",
        "Tendencia" to "trending",
        "Clasificación" to "rating",
        "Más Vistos" to "views",
    )

    fun String.toMetaKey(): String {
        return genreToMetaKeyMap[this].toString()
    }
    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? {
//        return null
       return FormBody.Builder().apply {
            add("vars[s]", searchType.query)
            add("vars[posts_per_page]", "20")
            add("template", "madara-core/content/content-archive")
            add("page", "0")
            add("vars[orderby]", "wp-manga")
            add("vars[paged]", "1")
            add("action",  "madara_load_more")
            add("vars[orderby]", "meta_value_num")
            add("vars[sidebar]", "right")
        }.build()
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? {
//        return null

       return FormBody.Builder().apply {
            add("vars[s]", searchType.query)
            add("vars[wp-manga-genre]", searchType.genres)
            add("template", "madara-core/content/content-archive")
            add("page", "0")
            add("vars[orderby]", "wp-manga")
            add("vars[paged]", "1")
            add("action",  "madara_load_more")
            add("vars[orderby]", "meta_value_num")
            add("vars[sidebar]", "right")
        }.build()
    }

    override fun sortFormBody(searchType: SearchType.SORT): FormBody? {
//        return null

       return FormBody.Builder().apply {
            add("vars[s]", searchType.query)
            add("vars[wp-manga-genre]", searchType.genres)
            add("vars[meta_key]", searchType.sortType.toMetaKey())
            add("template", "madara-core/content/content-archive")
            add("page", "0")
            add("vars[posts_per_page]", "20")
            add("vars[orderby]", "wp-manga")
            add("vars[paged]", "1")
            add("action",  "madara_load_more")
            add("vars[orderby]", "meta_value_num")
            add("vars[sidebar]", "right")
        }.build()
    }
    override fun extractCustomHomeMangaItems(html: String): MutableList<MangaItem> {
        val doc: Document = Jsoup.parse(html)
        return doc.select("div.page-item-detail.type.manga").map { el ->
            // Title & URL
            val aTitle = el.selectFirst(".post-title a")!!
            val title = aTitle.text().trim()
            val url   = aTitle.absUrl("href")

            // Image URL
            val img = el.selectFirst(".item-thumb img")!!
            val imageUrl = img.absUrl("src")

            // Chapters (may be multiple, here we grab all listed)

            // Genres: not in sample HTML, so defaulting to empty.
            // If you later add <span class="genre"> tags, you can change this selector.
            val genres = emptyList<String>()

            MangaItem(
                api       = API,
                language  = LANGUAGE,
                title     = title,
                url       = url,
                imageUrl  = imageUrl,
                rating    = null,      // no rating in sample HTML
                chapters = emptyList(),
                genres    = genres
            )
        }.toMutableList()
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val doc: Document = Jsoup.parse(html, baseUrl.ifBlank { BASE_URL })
        val items = mutableListOf<MangaItem>()

        // Each manga card
        val listings = doc.select("div.manga__item")
        listings.forEach { el ->
            // Title & URL
            val aTitle = el.selectFirst(".post-title a")
            val title = aTitle?.text()?.trim().orEmpty()
            val href = aTitle?.attr("href").orEmpty()
            val absHref = aTitle?.absUrl("href").takeIf { it?.isNotBlank() == true } ?: href

            // Image
            val imgEl = el.selectFirst(".manga__thumb_item img")
            val src = imgEl?.attr("src").orEmpty()
            val absSrc = imgEl?.absUrl("src").takeIf { it?.isNotBlank() == true } ?: src

            // Genres
            val genres = el.select(".manga-genres a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }

            // Chapters


            // Build item
            items += MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = absHref,
                imageUrl = absSrc,
                rating = null,
                chapters = emptyList(),
                genres = genres
            )
        }

        return items
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        val doc: Document = Jsoup.parse(html, baseUrl.ifBlank { BASE_URL })
        val items = mutableListOf<PopularManga>()

        // Each manga entry
        val listings = doc.select("div.manga__item")

        listings.forEach { el ->
            // Title & URL
            val aTitle = el.selectFirst(".post-title a")
            val title = aTitle?.text()?.trim().orEmpty()
            val href = aTitle?.attr("href").orEmpty()
            val absHref = aTitle?.absUrl("href").takeIf { it?.isNotBlank() == true } ?: href

            // Image
            val imgEl = el.selectFirst(".manga__thumb_item img")
            val src = imgEl?.attr("src").orEmpty()
            val absSrc = imgEl?.absUrl("src").takeIf { it?.isNotBlank() == true } ?: src

            // Genres (optional, can be included in PopularManga if your model supports it)


            // Add to list
            items += PopularManga(
                api = API,
                language = LANGUAGE,
                title = title,
                url = absHref,
                imageUrl = absSrc,
                // add genres here if PopularManga has a field for it
            )
        }

        return items
    }

    override suspend fun extractMangaInfo(
        html: String,
        baseUrl: String
    ): MangaInfo {
        val doc: Document = Jsoup.parse(html)

        val chapters = doc.select("ul.main.version-chap li.wp-manga-chapter").map { el ->
            val link = el.selectFirst("a")!!
            val chapterName = link.text()
            val chapterNumber = chapterName
                .replace(Regex("[^\\d.]"), "")             // remove everything except digits and '.'
                .trim()
            val chapterUrl    = link.attr("href")

            val dateEl = el.selectFirst("span.chapter-release-date")!!
            val rawDate = if (dateEl.selectFirst("span.c-new-tag") != null) {
                // marked as NEW: try img@alt
                dateEl.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() } ?: "NEW"
            } else {
                dateEl.selectFirst("i")?.text() ?: ""
            }

            val parsed = parseChapterDate(rawDate) ?: LocalDate.now()
            Log.i("slgjsflgfsdgdsfgdsfsgdfgdg1",chapterUrl.toString())

            ChapterItem(
                number = chapterNumber,
                name = chapterName,
                url = chapterUrl,
                date = parsed,
            )
        }.toMutableList()


        return MangaInfo(
            title = doc.selectFirst("h1.post-title")?.text().orEmpty(),

            imageUrl         = doc.selectFirst("div.summary_image img")?.attr("src").orEmpty(),
            rating           = doc.selectFirst("span#averagerate")?.text().orEmpty(),
            ratingCount      = doc.selectFirst("span#countrate")?.text().orEmpty(),
            description      =  doc.selectFirst("div.description-summary")
            ?.select("p")
            ?.joinToString("\n\n") { it.text().trim() }
            .orEmpty(),
            otherNames       = "",
            author           = doc.selectFirst("div.author-content")?.text().orEmpty(),
            artist           = doc.selectFirst("div.artist-content")?.text().orEmpty(),
//            genres           = doc.select("div.genres-content a").eachText(),
            genres = doc.select(".post-content_item.genres .summary-content a").eachText(),

            tags             = doc.select("div.tags-content a").eachText(),
            yearOfProduction = doc.select("div.summary-content")
                .find { it.select("h5:contains(سنة الانتاج)").isNotEmpty() }
                ?.text().orEmpty(),
            status = doc.select("div.manga-status span")
                .let { if (it.isNotEmpty()) it.last()?.text()?.trim() else "" }
                ?.ifBlank {
                    // fallback to older selector (if some sites use labeled summary-content blocks)
                    doc.select("div.summary-content")
                        .find { sc -> sc.select("h5:contains(Estado), h5:contains(الحالة)").isNotEmpty() }
                        ?.text()
                        .orEmpty()
                } ?: "",

            favoritesCount   = doc.selectFirst("div.add-bookmark .action_detail span")?.text().orEmpty(),
            chapters         = chapters,
            api              = API,
            url              = baseUrl,
            language         = LANGUAGE
        )
    }
    fun parseChapterDate(raw: String): LocalDate {
        val trimmed = raw.trim()

        // 1) Literal NEW → today
        if (trimmed.equals("NEW", ignoreCase = true)) {
            return LocalDate.now()
        }

        // 2) Absolute Spanish‐style date dd/MM/yyyy
        val absoluteFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        try {
            return LocalDate.parse(trimmed, absoluteFormatter)
        } catch (_: DateTimeParseException) {
            // not in dd/MM/yyyy form
        }

        // 3) Spanish relative times e.g. "2 días hace", "3 horas hace"
        //    tokens: [amount, unit, "hace"]
        val parts = trimmed.split("\\s+".toRegex())
        if (parts.size == 3 && parts[2].equals("hace", ignoreCase = true)) {
            parts[0].toLongOrNull()?.let { amount ->
                val unit = parts[1].lowercase()
                return when {
                    "día" in unit    || "dias" in unit    -> LocalDate.now().minus(amount, ChronoUnit.DAYS)
                    "hora" in unit   || "horas" in unit   -> LocalDate.now()  // same-day
                    "semana" in unit -> LocalDate.now().minus(amount * 7, ChronoUnit.DAYS)
                    "mes" in unit    || "meses" in unit   -> LocalDate.now().minus(amount, ChronoUnit.MONTHS)
                    "año" in unit    || "años" in unit    -> LocalDate.now().minus(amount, ChronoUnit.YEARS)
                    else             -> LocalDate.now()
                }
            }
        }

        // 4) Fallback: unrecognized → today
        return LocalDate.now()
    }
    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html, baseUrl.ifBlank { BASE_URL })
        val items = mutableListOf<MangaItem>()

        // Primary selector for Taurus/Madara search results
        val results = doc.select("div.search-lists div.manga__item").ifEmpty {
            // fallback: some themes use page-content-listing or .page-item-detail
            doc.select(".page-content-listing .page-listing-item, .manga__item, .listing-items .item")
        }



        results.forEach { el ->
            // image anchor & img
            val imgEl = el.selectFirst(".manga__thumb img, img")
            // title anchor: often inside .manga__content .post-title or .post-title h2 a
            val titleLink = el.selectFirst(".manga__content .post-title a, .post-title a, h3 a, h2 a, .post-title h2 a")

            val title = titleLink?.text()?.trim().orEmpty()
            val href = titleLink?.absUrl("href").takeIf { it?.isNotBlank() == true }
                ?: titleLink?.attr("href").orEmpty()

            val imgSrc = imgEl?.absUrl("src").takeIf { it?.isNotBlank() == true }
                ?: imgEl?.attr("src").orEmpty()

            // genres (site uses .manga-genres)
            val genres = el.select(".manga-genres a, .mg_genres .summary-content a, .post-content_item.genres a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }


            items += MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = href,
                imageUrl = imgSrc,
                rating = 0,
                chapters = null,
                genres = genres
            )
        }

        return items
    }


    override fun getChapterImages(html: String): List<String> {
        val document: Document = Jsoup.parse(html)
        return document
            // select your chapter images container + image selector
            .select("div.reading-content img.wp-manga-chapter-img")
            .mapNotNull { img ->
                // try lazy load → data-src → src
                val url = listOf(
                    img.attr("data-lazy-src"),
                    img.attr("data-src"),
                    img.attr("src")
                )
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("http") }
                url
                // normalize or drop if still blank
            }


    }

    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language


    override val blackListGenres: Set<String>
        get() = setOf(
            "Adult",
            "Mature",
            "Smut",
            "Soft Yaoi",
            "Soft Yuri",
            "Gender Bender",
//            "+15",
            "Smut",
            "Yaoi",
            "Yuri",
        )
    override val allGenres: Set<String>
        get() = setOf(
            "Acción",
            "Action",
            "Adventure",
            "Angeles",
            "Anime",
            "Anti héroe",
            "Anti‑Héroe",
            "Apocalíptico",
            "Apocalipto",
            "Artes Marciales",
            "Arts",
            "Aventura",
            "Bestias invocadas",
            "Cartoon",
            "Ciencia Ficción",
            "Comedia",
            "Comedy",
            "Comic",
            "Cooking",
            "Crimen",
            "Cultivación",
            "Demonio",
            "Demonios",
            "Deportes",
            "Detective",
            "Distopico",
            "Donghua",
            "Doujinshi",
            "Drama",
            "Ecchi",
            "Evolución",
            "Familia",
            "Fantasía",
            "Fantasy",
            "Gore",
            "Guerra",
            "Harem",
            "Historia",
            "Historical",
            "Horror",
            "Inmersión",
            "Invocador",
            "Josei",
            "Live action",
            "Magia",
            "Manga",
            "Manhua",
            "Manhwa",
            "Martial",
            "Martial Arts",
            "Mecha",
            "Medicina",
            "Meian",
            "Militar",
            "Misterio",
            "Monstruos",
            "Murim",
            "Mystery",
            "Novela",
            "One shot",
            "Parodia",
            "Posible‑Harem",
            "Post‑apocalíptico",
            "Psicológico",
            "Psychological",
            "Puto‑Amo",
            "Realidad",
            "Realidad Virtual",
            "Recuentos de la vida",
            "Reencarnación",
            "Retornado",
            "Rey Demonio",
            "Romance",
            "School Life",
            "Sci‑fi",
            "Seinen",
            "Shoujo",
            "Shoujo Ai",
            "Shounen",
            "Shounen Ai",
            "Sistema",
            "Slice of Life",
            "Sobrenatural",
            "Sports",
            "Super poderes",
            "Supernatural",
            "Superpoderes",
            "Supervivencia",
            "Tragedia",
            "Tragedy",
            "Transmigración entre mundos",
            "Venganza",
            "Viajes en el tiempo",
            "Vida Escolar",
            "Webtoon",
            "Zombies",


            )
    val extractedGenreSlugs: Set<String> = setOf(
        "15","accion","action","adventure","angeles","anime","anti-heroe","anti-heroe-2",
        "apocaliptico","apocalipto","artes-marciales","arts","aventura","bestias-invocadas",
        "cartoon","ciencia-ficcion","comedia","comedy","comic","cooking","crimen",
        "cultivacion","demonio","demonios","deportes","detective","distopico","donghua",
        "doujinshi","drama","evolucion","familia","fantasia","fantasy","gender-bender",
        "gore","guerra","harem","historia","historical","horror","inmersion","invocador",
        "josei","live-action","magia","manga","manhua","manhwa","martial","martial-arts",
        "mature","mecha","medicina","meian","militar","misterio","monstruos","murim",
        "mystery","novela","one-shot","parodia","posible-harem","post-apocaliptico",
        "psicologico","psychological","puto-amo","realidad","realidad-virtual",
        "recuentos-de-la-vida","reencarnacion","retornado","rey-demonio","romance",
        "school-life","sci-fi","seinen","shoujo","shoujo-ai","shounen","shounen-ai",
        "sistema","sistemas","slice-of-life","smut","sobrenatural","soft-yaoi",
        "soft-yuri","sports","super-poderes","supernatural","superpoderes",
        "supervivencia","tragedia","tragedy","transmigracion-entre-mundos","urbano",
        "venganza","viajes-en-el-tiempo","vida-escolar","webtoon","zombies"
    )

    // 3) helper to “slugify” a human name
    fun String.toGenreKey(): String {
        val noAccents = Normalizer
            .normalize(this, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noAccents
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }
}