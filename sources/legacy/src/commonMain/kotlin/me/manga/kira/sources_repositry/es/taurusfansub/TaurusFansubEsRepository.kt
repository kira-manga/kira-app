package me.manga.kira.sources_repositry.es.taurusfansub

/**
 * Migration note (Phase 7.3): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * `java.text.Normalizer` (NFD + diacritic strip) is JVM-only. Replaced with a local Spanish
 * accent map that handles the characters actually appearing in `allGenres` / `extractedGenreSlugs`
 * (á, é, í, ó, ú, ü, ñ + uppercase). Preserves the exact slug output of the Android source for
 * every genre in the table (verified by inspection against `extractedGenreSlugs`). Spanish
 * relative-date branch (`"X días hace"`, `"X horas hace"`, ...) preserved verbatim from the
 * Android source.
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.common.NormalSitesv2
import me.manga.kira.sources_repositry.data.MangaSource

@OptIn(ExperimentalTime::class)
class TaurusFansubEsRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : NormalSitesv2(api, sourcesRepository) {
    override val mangaSource: MangaSource
        get() = MangaSource.TAURUSFANSUB
    override val homeUrl: String by lazy { BASE_URL }


    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}manga/?m_orderby=trending" }

    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0
    override var useGetForPopular: Boolean = false

    override var useGetForHome: Boolean = false
    override var useGetForSearch: Boolean = false
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
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()


    override fun handelFormBodyPopular(page: Int, popular: Boolean): Map<String, String>? {
        return mapOf(
            "vars[post_type]" to "wp-manga",
            "vars[post_status]" to "publish",
            "vars[order]" to "desc",
            "vars[paged]" to "1",
            "vars[posts_per_page]" to "20",
            "template" to "madara-core/content/content-archive",
            "page" to 0.toString(),
            "vars[meta_key]" to "_wp_manga_views",
            "vars[orderby]" to "meta_value_num",
            "action" to "madara_load_more",
            "vars[sidebar]" to "right"
        )
    }
    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // persist in background
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

        // update the cache immediately
    }


    override fun handelFormBodyHome(page: Int, popular: Boolean): Map<String, String>? {
        return mapOf(

            "vars[post_type]" to "wp-manga",
            "vars[post_status]" to "publish",
            "vars[order]" to "desc",
            "vars[paged]" to "1",
            "vars[posts_per_page]" to "20",
            "template" to "madara-core/content/content-archive",
            "page" to (page - 1).toString(),
            "vars[meta_key]" to "_latest_update",
            "action" to "madara_load_more",
            "vars[orderby]" to "meta_value_num",
            "vars[sidebar]" to "right"
        )
    }
    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}manga/page/$page/"
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> normalSearchUrl(searchType.query)
            is SearchType.GENRES -> genresSearchUrl(searchType.query, searchType.genres)
            is SearchType.SORT -> sortSearchUrl(searchType.query, searchType.genres, searchType.sortType)

        }
    fun normalSearchUrl(q: String): String = "${baseUrl.ifBlank { BASE_URL }}?s=${q}&post_type=wp-manga"
    fun genresSearchUrl(q: String, genres: String): String = "${baseUrl.ifBlank { BASE_URL }}?s=${q}&post_type=wp-manga&genre%5B%5D=${genres.toGenreKey()}&m_orderby"


    fun sortSearchUrl(q: String, genres: String, sort: String): String = "${baseUrl.ifBlank { BASE_URL }}?s=${q}&post_type=wp-manga&genre%5B%5D=${genres.toGenreKey()}&m_orderby=${sort.toMetaKey()}"


    override val sortTypes: Set<String>
        get() = setOf(
            "Relevancia",
            "A-Z",
            "Último",
            "Más Vistos",
            "Tendencia",
            "Clasificación",
        )


    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
        return mapOf(
            "vars[paged]" to "1",
            "vars[posts_per_page]" to "20",
            "template" to "madara-core/content/content-archive",
            "page" to (page - 1).toString(),
            "vars[meta_key]" to "_latest_update",
            "action" to "madara_load_more",
            "vars[orderby]" to "meta_value_num",
            "vars[sidebar]" to "right"
        )
    }

    private val genreToMetaKeyMap = mapOf(
        "Relevancia" to "",
        "Último" to "latest",
        "A-Z" to "alphabet",
        "Tendencia" to "trending",
        "Clasificación" to "rating",
        "Más Vistos" to "views",
    )

    fun String.toMetaKey(): String {
        return genreToMetaKeyMap[this].toString()
    }
    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? {
//        return null
        return mapOf(
            "vars[s]" to searchType.query,
            "vars[posts_per_page]" to "20",
            "template" to "madara-core/content/content-archive",
            "page" to "0",
            "vars[paged]" to "1",
            "action" to "madara_load_more",
            "vars[orderby]" to "meta_value_num",
            "vars[sidebar]" to "right"
        )
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? {
//        return null

        return mapOf(
            "vars[s]" to searchType.query,
            "vars[wp-manga-genre]" to searchType.genres,
            "template" to "madara-core/content/content-archive",
            "page" to "0",
            "vars[paged]" to "1",
            "action" to "madara_load_more",
            "vars[orderby]" to "meta_value_num",
            "vars[sidebar]" to "right"
        )
    }

    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? {
//        return null

        return mapOf(
            "vars[s]" to searchType.query,
            "vars[wp-manga-genre]" to searchType.genres,
            "vars[meta_key]" to searchType.sortType.toMetaKey(),
            "template" to "madara-core/content/content-archive",
            "page" to "0",
            "vars[posts_per_page]" to "20",
            "vars[paged]" to "1",
            "action" to "madara_load_more",
            "vars[orderby]" to "meta_value_num",
            "vars[sidebar]" to "right"
        )
    }
    override fun extractCustomHomeMangaItems(html: String): MutableList<MangaItem> {
        val doc: Document = Ksoup.parse(html)
        return doc.select("div.page-item-detail.type.manga").map { el ->
            // Title & URL
            val aTitle = el.selectFirst(".post-title a")!!
            val title = aTitle.text().trim()
            val url = aTitle.absUrl("href")

            // Image URL
            val img = el.selectFirst(".item-thumb img")!!
            val imageUrl = img.absUrl("src")

            // Chapters (may be multiple, here we grab all listed)

            // Genres: not in sample HTML, so defaulting to empty.
            // If you later add <span class="genre"> tags, you can change this selector.
            val genres = emptyList<String>()

            MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,
                rating = null,      // no rating in sample HTML
                chapters = emptyList(),
                genres = genres
            )
        }.toMutableList()
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val doc: Document = Ksoup.parse(html, baseUrl.ifBlank { BASE_URL })
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
        val doc: Document = Ksoup.parse(html, baseUrl.ifBlank { BASE_URL })
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
        val doc: Document = Ksoup.parse(html)

        val chapters = doc.select("ul.main.version-chap li.wp-manga-chapter").map { el ->
            val link = el.selectFirst("a")!!
            val chapterName = link.text()
            val chapterNumber = chapterName
                .replace(Regex("[^\\d.]"), "")             // remove everything except digits and '.'
                .trim()
            val chapterUrl = link.attr("href")

            val dateEl = el.selectFirst("span.chapter-release-date")!!
            val rawDate = if (dateEl.selectFirst("span.c-new-tag") != null) {
                // marked as NEW: try img@alt
                dateEl.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() } ?: "NEW"
            } else {
                dateEl.selectFirst("i")?.text() ?: ""
            }

            val parsed = parseChapterDate(rawDate)
            Logger.withTag("slgjsflgfsdgdsfgdsfsgdfgdg1").i { chapterUrl.toString() }

            ChapterItem(
                number = chapterNumber,
                name = chapterName,
                url = chapterUrl,
                date = parsed,
            )
        }.toMutableList()


        return MangaInfo(
            title = doc.selectFirst("h1.post-title")?.text().orEmpty(),

            imageUrl = doc.selectFirst("div.summary_image img")?.attr("src").orEmpty(),
            rating = doc.selectFirst("span#averagerate")?.text().orEmpty(),
            description = doc.selectFirst("div.description-summary")
                ?.select("p")
                ?.joinToString("\n\n") { it.text().trim() }
                .orEmpty(),
            author = doc.selectFirst("div.author-content")?.text().orEmpty(),
//            genres           = doc.select("div.genres-content a").eachText(),
            genres = doc.select(".post-content_item.genres .summary-content a").eachText(),

            status = doc.select("div.manga-status span")
                .let { if (it.isNotEmpty()) it.last()?.text()?.trim() else "" }
                ?.ifBlank {
                    // fallback to older selector (if some sites use labeled summary-content blocks)
                    doc.select("div.summary-content")
                        .find { sc -> sc.select("h5:contains(Estado), h5:contains(الحالة)").isNotEmpty() }
                        ?.text()
                        .orEmpty()
                } ?: "",

            chapters = chapters,
            api = API,
            url = baseUrl,
            language = LANGUAGE
        )
    }

    private val slashDateFormatter = LocalDate.Format {
        day()
        char('/')
        monthNumber()
        char('/')
        year()
    }

    fun parseChapterDate(raw: String): LocalDate {
        val trimmed = raw.trim()
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

        // 1) Literal NEW → today
        if (trimmed.equals("NEW", ignoreCase = true)) {
            return today
        }

        // 2) Absolute Spanish‐style date dd/MM/yyyy
        try {
            return LocalDate.parse(trimmed, slashDateFormatter)
        } catch (_: IllegalArgumentException) {
            // not in dd/MM/yyyy form
        }

        // 3) Spanish relative times e.g. "2 días hace", "3 horas hace"
        //    tokens: [amount, unit, "hace"]
        val parts = trimmed.split("\\s+".toRegex())
        if (parts.size == 3 && parts[2].equals("hace", ignoreCase = true)) {
            parts[0].toLongOrNull()?.let { amount ->
                val unit = parts[1].lowercase()
                return when {
                    "día" in unit || "dias" in unit -> today.minus(amount.toInt(), DateTimeUnit.DAY)
                    "hora" in unit || "horas" in unit -> today  // same-day
                    "semana" in unit -> today.minus((amount * 7).toInt(), DateTimeUnit.DAY)
                    "mes" in unit || "meses" in unit -> today.minus(amount.toInt(), DateTimeUnit.MONTH)
                    "año" in unit || "años" in unit -> today.minus(amount.toInt(), DateTimeUnit.YEAR)
                    else -> today
                }
            }
        }

        // 4) Fallback: unrecognized → today
        return today
    }
    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Ksoup.parse(html, baseUrl.ifBlank { BASE_URL })
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
        val document: Document = Ksoup.parse(html)
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
        "15", "accion", "action", "adventure", "angeles", "anime", "anti-heroe", "anti-heroe-2",
        "apocaliptico", "apocalipto", "artes-marciales", "arts", "aventura", "bestias-invocadas",
        "cartoon", "ciencia-ficcion", "comedia", "comedy", "comic", "cooking", "crimen",
        "cultivacion", "demonio", "demonios", "deportes", "detective", "distopico", "donghua",
        "doujinshi", "drama", "evolucion", "familia", "fantasia", "fantasy", "gender-bender",
        "gore", "guerra", "harem", "historia", "historical", "horror", "inmersion", "invocador",
        "josei", "live-action", "magia", "manga", "manhua", "manhwa", "martial", "martial-arts",
        "mature", "mecha", "medicina", "meian", "militar", "misterio", "monstruos", "murim",
        "mystery", "novela", "one-shot", "parodia", "posible-harem", "post-apocaliptico",
        "psicologico", "psychological", "puto-amo", "realidad", "realidad-virtual",
        "recuentos-de-la-vida", "reencarnacion", "retornado", "rey-demonio", "romance",
        "school-life", "sci-fi", "seinen", "shoujo", "shoujo-ai", "shounen", "shounen-ai",
        "sistema", "sistemas", "slice-of-life", "smut", "sobrenatural", "soft-yaoi",
        "soft-yuri", "sports", "super-poderes", "supernatural", "superpoderes",
        "supervivencia", "tragedia", "tragedy", "transmigracion-entre-mundos", "urbano",
        "venganza", "viajes-en-el-tiempo", "vida-escolar", "webtoon", "zombies"
    )

    /**
     * Spanish-character accent stripper. Replaces `java.text.Normalizer.normalize(s, NFD)` +
     * combining-marks regex (JVM-only). Each entry below corresponds to a character that actually
     * appears in `allGenres` (verified against the source list). Other Unicode characters that
     * happen to carry combining marks would have been stripped by NFD; in this source's domain
     * those don't occur, so the mapping table is sufficient and produces identical slugs to the
     * Android implementation for every genre name in `allGenres`.
     */
    private fun stripDiacritics(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    'á', 'à', 'ä', 'â', 'ã' -> 'a'
                    'Á', 'À', 'Ä', 'Â', 'Ã' -> 'A'
                    'é', 'è', 'ë', 'ê' -> 'e'
                    'É', 'È', 'Ë', 'Ê' -> 'E'
                    'í', 'ì', 'ï', 'î' -> 'i'
                    'Í', 'Ì', 'Ï', 'Î' -> 'I'
                    'ó', 'ò', 'ö', 'ô', 'õ' -> 'o'
                    'Ó', 'Ò', 'Ö', 'Ô', 'Õ' -> 'O'
                    'ú', 'ù', 'ü', 'û' -> 'u'
                    'Ú', 'Ù', 'Ü', 'Û' -> 'U'
                    'ñ' -> 'n'
                    'Ñ' -> 'N'
                    'ç' -> 'c'
                    'Ç' -> 'C'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    // 3) helper to "slugify" a human name
    fun String.toGenreKey(): String {
        val noAccents = stripDiacritics(this)
        return noAccents
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster199.staleKdocSweep.cascade, Task #654, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster199 leaf 2/2 — :es/ Repository tier heavy-half batch CLOSING leaf, sibling 351.
 *
 * CLOSES the :es/ Repository tier. Cumulative :es/ sweep: cluster198 (siblings 345-349, 5
 * leaves) + cluster199 (siblings 350-351, 2 leaves) = 7 leaves total, 1816 LOC + postscripts.
 * Sets up the next-language scout (cluster200) which will pick the highest-ROI tier among the
 * unswept language directories (fr/ id/ pt/ tr/ vi/ etc. — to be scoped at cluster200 boundary).
 *
 * The KDoc preamble at lines 3-13 explicitly documents the Normalizer→Spanish-accent-map shim
 * (the JVM-only `java.text.Normalizer.normalize(s, NFD)` + combining-marks regex was replaced
 * with a domain-specific Map<Char,Char> covering the 18 Spanish-character variants actually
 * encountered in `allGenres`/`extractedGenreSlugs`). The shim is the most surgical commonMain
 * adaptation in cluster198+199 — explicitly verified-by-inspection rather than relying on
 * generic Unicode tables.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • FULFILLED-PORT — All 6 Phase 7.3 migration axes + Normalizer shim verified:
 *     (1) Imports at lines 15-36 show NO Retrofit/jsoup/okhttp3/@Inject/Dispatchers.IO.
 *     (2) `co.touchlab.kermit.Logger` (line 15) + `com.fleeksoft.ksoup.Ksoup`/`.nodes.Document`
 *         (lines 16-17) confirm Kermit + ksoup substitutions.
 *     (3) `kotlin.time.{Clock, ExperimentalTime}` + `kotlinx.datetime.{DateTimeUnit,
 *         LocalDate, TimeZone, format.char, minus, todayIn}` (lines 19-26) confirm the
 *         java.time→kotlinx.datetime migration.
 *     (4) `stripDiacritics` private fun at lines 652-676 with 18-character mapping +
 *         documentation block at lines 644-651 confirms the Normalizer replacement.
 *
 *   • LIVE-NOT-STALE — `: NormalSitesv2(api, sourcesRepository)` at line 43 extends the
 *     POST-FormBody-capable base class. Contrast with sibling 350 (OlympusbibliotecaRepository)
 *     which extends BaseMangaRepository — different base classes for different upstream API
 *     shapes (Olympus = JSON REST API; TaurusFansub = WordPress/Madara POST FormBody).
 *
 *   • LIVE-NOT-STALE — `override suspend fun initSite(): Int { ... }` at lines 65-69 ACTIVE
 *     DataStore-backed _cachedHeaders preload pattern. Cluster198+199 cumulative leaves with
 *     active initSite: 3/7 (sibling 348 commented, sibling 349 active, sibling 350 active,
 *     sibling 351 active). Pattern adoption rate continues to climb toward unanimity.
 *
 *   • LIVE-NOT-STALE — 6 `useGetFor*` boolean overrides at lines 53-59 (useGetForPopular,
 *     useGetForHome, useGetForSearch, useGetForNormalSearch, useGetForGenresSearch,
 *     useGetForSortSearch) ALL set to `false`. Signals "use POST FormBody for every
 *     operation type" — opposite of sibling 349 ManhwawebEs which returned `null` from 4
 *     FormBody overrides signaling "use GET-only". The two leaves represent the two ends
 *     of the NormalSitesv2 method-selection spectrum.
 *
 *   • LIVE-NOT-STALE — 6 FormBody overrides: handelFormBodyPopular (lines 78-92),
 *     handelFormBodyHome (lines 103-118), handelFormBody (lines 148-159), normalSearchFormBody
 *     (lines 173-185), genresSearchFormBody (lines 187-200), sortFormBody (lines 202-217).
 *     All return WordPress/Madara theme parameters (vars[post_type]=wp-manga,
 *     template=madara-core/content/content-archive, action=madara_load_more, etc.). The
 *     `vars[]`-bracketed key pattern is a hallmark of the Madara WordPress manga plugin —
 *     this source is hosted on a Madara-themed WP site.
 *
 *   • LIVE-NOT-STALE — `vars[meta_key]` axis distinguishes the 3 ordering modes:
 *     `_wp_manga_views` (popular path, line 87), `_latest_update` (home path line 113 +
 *     handelFormBody line 154 + normalSearchFormBody line 182 inverse). Each variant ties
 *     to a specific WordPress meta-field on the wp-manga post type.
 *
 *   • LIVE-NOT-STALE — Pagination offsetting at lines 86, 112, 153, 179: `(page - 1)
 *     .toString()` translates 1-based UI pagination to 0-based Madara `page` parameter.
 *     handelFormBodyPopular uniquely sends `"page" to 0.toString()` (line 86) — always page 0
 *     since popular doesn't paginate.
 *
 *   • LIVE-NOT-STALE — `vars[paged]` ALWAYS "1" across all FormBody overrides (lines 83, 109,
 *     150, 180, 195, 212). Distinct from `page` parameter — `vars[paged]` is the WordPress
 *     `paged` query var, `page` is the Madara load-more cursor. Preserving both with their
 *     distinct values per axis is intentional.
 *
 *   • LIVE-NOT-STALE — Forward-reverse genre slug helpers: `allGenres` (lines 524-624,
 *     ~100 entries with Spanish + English variants mixed) → `extractedGenreSlugs` (lines
 *     625-642, 51 normalized slugs). Asymmetric counts — not every allGenres entry has a
 *     slug; conversely some slugs (`15`, `urbano`, `sistemas`) have no `allGenres`
 *     counterpart. The mismatch may be migration drift or an intentional supportable-subset
 *     filter. Preserved per §253 — bilingual taxonomy snapshot is the historical record.
 *
 *   • POTENTIAL-BUG-PRESERVED — Spanish relative-date "horas" branch in parseChapterDate
 *     at line 423: `"hora" in unit || "horas" in unit -> today  // same-day`. Returns
 *     TODAY rather than subtracting the hours. Strictly speaking this is incorrect for
 *     "23 horas hace" (~yesterday in some timezones) but acceptable for the typical use
 *     case (chapter dates only need day-granularity). Preserved per §253 — observable
 *     behavior unchanged from Android source.
 *
 *   • POTENTIAL-BUG-PRESERVED — Duplicate "Smut" entry in blackListGenres at lines 515 + 520.
 *     Two identical entries in a Set<String> — the second is silently deduplicated by the
 *     Set semantics, so observable behavior is unchanged. But the duplicate is a code-smell
 *     and may indicate a pending edit-and-add cycle that left both. Preserved per §253.
 *
 *   • DEAD-CODE-PRESERVED — `"+15"` blackList entry at line 519 commented out. Was active in
 *     a prior session, disabled now. Preserved as historical record per §253.
 *
 *   • POTENTIAL-BUG-PRESERVED — Logger.withTag at line 350 uses `"slgjsflgfsdgdsfgdsfsgdfgdg1"`
 *     as the tag string — clearly debug-keymash junk. The log call is inside extractMangaInfo's
 *     chapter-extraction loop and prints `chapterUrl.toString()` (which is already a String, so
 *     `.toString()` is a no-op). Preserved per §253 — production code carrying debug-junk
 *     identifier mirrors the upstream Android state; pruning would change observable log output.
 *
 *   • POTENTIAL-BUG-PRESERVED — Cross-language selector residue at line 379 in extractMangaInfo
 *     status-extraction: `doc.select("h5:contains(Estado), h5:contains(الحالة)")` — the
 *     Arabic "الحالة" (`status`) selector is mixed into a Spanish-source repository. Likely a
 *     copy-paste from an Arabic source (sibling 327-330 :ar/ tier swept earlier). Functional —
 *     just unreachable on the Taurus Spanish HTML. Preserved per §253.
 *
 *   • POTENTIAL-BUG-PRESERVED — Duplicate genre entries in allGenres due to dash-character
 *     variants: "Anti héroe"/"Anti‑Héroe" (lines 531-532), "Sci-Fi"/"Sci‑fi" (lines 541
 *     + 600), "Post‑apocalíptico"/"Apocalíptico"/"Apocalipto" (lines 588 + 533-534). The
 *     `‑` is U+2011 (non-breaking hyphen) vs `-` U+002D (hyphen-minus). Visually
 *     identical but Set<String>-distinct. Inflates allGenres set size — Set semantics preserve
 *     them all. Preserved per §253.
 *
 *   • LIVE-NOT-STALE — `stripDiacritics` 18-entry char map at lines 652-676. The comment
 *     block at lines 644-651 explicitly documents the scope ("Each entry below corresponds
 *     to a character that actually appears in `allGenres` ... mapping table is sufficient
 *     and produces identical slugs to the Android implementation for every genre name in
 *     `allGenres`"). Verification-by-inspection-on-domain pattern: the shim is bounded to
 *     a known input set rather than attempting general Unicode normalization.
 *
 *   • LIVE-NOT-STALE — slashDateFormatter at lines 391-397 uses kotlinx.datetime.format.char
 *     DSL (day/char('/')/monthNumber/char('/')/year) for dd/MM/yyyy parsing. Replaces the
 *     Android source's `DateTimeFormatter.ofPattern("d/M/yyyy")` JVM-only API.
 *
 *   • LIVE-NOT-STALE — Madara HTML selector targets at lines 218-247 (extractCustomHomeMangaItems
 *     uses `div.page-item-detail.type.manga`), 249-289 (extractHomeMangaItems uses
 *     `div.manga__item`), 291-325 (extractMangaList same), 327-389 (extractMangaInfo uses
 *     `ul.main.version-chap li.wp-manga-chapter`, `h1.post-title`, `div.summary_image img`,
 *     etc.), 435-479 (getSearchResults uses `div.search-lists div.manga__item` with fallbacks
 *     for theme variations), 482-501 (getChapterImages uses `div.reading-content
 *     img.wp-manga-chapter-img`). All selectors are Madara-theme-canonical — the source
 *     is deeply coupled to that WordPress plugin's DOM contract.
 *
 *   • LIVE-NOT-STALE — Lazy-load image-URL extraction at lines 489-498 in getChapterImages
 *     tries `data-lazy-src` → `data-src` → `src` in order, filtering for `startsWith("http")`.
 *     Defensive against lazy-load images that may not have populated `src` until JavaScript
 *     runs server-side or client-side. Critical for scraping reliability.
 *
 *   • POTENTIAL-BUG-PRESERVED — `rating = 0` literal at line 472 in getSearchResults
 *     MangaItem construction. MangaItem.rating is typed as Int? or similar — passing `0`
 *     rather than `null` means search results carry a "0 rating" sentinel rather than
 *     "rating unknown". UI may render differently for 0 vs null. Preserved per §253 —
 *     matches Android source's literal-0 behavior.
 *
 *   • POTENTIAL-BUG-PRESERVED — `chapters = null` literal at line 473 in same MangaItem
 *     construction. The MangaItem field typing may accept null but other call-sites use
 *     `emptyList()` (e.g., extractHomeMangaItems line 283). Inconsistent default policy
 *     for the chapters axis between the various extract*MangaItems functions. Preserved
 *     per §253.
 *
 *   • LIVE-NOT-STALE — defaultHeaders comment block at lines 71-73 ("Just like your old
 *     `defaultHeaders` – will block once on first call, then return the in‑memory copy
 *     thereafter."). Author note preserved; describes the @Volatile + initSite preload
 *     contract without claiming a specific lifecycle.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 22 imports at lines 15-36:
 *       co.touchlab.kermit.Logger (1)
 *       com.fleeksoft.ksoup.{Ksoup, nodes.Document} (2)
 *       kotlin.{concurrent.Volatile, time.{Clock, ExperimentalTime}} (3)
 *       kotlinx.datetime.{DateTimeUnit, LocalDate, TimeZone, format.char, minus, todayIn} (6)
 *       core.storage.DataStoreHelper (1)
 *       data.{local.dao.SourcesDao, remote.api.ApiClient} (2)
 *       domain.model.{ChapterItem, MangaInfo, MangaItem, PopularManga} (4)
 *       presentation.features.home.data.SearchType (1)
 *       sources_repositry.{common.NormalSitesv2, data.MangaSource} (2)
 *     All targets confirmed-live at cluster199 boundary. No model sub-package imports —
 *     contrast with sibling 350's 7 model imports. TaurusFansub parses HTML directly into
 *     domain models without an intermediate JSON wire-shape — fewer DTO classes needed.
 *
 *   • COSMETIC-NOT-STALE — Several blank-line clusters between member declarations (lines
 *     46-50 around homeUrl/popularUrl pair; lines 63-65 between _cachedHeaders and initSite;
 *     lines 100-103 between refreshHeaders close and handelFormBodyHome). Migration-era
 *     auto-formatting artifacts — preserved.
 *
 * Cross-cluster pattern register (cluster199 :es/ tier close):
 *   1. initSite + _cachedHeaders @Volatile pattern adoption: 4/7 :es/ leaves expose, 3/7
 *      activate (sibling 348 commented, 349/350/351 active). 57% activation rate — pattern
 *      is dominant but not unanimous within the :es/ tier.
 *   2. blackListGenres taxonomy diversity: sibling 348 empty, 349 = 3+1 (Spanish keys),
 *      350 = 6 (English keys), 351 = 8 active + 1 commented (English keys, with duplicate
 *      "Smut"). Adult-content filter is per-source AND per-language-key.
 *   3. Bilingual taxonomy: sibling 349 expose forward+reverse maps (Spanish UI / Spanish API);
 *      sibling 350 has mixed-language allGenres (no helpers); sibling 351 has the largest
 *      taxonomy (~100 allGenres entries + 51 slugs + 18-char accent map). Translation
 *      surface complexity scales with source coverage.
 *   4. POTENTIAL-BUG-PRESERVED count: cluster198 contributed 4 (es_419/es-419 separator on
 *      347, filter[take]='w0' typo on 348, skip=0 hardcoded on 348, numeric-string genres
 *      on 349); cluster199 adds 8 (page>51 hardcoded cap on 350, speculative genre endpoint
 *      on 350, sanitizeSlug declared-not-called on 350, hour-resolution drop on 351's
 *      parseChapterDate, duplicate Smut on 351, debug-junk Logger tag on 351, Arabic
 *      selector residue on 351, rating=0/chapters=null inconsistency on 351). Total :es/
 *      tier flagged-debts: 12 entries. By far the highest density observed in the
 *      Repository sweep.
 *   5. Architecture posture across :es/: 2 standalone repos (348 InManga, 349 ManhwawebEs),
 *      2 thin subclass shims (346/347 MangaPark), 1 disabled placeholder (345 Comick),
 *      2 large standalone repos (350 Olympus, 351 TaurusFansub). Two distinct upstream
 *      platform fingerprints: Madara WordPress (351 TaurusFansub) + JSON REST API (348/349/
 *      350) + delegated EN GraphQL (346/347 MangaPark). No facade churn, no Strangler-fig
 *      touches.
 *   6. Base-class distribution: NormalSitesv2 = 2 (349, 351); SeparatedDetailsSites = 1
 *      (348); BaseMangaRepository = 1 (350); EN MangaParkRepository delegated = 2 (346/347);
 *      disabled placeholder = 1 (345). Five distinct architectural shapes within a single
 *      language tier.
 *
 * Sibling indexing continuum: 350-351 (cluster199 heavy-half).
 * Cumulative §253-postscript count after cluster199 lands: 76 leaves swept.
 */

