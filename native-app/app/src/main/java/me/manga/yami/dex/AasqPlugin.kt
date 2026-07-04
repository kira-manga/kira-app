package me.manga.yamiapk.dex


import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------
// Generic plugin models
// ---------------------------

data class PluginConfig(
    val name: String,           // "AASQ"
    val apiId: String,          // some ID you use in app (e.g. "aasq")
    val language: String,       // "ar"
    val baseUrl: String         // e.g. "https://example.com/"
)

data class PluginRequest(
    val url: String,
    val method: String,                 // "GET" or "POST"
    val headers: Map<String, String>,
    val body: String?,                  // for POST requests
    val contentType: String?            // e.g. "application/x-www-form-urlencoded"
)

data class PluginChapter(
    val name: String,
    val number: String,
    val url: String,
    val dateIso: String?                // ISO-8601 "yyyy-MM-dd" or null
)

data class PluginMangaItem(
    val title: String,
    val url: String,
    val imageUrl: String,
    val rating: Int,
    val chapters: List<PluginChapter>,
    val genres: List<String>
)

data class PluginMangaInfo(
    val url: String,
    val title: String,
    val imageUrl: String,
    val rating: String,
    val ratingCount: String,
    val description: String,
    val otherNames: String,
    val author: String,
    val artist: String,
    val genres: List<String>,
    val tags: List<String>,
    val yearOfProduction: String,
    val status: String,
    val favoritesCount: String
)

// ---------------------------
// AASQ plugin implementation
// ---------------------------

/**
 * All parsing logic + URLs + strings for AASQ live here.
 * This file is pure JVM (no Android imports), so you can DEX it and load via DexClassLoader.
 */
object AasqPlugin {

    // You can change these any time and rebuild the DEX
    private const val BASE_URL = "https://aasq.example.com/"      // put real base
    private const val API_ID   = "aasq"
    private const val LANG     = "ar"

    // --------- Basic config ---------
    fun getConfig(): PluginConfig = PluginConfig(
        name = "AASQ",
        apiId = API_ID,
        language = LANG,
        baseUrl = BASE_URL
    )

    // --------- URL builders ---------

    /**
     * Home list (latest) – page 1,2,3...
     * ex: https://site/manga/page/1/?m_orderby=latest
     */
    fun homeUrl(page: Int): String {
        val p = if (page < 1) 1 else page
        return "${BASE_URL}manga/page/$p/?m_orderby=latest"
    }

    /**
     * Popular (you can customize later if site has special URL)
     */
    fun popularUrl(page: Int): String {
        // if popular has different path, change it here
        return homeUrl(page)
    }

    /**
     * Search URL – your repo used: BASE/wp-admin/admin-ajax.php
     */
    fun searchUrl(): String = "${BASE_URL}wp-admin/admin-ajax.php"

    /**
     * Ajax chapter list URL: e.g. "$mangaUrl/ajax/chapters"
     */
    fun chaptersUrl(mangaPageUrl: String): String {
        return if (mangaPageUrl.endsWith("/")) {
            "${mangaPageUrl}ajax/chapters"
        } else {
            "$mangaPageUrl/ajax/chapters"
        }
    }

    // --------- Requests (only strings — no OkHttp / Android) ---------

    /**
     * Build search POST request body + headers for AASQ (Madara style)
     */
    fun buildSearchRequest(query: String, page: Int): PluginRequest {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = searchUrl()

        // You can tweak posts_per_page, template, etc.
        val body = buildString {
            append("vars[s]=").append(encodedQuery)
            append("&action=madara_load_more")
            append("&vars[posts_per_page]=20")
            append("&template=madara-core/content/content-search")
        }

        val headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to BASE_URL
        )

        return PluginRequest(
            url = url,
            method = "POST",
            headers = headers,
            body = body,
            contentType = "application/x-www-form-urlencoded; charset=UTF-8"
        )
    }

    /**
     * Build chapter list request. Your repo used a POST with "manga_get_chapters".
     * If your site needs POST instead of GET, you can change this to POST easily.
     */
    fun buildChaptersRequest(mangaPageUrl: String): PluginRequest {
        val url = chaptersUrl(mangaPageUrl)

        val body = "action=manga_get_chapters"

        val headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to mangaPageUrl
        )

        return PluginRequest(
            url = url,
            method = "POST",
            headers = headers,
            body = body,
            contentType = "application/x-www-form-urlencoded; charset=UTF-8"
        )
    }

    // --------- Parsing: chapter images ---------

    /**
     * Parse chapter images from chapter HTML
     * selector: img.wp-manga-chapter-img
     */
    fun getChapterImages(html: String): List<String> {
        val doc = Jsoup.parse(html)
        return doc.select("img.wp-manga-chapter-img")
            .mapNotNull { img ->
                val abs = img.absUrl("src").takeIf { it.isNotBlank() }
                if (abs != null) abs else img.attr("src").trim().takeIf { it.isNotBlank() }
            }
    }

    // --------- Parsing: chapters list ---------

    /**
     * Parse chapter list from the "ajax/chapters" HTML snippet.
     * Original selector: ul.main.version-chap.no-volumn li.wp-manga-chapter
     */
    fun parseChapters(html: String): List<PluginChapter> {
        val document = Jsoup.parse("<html><body>$html</body></html>")
        val chapterElements = document.select("ul.main.version-chap.no-volumn li.wp-manga-chapter")

        return chapterElements.mapNotNull { element ->
            try {
                val anchor = element.selectFirst("a") ?: return@mapNotNull null
                val href = anchor.attr("href").trim()
                val url = if (href.startsWith("http")) href else BASE_URL + href.trimStart('/')
                val fullTitle = anchor.text().trim()

                val numberMatch = Regex("""\d+(\.\d+)?""").findAll(fullTitle).lastOrNull()
                val chapterNumber = numberMatch?.value ?: ""

                val dateText = element
                    .selectFirst(".chapter-release-date .timediff i")
                    ?.text()
                    ?.trim()
                    ?: ""

                val dateIso = parseArabicDateToIso(dateText)

                PluginChapter(
                    name = fullTitle,
                    number = chapterNumber,
                    url = url,
                    dateIso = dateIso
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    // --------- Parsing: home / latest list ---------

    /**
     * Extract items for home page.
     * Your repo used: .page-item-detail.manga, title ".item-thumb a", image ".item-thumb img"
     */
    fun extractHomeMangaItems(html: String): List<PluginMangaItem> {
        val mangaList = mutableListOf<PluginMangaItem>()
        val doc: Document = Jsoup.parse(html)
        val mangaElements = doc.select(".page-item-detail.manga")

        for (element in mangaElements) {
            val titleElement: Element? = element.selectFirst(".item-thumb a")
            val imageElement: Element? = element.selectFirst(".item-thumb img")
            val ratingElement: Element? = element.selectFirst(".post-total-rating")
            val chapterElements = element.select(".list-chapter .chapter-item")

            if (titleElement != null && imageElement != null) {
                val title = titleElement.attr("title").trim()
                val url = titleElement.attr("href")
                val imageUrl = imageElement.attr("src")
                val rating = ratingElement?.select("i.rating_current")?.size ?: 0

                val chapters = chapterElements.mapNotNull { chapter ->
                    val chapterLink = chapter.selectFirst("a")
                    val chapterNum = chapterLink?.text()?.trim() ?: "Unknown"
                    val chapterUrl = chapterLink?.attr("href") ?: ""
                    val dateText = chapter.selectFirst(".post-on")?.text()?.trim()
                    val dateStr = if (!dateText.isNullOrEmpty()) dateText else "NEW"
                    val clean = cleanDateString(dateStr)
                    val iso = parseArabicDateToIso(clean)

                    PluginChapter(
                        number = chapterNum,
                        name = chapterNum,
                        url = chapterUrl,
                        dateIso = iso
                    )
                }

                mangaList.add(
                    PluginMangaItem(
                        title = title,
                        url = url,
                        imageUrl = imageUrl,
                        rating = rating,
                        chapters = chapters,
                        genres = emptyList()
                    )
                )
            }
        }
        return mangaList
    }

    // --------- Parsing: search results ---------

    /**
     * Search cards parser (your repo used selector "div.row.c-tabs-item__content")
     */
    fun getSearchResults(html: String): List<PluginMangaItem> {
        val doc = Jsoup.parse(html)

        return doc.select("div.row.c-tabs-item__content").mapNotNull { card ->
            val imgEl = card.selectFirst("div.tab-thumb a img") ?: return@mapNotNull null
            val imageUrl = imgEl.absUrl("src").ifBlank { imgEl.attr("src") }

            val titleA = card.selectFirst("div.tab-summary .post-title a") ?: return@mapNotNull null
            val title = titleA.text().trim()
            val pageUrl = titleA.absUrl("href").ifBlank { titleA.attr("href") }

            PluginMangaItem(
                title = title,
                url = pageUrl,
                imageUrl = imageUrl,
                rating = 0,
                chapters = emptyList(),
                genres = emptyList()
            )
        }
    }

    // --------- Parsing: manga info page ---------

    fun extractMangaInfo(html: String, url: String): PluginMangaInfo {
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("div.post-title h1")?.text()?.trim() ?: ""
        val otherNames = doc
            .select("div.summary-heading:contains(أسماء أخرى) + div.summary-content")
            ?.text()
            ?.trim() ?: ""
        val imageUrl = doc.selectFirst("div.summary_image img")?.attr("src") ?: ""

        val rating = doc.selectFirst("span#averagerate")?.text()?.trim() ?: "0"
        val ratingCount = "0" // not available

        val description = doc
            .select("meta[name=description]")
            ?.attr("content")
            ?.trim() ?: ""

        val author = doc
            .select("div.summary-heading:contains(الكاتب) + div.summary-content a")
            .eachText()
            .joinToString(", ")

        val artist = doc
            .select("div.summary-heading:contains(الرسام) + div.summary-content a")
            .eachText()
            .joinToString(", ")

        val genres = doc
            .select("div.summary-heading:contains(التصنيفات) + div.summary-content a")
            .map { it.text().trim() }

        val tags = doc.select("a.tag-cloud-link").map { it.text().trim() }

        val yearOfProduction = "N/A"
        val favoritesCount = "0"
        val status = "Unknown"

        return PluginMangaInfo(
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
            favoritesCount = favoritesCount
        )
    }

    // --------- Date helpers (Arabic) ---------

    fun cleanDateString(raw: String): String {
        return raw.replace(Regex("\\s+\\d+$"), "").trim()
    }

    /**
     * Returns ISO date string "yyyy-MM-dd" or null.
     * Based on your parseArabicDateToLocalDate logic.
     */
    fun parseArabicDateToIso(input: String): String? {
        val zone = ZoneId.of("Africa/Cairo")
        val nowZdt = ZonedDateTime.now(zone)
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // Relative: "منذ 3 أيام", "منذ ساعة واحدة"...
        if (trimmed.startsWith("منذ")) {
            val regex = Regex("""منذ\s+(\d+)\s+([^\s]+)""")
            val match = regex.find(trimmed)
            val adjusted = if (match != null) {
                val value = match.groupValues[1].toLongOrNull() ?: 0L
                val unitWord = match.groupValues[2]
                when {
                    unitWord.contains("ساعة") || unitWord.contains("ساعت") ->
                        nowZdt.minusHours(value)
                    unitWord.contains("دقيقة") ->
                        nowZdt.minusMinutes(value)
                    unitWord.contains("يوم") ->
                        nowZdt.minusDays(value)
                    unitWord.contains("أسبوع") ->
                        nowZdt.minusWeeks(value)
                    unitWord.contains("شهر") ->
                        nowZdt.minusMonths(value)
                    unitWord.contains("سنة") || unitWord.contains("سنوات") ->
                        nowZdt.minusYears(value)
                    else -> nowZdt
                }
            } else {
                nowZdt
            }
            return adjusted.toLocalDate().toString()
        }

        val norm = trimmed
            .replace('،', ' ')
            .replace(',', ' ')
            .replace("\\s+".toRegex(), " ")
            .trim()

        try {
            val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ar"))
            val date = LocalDate.parse(norm, formatter)
            return date.toString()
        } catch (_: Exception) {
            val regex = Regex("""(\d{1,2})\s+([^\s]+)\s+(\d{4})""")
            val match = regex.find(norm)
            if (match != null) {
                val day = match.groupValues[1].toInt()
                val monthName = match.groupValues[2]
                val year = match.groupValues[3].toInt()
                val monthMap = mapOf(
                    "يناير" to 1,
                    "فبراير" to 2,
                    "مارس" to 3,
                    "أبريل" to 4, "ابريل" to 4,
                    "مايو" to 5,
                    "يونيو" to 6,
                    "يوليو" to 7,
                    "أغسطس" to 8, "اغسطس" to 8,
                    "سبتمبر" to 9,
                    "أكتوبر" to 10, "اكتوبر" to 10,
                    "نوفمبر" to 11,
                    "ديسمبر" to 12
                )
                val month = monthMap[monthName]
                if (month != null) {
                    return LocalDate.of(year, month, day).toString()
                }
            }
        }
        return null
    }
}
