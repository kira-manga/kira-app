package me.manga.kira.sources_repositry.pt.flowermanga

/**
 * Migration note (Phase 7.7): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * Gson -> kotlinx.serialization, @Inject dropped, android.util.Log -> Kermit Logger,
 * java.time -> kotlinx.datetime.
 *
 * Portuguese month-name parsing in chapter dates ("27 de julho de 2025") is deferred —
 * kotlinx.datetime cannot resolve locale-aware text months in commonMain. The chapter-date
 * branch now returns `Clock.System.todayIn(...)` as the original Android `getOrDefault` fallback
 * did, so chapter listing behaviour is preserved on the unhappy path. See TODO(Phase 8 - locale)
 * below.
 */

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
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
class FlowerMangaRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
): NormalSitesv2(api, sourcesRepository) {
    override val mangaSource: MangaSource
        get() = MangaSource.FLOWERMANGA
    override val homeUrl: String
        get() = "${baseUrl.ifBlank { BASE_URL }}manga/?m_orderby=latest"
    override val popularUrl: String
        get() = "${baseUrl.ifBlank { BASE_URL }}manga/?m_orderby=views"


    override fun handelLoadMoreUrl(page: Int): String {
      return "${baseUrl.ifBlank { BASE_URL }}manga/page/$page/?m_orderby=latest"
    }
    val json: Json by lazy {
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
    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0

    override fun handelSearchUrl(searchType: SearchType): String {
       return "${baseUrl.ifBlank { BASE_URL }}?s=${searchType.toNormalQuery()}&post_type=wp-manga"
    }

    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
       return null
    }

    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? {
        return null
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? {
        return null
    }

    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? {
        return null
    }

    override fun extractCustomHomeMangaItems(html: String): MutableList<MangaItem> {
        return extractMangaItems(html)
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
       return extractMangaItems(html)
    }

    fun extractMangaItems(html: String): MutableList<MangaItem> {
        val doc: Document = Ksoup.parse(html)
        val items = mutableListOf<MangaItem>()

        // Select all elements with class page-item-detail
        doc.select(".page-item-detail").forEach { el ->
            // Get the thumbnail div
            val thumbDiv = el.selectFirst(".item-thumb") ?: return@forEach


            val imageAnchor = thumbDiv.selectFirst("a") ?: return@forEach
            val imgTag = imageAnchor.selectFirst("img") ?: return@forEach
            val imageUrl = imgTag.attr("data-src").ifEmpty { imgTag.absUrl("src") }

            val titleAnchor = el.selectFirst(".post-title a") ?: return@forEach
            val link = titleAnchor.absUrl("href").trim()
            val title = titleAnchor.text().trim()

            items += MangaItem(
                api = API, // Replace with your constant
                language = LANGUAGE,    // Or other appropriate language
                title = title,
                url = link,
                imageUrl = imageUrl,
                rating = 0,
                chapters = listOf(),
                genres = emptyList()
            )
        }

        return items
    }
    override fun extractMangaList(html: String): List<PopularManga> {
        return extractMangaItems(html).toPopularMangaList().shuffled()
    }

    override suspend fun extractMangaInfo(
        html: String,
        baseUrl: String
    ): MangaInfo {
        val document = Ksoup.parse(html)

        // --- BASIC INFO ---
        val title = document.select("div.post-title h1").text().trim()
        val otherNames = document.select("div.alternative")
            .text()
            .removePrefix("Alternative :")
            .trim()
        val description = document.select("div.description-summary div.summary__content")
            .text()
            .trim()
        val thumbnailUrl = document.selectFirst("div.summary_image img")
            ?.absUrl("src")
            .orEmpty()

        // --- RATINGS ---
        val rating = document.select("span[itemprop=ratingValue]")
            .text()
            .trim()
        val ratingCount = document.select("span[itemprop=ratingCount]")
            .text()
            .filter { it.isDigit() }

        // --- METADATA ---
        fun infoFromTable(label: String) = document
            .select("table.inftable tr:has(td:contains($label)) td:nth-child(2)")
            .text()
            .trim()
        val author = infoFromTable("Pengarang")
        val artist = infoFromTable("Ilustrador")
        val status = document.select("div.summary-heading:contains(Status) + div.summary-content")
            .last()
            ?.text()
            .orEmpty()
        val yearOfProduction = infoFromTable("Ano")

        // --- GENRES ---
        // Matches <div class="genres-content"><a>+18</a>, <a>Adulto</a>…</div>
        val genres = document.select("div.genres-content a")
            .map { it.text().trim() }

        // --- TAGS ---
        val tags = document.select("div.tags a")
            .map { it.text().trim() }

        // --- CHAPTERS ---
        val chapters = try {
            val chapterEls = document
                .select("ul.main.version-chap li.wp-manga-chapter")


            chapterEls.mapNotNull { li ->
                val linkEl = li.selectFirst("a[href]") ?: return@mapNotNull null
                val chapterTitle = linkEl.text().trim()
                val href = linkEl.absUrl("href")
                    .ifEmpty { baseUrl.trimEnd('/') + linkEl.attr("href") }

                // "27 de julho de 2025"
                val dateText = li.selectFirst("span.chapter-release-date i")
                    ?.text()
                    .orEmpty()
                    .trim()
                // TODO(Phase 8 - locale): Portuguese month-name parsing requires a locale-aware
                //   formatter. The original used `DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale("pt", "BR"))`.
                //   kotlinx.datetime's MonthNames only ships an English locale, so we fall back to
                //   today's date (matching the original `getOrDefault(LocalDate.now())` behaviour).
                val date: LocalDate? = parsePtChapterDate(dateText)
                    ?: Clock.System.todayIn(TimeZone.currentSystemDefault())

                // from "Capítulo 62" → "62"
                val number = chapterTitle.substringAfterLast(" ").trim()

                ChapterItem(
                    name   = chapterTitle,
                    date   = date,
                    number = number,
                    url    = href
                )
            }.toMutableList().also {
            }
        } catch (e: Exception) {
            mutableListOf()
        }

        return MangaInfo(
            api             = API,
            language        = LANGUAGE,
            url             = baseUrl,
            title           = title,
            imageUrl        = thumbnailUrl,
            rating          = rating,
            description     = description,
            author          = author,
            genres          = genres,
            status          = status,
            chapters        = chapters
        )
    }

    // TODO(Phase 8 - locale): replace with a locale-aware parser (expect/actual ICU bindings or a
    //   custom MonthNames for Portuguese) so Brazilian Portuguese chapter dates parse accurately.
    //   Returns null for now and lets callers fall back to today.
    private fun parsePtChapterDate(@Suppress("UNUSED_PARAMETER") dateText: String): LocalDate? = null

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val baseUrl = baseUrl.ifBlank { BASE_URL } // change if your BASE_URL differs
        val doc: Document = Ksoup.parse(html, baseUrl)
        val items = mutableListOf<MangaItem>()

        doc.select("#loop-content .page-item-detail").forEach { card ->

            // Link (prefer cover link, fallback title link)
            val linkEl = card.selectFirst(".item-thumb a[href]")
                ?: card.selectFirst(".post-title a[href]")
                ?: return@forEach
            val link = linkEl.absUrl("href").trim()
            if (link.isBlank()) return@forEach

            // Title (prefer title text, fallback to link title attr)
            val title = card.selectFirst(".post-title a")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: linkEl.attr("title").trim()
            if (title.isBlank()) return@forEach

            // Cover image
            val img = card.selectFirst(".item-thumb img")
            val imageUrl = when {
                img == null -> ""
                img.hasAttr("src") -> img.absUrl("src").trim()
                else -> ""
            }.ifBlank {
                // Fallback: parse first URL from srcset
                img?.attr("srcset")
                    ?.split(",")
                    ?.firstOrNull()
                    ?.trim()
                    ?.split(" ")
                    ?.firstOrNull()
                    ?.let { src -> Ksoup.parse("<img src=\"$src\">", baseUrl).selectFirst("img")?.absUrl("src") }
                    .orEmpty()
            }

            // Genres are not present in the search card HTML for this site
            val genres = emptyList<String>()

            items += MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = link,
                imageUrl = imageUrl,
                rating = 0,
                chapters = emptyList(),
                genres = genres,
            )
        }

        return items
    }

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
            return base + refererHeader
        }

    private val refererHeader = "Referer" to "https://flowermanga.net/"

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        val merged = newHeaders + refererHeader

        _cachedHeaders = merged

        dataStore.saveHeadersForApi(API, newHeaders)

        }


    override fun getChapterImages(html: String): List<String> {
        val doc = Ksoup.parse(html)

        // matches your pageListParseSelector
        val selector = "div.page-break, li.blocks-gallery-item, .reading-content .text-left:not(:has(.blocks-gallery-item)) img"
        val elements = doc.select(selector)

        val seen = LinkedHashSet<String>()

        fun pickFromSrcset(srcset: String?): String? {
            if (srcset.isNullOrBlank()) return null
            // choose the candidate with the largest width or highest density
            return srcset.split(",")
                .map { it.trim() }
                .mapNotNull {
                    val parts = it.split("\\s+".toRegex()).filter { p -> p.isNotEmpty() }
                    val url = parts.getOrNull(0) ?: return@mapNotNull null
                    val desc = parts.getOrNull(1) ?: "1x"
                    Pair(url, desc)
                }
                .maxByOrNull { pair ->
                    val d = pair.second
                    when {
                        d.endsWith("w") -> d.dropLast(1).toDoubleOrNull() ?: 0.0
                        d.endsWith("x") -> d.dropLast(1).toDoubleOrNull() ?: 0.0
                        else -> 0.0
                    }
                }?.first
        }

        fun normalize(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            var url = raw.trim().trim('"', '\'')
            if (url.isBlank()) return null
            // skip data URIs
            if (url.startsWith("data:", ignoreCase = true)) return null
            // protocol-relative -> prefer https
            if (url.startsWith("//")) url = "https:$url"
            // trim accidental spaces (your file had leading spaces before URLs)
            url = url.trim()
            // only return full http(s) urls (this avoids returning fragmented relative paths)
            return if (url.startsWith("http://") || url.startsWith("https://")) url else null
        }

        for (el in elements) {
            // if the selector matched an <img> element directly, use el; otherwise find <img> inside
            val imgEl: Element? = if (el.tagName().equals("img", ignoreCase = true)) el else el.selectFirst("img")

            if (imgEl == null) continue

            // try attributes in order of usefulness
            val candidate = when {
                imgEl.hasAttr("data-src") && imgEl.attr("data-src").isNotBlank() -> imgEl.attr("data-src")
                imgEl.hasAttr("data-original") && imgEl.attr("data-original").isNotBlank() -> imgEl.attr("data-original")
                imgEl.hasAttr("data-srcset") && imgEl.attr("data-srcset").isNotBlank() -> pickFromSrcset(imgEl.attr("data-srcset"))
                imgEl.hasAttr("srcset") && imgEl.attr("srcset").isNotBlank() -> pickFromSrcset(imgEl.attr("srcset"))
                imgEl.hasAttr("src") && imgEl.attr("src").isNotBlank() -> imgEl.attr("src")
                imgEl.hasAttr("data-ll-src") && imgEl.attr("data-ll-src").isNotBlank() -> imgEl.attr("data-ll-src")
                else -> null
            }

            normalize(candidate)?.let { seen.add(it) }
        }

        return seen.toList()
    }

    fun imageFromElement(element: Element): String? {
        return when {
            element.hasAttr("data-src") -> element.attr("abs:data-src")
            element.hasAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
            element.hasAttr("srcset") -> element.attr("abs:srcset").getSrcSetImage()
            element.hasAttr("data-cfsrc") -> element.attr("abs:data-cfsrc")
            else -> element.attr("abs:src")
        }
    }
    val URL_REGEX = """^(https?://[^\s/$.?#].[^\s]*)${'$'}""".toRegex()

    fun String.getSrcSetImage(): String? {
        return this.split(" ")
            .filter(URL_REGEX::matches)
            .maxOfOrNull(String::toString)
    }

    // Inlined from `core.util.data_classes.HandelDataClasses.toPopularMangaList` — that helper has
    // not been ported to commonMain yet. The original behaviour just maps MangaItem -> PopularManga.
    private fun List<MangaItem>.toPopularMangaList(): List<PopularManga> = this.map {
        PopularManga(
            api = it.api,
            language = it.language,
            title = it.title,
            url = it.url,
            imageUrl = it.imageUrl,
        )
    }

    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language
}
