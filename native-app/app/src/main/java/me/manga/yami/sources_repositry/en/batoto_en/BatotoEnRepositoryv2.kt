package me.manga.yamiapk.sources_repositry.en.batoto_en

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
import me.manga.yamiapk.sources_repositry.common.NormalSites
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.sources_repositry.dropTrailingSlash
import okhttp3.FormBody
import org.jsoup.Jsoup
import java.time.LocalDate
import java.util.Locale
import java.util.regex.Pattern
import javax.inject.Inject

class BatotoEnRepositoryv2 @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): NormalSites(dataStore,api,sourcesRepository,) {
    override val mangaSource: MangaSource
        get() = MangaSource.BATOTO
    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language
    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0
    override val homeUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}browse?langs=en&sort=update.za" }
    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}browse?langs=ar,en&chapters=200&sort=views_d.za" }


    override fun handelLoadMoreUrl(page: Int): String = "${baseUrl.ifBlank { BASE_URL }}browse?langs=en&sort=update.za&page=$page"



    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal  ->  "${baseUrl.ifBlank { BASE_URL }}search?word=${searchType.query}"
            is SearchType.GENRES  -> "${baseUrl.ifBlank { BASE_URL }}browse?genres=${searchType.genres}&langs=ar,en&sort=views_m.za"
            is SearchType.SORT    -> "${baseUrl.ifBlank { BASE_URL }}browse?genres=${searchType.genres}&langs=ar,en&sort=${searchType.sortType}"
        }

    override val sortTypes: Set<String>
        get() = setOf(
            "views_m.za",
            "title.az",
            "update.za",
            "create.za"
        )
    override val allGenres: Set<String>
        get() = setOf(
            "All",
            "Artbook",
            "Cartoon",
            "Comic",
            "Doujinshi",
            "Imageset",
            "Manga",
            "Manhua",
            "Manhwa",
            "Webtoon",
            "Western",
            "4-Koma",
            "Oneshot",
            "Shoujo(G)",
            "Shounen(B)",
            "Josei(W)",
            "Seinen(M)",
            "Yuri(GL)",
            "Yaoi(BL)",
            "Bara(ML)",
            "Kodomo(Kid)",
            "Non-human",
            "Gore",
            "Bloody",
            "Violence",
            "Action",
            "Adaptation",
            "Adventure",
            "Age_Gap",
            "Aliens",
            "Animals",
            "Anthology",
            "Beasts",
            "Bodyswap",
            "Boys",
            "Cars",
            "Cheating/Infidelity",
            "Childhood_Friends",
            "College_life",
            "Comedy",
            "Contest_winning",
            "Cooking",
            "Crime",
            "Crossdressing",
            "Delinquents",
            "Dementia",
            "Demons",
            "Drama",
            "Dungeons",
            "emperor_daughte",
            "Fantasy",
            "Fan-Colored",
            "Full_Color",
            "Game",
            "Gender_Bender",
            "Genderswap",
            "Ghosts",
            "Girls",
            "Gyaru",
            "Harem",
            "Harlequin",
            "Historical",
            "Horror",
            "Incest",
            "Isekai",
            "Kids",
            "Magic",
            "Magical_Girls",
            "Martial_Arts",
            "Mecha",
            "Medical",
            "Military",
            "Monster_Girls",
            "Monsters",
            "Music",
            "Mystery",
            "netorare",
            "Ninja",
            "gore",
            "Omegaverse",
            "Parody",
            "Philosophical",
            "Police",
            "Post-Apocalyptic",
            "Psychological",
            "Regression",
            "Reincarnation",
            "Reverse Harem",
            "Revenge",
            "Reverse_Isekai",
            "Romance",
            "Royal_family",
            "Royalty",
            "Samurai",
            "School_Life",
            "Sci-Fi",
            "Showbiz",
            "slice_of_life",
            "Space",
            "Sports",
            "Super_Power",
            "Superhero",
            "Supernatural",
            "Survival",
            "Thriller",
            "Time_Travel",
            "Tower_Climbing",
            "Traditional Games",
            "Tragedy",
            "Transmigration",
            "Vampires",
            "Villainess",
            "Video_Games",
            "Virtual_Reality",
            "Wuxia",
            "Xianxia",
            "Xuanhuan",
            "Yakuzas",
            "Zombies",
        )
    override val blackListGenres: Set<String>
        get() = setOf(
            "Adult",
            "Yaoi(BL)",
            "Smut",
//        "Mature",
            "Ecchi",
            "Hentai",
            "Yuri(GL)",
            "Fetish",
            "SM/BDSM/SUB-DOM",
            "Incest",
            "Omegaverse",
            "Netorare/NTR",
            "Bara(ML)",
//        "Shoujo(G)",
            "Yaoi(BL)",
            "Shounen ai",
            "Gender Bender"
        )

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
        return null
    }


    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? {
        return null
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? {
        return null
    }

    override fun sortFormBody(searchType: SearchType.SORT): FormBody? {
        return null
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val items = mutableListOf<MangaItem>()
        val doc = Jsoup.parse(html)

        // 1) Grab the container by ID
        val seriesList = doc.getElementById("series-list") ?: return items

        // 2) Each .col.item is one manga entry
        for (entry in seriesList.select(".col.item")) {
            // 2a) Manga cover link + series URL
            val coverLink = entry.selectFirst("a.item-cover") ?: continue
            val url       = coverLink.attr("href").trim()

            // 2b) Title
            val titleElem = entry.selectFirst("a.item-title") ?: continue
            val title     = titleElem.text().trim()

            // 2c) Thumbnail image
            val imageUrl = coverLink.selectFirst("img")?.attr("src")?.trim() ?: continue

            // 2d) Chapters container: .item-volch > a.visited
            val volCh = entry.selectFirst(".item-volch") ?: continue
            val chapLink = volCh.selectFirst("a.visited") ?: continue
            val chapName = chapLink.text().trim()
            val chapUrl  = chapLink.attr("href").trim()

            // 2e) Date text is inside the <i> tag in the same .item-volch
            val dateTxt = volCh.selectFirst("i")?.text()?.trim() ?: ""
            // You'll need a helper to turn things like "7 mins ago", "1 hour ago", etc. into LocalDate
            val date    = parseChapterDate(dateTxt) ?: LocalDate.now()
            val genresContainer = entry.selectFirst(".item-genre")
            val genres = genresContainer
                ?.select("span, u, b")
                ?.map { it.text().trim() }
                ?: emptyList()
            if (genres.hasBlacklistedGenre()) continue

            val chapter = ChapterItem(
                number = chapName,
                name   = chapName,
                url    = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$chapUrl",
                date   = date
            )


            items += MangaItem(
                api       = API,
                language  = LANGUAGE,
                title     = title,
                url       = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                imageUrl  = imageUrl,
                rating    = 0,
                chapters  = listOf(chapter),
                genres    = genres
            )
        }

        return items
    }

    override fun extractMangaList(html: String): List<PopularManga>  {
        val items = mutableListOf<PopularManga>()
        val doc = Jsoup.parse(html)

        // 1) Grab the container by ID
        val seriesList = doc.getElementById("series-list") ?: return items

        // 2) Each .col.item is one manga entry
        for (entry in seriesList.select(".col.item")) {
            // 2a) Manga cover link + series URL
            val coverLink = entry.selectFirst("a.item-cover") ?: continue
            val url       = coverLink.attr("href").trim()
            // 2b) Title
            val titleElem = entry.selectFirst("a.item-title") ?: continue
            val title     = titleElem.text().trim()

            // 2c) Thumbnail image
            val imageUrl = coverLink.selectFirst("img")?.attr("src")?.trim() ?: continue


            // You'll need a helper to turn things like "7 mins ago", "1 hour ago", etc. into LocalDate
            val genresContainer = entry.selectFirst(".item-genre")
            val genres = genresContainer
                ?.select("span, u, b")
                ?.map { it.text().trim() }
                ?: emptyList()
            if (genres.hasBlacklistedGenre()) continue



            items += PopularManga(
                api       = API,
                language  = LANGUAGE,
                title     = title,
                url       = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                imageUrl  = imageUrl,

                )
        }

        return items
    }

    override suspend fun extractMangaInfo(
        html: String,
        url: String
    ): MangaInfo {
        val doc = Jsoup.parse(html, url)
        val infoEl = doc.selectFirst("div#mainer div.container-fluid")
            ?: error("Series info container not found at $url")

        // Title handling
        val rawTitle = infoEl.selectFirst("h3, h1")?.text().orEmpty()
        val title =  rawTitle.trim()

        // Description builder
        val description = buildString {
            append(infoEl.selectFirst("div.limit-html")?.text().orEmpty())

            infoEl.selectFirst(".episode-list > .alert-warning")?.let {
                append("\n\n" + it.text().trim())
            }

            infoEl.selectFirst("h5:containsOwn(Extra Info:) + div")?.let {
                append("\n\nExtra Info:\n" + it.wholeText().trim())
            }

            doc.selectFirst("div.pb-2.alias-set.line-b-f")
                ?.takeIf { it.hasText() }
                ?.let {
                    append("\n\nAlternative Titles:\n")
                    it.text().split('/')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .forEach { alt -> append("• $alt\n") }
                }
        }.trim()

        // Basic attributes
        val author = infoEl.selectFirst("div.attr-item:contains(author) span")?.text().orEmpty()
        val artist = infoEl.selectFirst("div.attr-item:contains(artist) span")?.text().orEmpty()
        val workStatus = infoEl.selectFirst("div.attr-item:contains(original work) span")?.text()
        val uploadStatus = infoEl.selectFirst("div.attr-item:contains(upload status) span")?.text()
        val status = "$workStatus"
        val genres = infoEl.select(".attr-item b:contains(genres) + span ").joinToString { it.text() }
        val genresList = genres
            .split(",")
            .map { it.trim() }

        // Thumbnail URL
        val imageUrl = doc.selectFirst("div.attr-cover img")
            ?.absUrl("src").orEmpty()

        // Chapters parsing
        val chaptersElements = doc.select("div.mt-4.episode-list div.item")

        val chapters = chaptersElements.map { item ->
            val link = item.selectFirst("a.chapt")
            val title = link?.text() ?: ""
            val chpNumOnly = title.replace(Regex("[^\\d.]"), "")  // removes all non‐digits, yields "245"

            val url = link?.attr("href") ?: ""

            val dateText = item.select("div.extra i").last()?.text() ?: ""
            val parsedDate = parseChapterDate(dateText) ?: LocalDate.now()

            ChapterItem(
                number = title,
                name = title,
                url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                date = parsedDate,
                isDownloaded = false,
                isBookmarked = false,
                chaptersImages = emptyList()
            )
        }



        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = url,
            title = title,
            imageUrl = imageUrl,
            rating = "",
            ratingCount = "",
            description = description,
            otherNames = "",
            author = author,
            artist = artist,
            genres = genresList,
            tags = emptyList(),
            yearOfProduction = "",
            status = status.toString(),
            favoritesCount = "",
            chapters = chapters.toMutableList()
        )
    }





    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val items = mutableListOf<MangaItem>()
        val doc = Jsoup.parse(html)

        // 1) Grab the container by ID
        val seriesList = doc.getElementById("series-list") ?: return items

        // 2) Each .col.item is one manga entry
        for (entry in seriesList.select(".col.item")) {
            // 2a) Manga cover link + series URL
            val coverLink = entry.selectFirst("a.item-cover") ?: continue
            val url       = coverLink.attr("href").trim()

            // 2b) Title
            val titleElem = entry.selectFirst("a.item-title") ?: continue
            val title     = titleElem.text().trim()

            // 2c) Thumbnail image
            val imageUrl = coverLink.selectFirst("img")?.attr("src")?.trim() ?: continue

            // 2d) Chapters container: .item-volch > a.visited
            val volCh = entry.selectFirst(".item-volch") ?: continue
            val chapLink = volCh.selectFirst("a.visited") ?: continue
            val chapName = chapLink.text().trim()
            val chapUrl  = chapLink.attr("href").trim()

            // 2e) Date text is inside the <i> tag in the same .item-volch
            val dateTxt = volCh.selectFirst("i")?.text()?.trim() ?: ""
            // You'll need a helper to turn things like "7 mins ago", "1 hour ago", etc. into LocalDate
            val date    = parseChapterDate(dateTxt) ?: LocalDate.now()
            val genresContainer = entry.selectFirst(".item-genre")
            val genres = genresContainer
                ?.select("span, u, b")
                ?.map { it.text().trim() }
                ?: emptyList()
            if (genres.hasBlacklistedGenre()) continue

            val chapter = ChapterItem(
                number = chapName,
                name   = chapName,
                url    = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$chapUrl",
                date   = date
            )


            items += MangaItem(
                api       = API,
                language  = LANGUAGE,
                title     = title,
                url       = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                imageUrl  = imageUrl,
                rating    = 0,
                chapters  = listOf(chapter),
                genres    = genres
            )
        }

        return items.filter {
            !it.genres.hasBlacklistedGenre()
        }
    }




    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()




    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

    }

    override fun getChapterImages(html: String): List<String> {

        val doc = Jsoup.parse(html)

        // 1) Try real <img> tags first
        val imgs = doc.select("img.wp-manga-chapter-img")
            .mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }


        // 2) Fallback: extract the JS array `const imgHttps = [ ... ]`
        val jsArrayPattern = Pattern.compile(
            """const\s+imgHttps\s*=\s*\[\s*(.*?)\s*];""",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        )
        val matcher = jsArrayPattern.matcher(html)
        if (!matcher.find()) return emptyList()

        // Group 1 is everything between [ and ]
        val arrayBody = matcher.group(1)

        // Split on commas that separate entries, trim quotes/spaces

        return arrayBody
            ?.split(Regex(""",(?=(?:[^"']*"[^"']*")*[^"']*$)""")) // split on commas outside quotes
            ?.map { it.trim().trim('"', '\'') }
            ?.filter(String::isNotBlank) ?: listOf()

    }

//    fun getSearchResults(html: String): List<MangaItem> {
//        val items = mutableListOf<MangaItem>()
//        val doc = Jsoup.parse(html)
//
//        // 1) Grab the container by ID
//        val seriesList = doc.getElementById("series-list") ?: return items
//
//        // 2) Each .col.item is one manga entry
//        for (entry in seriesList.select(".col.item")) {
//            // 2a) Manga cover link + series URL
//            val coverLink = entry.selectFirst("a.item-cover") ?: continue
//            val url       = coverLink.attr("href").trim()
//
//            // 2b) Title
//            val titleElem = entry.selectFirst("a.item-title") ?: continue
//            val title     = titleElem.text().trim()
//
//            // 2c) Thumbnail image
//            val imageUrl = coverLink.selectFirst("img")?.attr("src")?.trim() ?: continue
//
//            // 2d) Chapters container: .item-volch > a.visited
//            val volCh = entry.selectFirst(".item-volch") ?: continue
//            val chapLink = volCh.selectFirst("a.visited") ?: continue
//            val chapName = chapLink.text().trim()
//            val chapUrl  = chapLink.attr("href").trim()
//
//            // 2e) Date text is inside the <i> tag in the same .item-volch
//            val dateTxt = volCh.selectFirst("i")?.text()?.trim() ?: ""
//            // You'll need a helper to turn things like "7 mins ago", "1 hour ago", etc. into LocalDate
//            val date    = parseChapterDate(dateTxt) ?: LocalDate.now()
//            val genresContainer = entry.selectFirst(".item-genre")
//            val genres = genresContainer
//                ?.select("span, u, b")
//                ?.map { it.text().trim() }
//                ?: emptyList()
//            if (genres.hasBlacklistedGenre()) continue
//
//            val chapter = ChapterItem(
//                number = chapName,
//                name   = chapName,
//                url    = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$chapUrl",
//                date   = date
//            )
//
//
//            items += MangaItem(
//                api       = API,
//                language  = LANGUAGE,
//                title     = title,
//                url       = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
//                imageUrl  = imageUrl,
//                rating    = 0,
//                chapters  = listOf(chapter),
//                genres    = genres
//            )
//        }
//
//        return items.filter {
//            !it.genres.hasBlacklistedGenre()
//        }
//    }


    fun parseChapterDate(text: String): LocalDate? {
        // Normalize and trim
        val normalized = text.trim().lowercase(Locale.getDefault())
        // Regex: capture an integer + unit (min(s), hour(s), day(s)) + "ago"
        val regex = """^(\d+)\s*(min(?:s)?|hour(?:s)?|day(?:s)?)\s*ago$""".toRegex()
        val match = regex.find(normalized) ?: return null

        val (rawNum, rawUnit) = match.destructured
        val number = rawNum.toLongOrNull() ?: return null

        // Compute how many days back
        val daysBack = when {
            rawUnit.startsWith("day") -> number
            rawUnit.startsWith("hour") -> number / 24  // floor hours into days
            rawUnit.startsWith("min") -> 0             // all minutes → same day
            else -> 0
        }

        return LocalDate.now().minusDays(daysBack)
    }

}