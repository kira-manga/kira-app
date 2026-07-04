package me.manga.yamiapk.sources_repositry.en.manhwatop

import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

class ManhwatopParser {


    val parserVersion = 1
    val baseUrlVersion = 1
    val API = "Manhwatop"
    val LANGUAGE = "(EN)"
    val baseUrl = "https://manhwatop.com/"
    val homeUrl = "${baseUrl}wp-admin/admin-ajax.php"
    val popularUrl = "${baseUrl}wp-admin/admin-ajax.php"

    fun normalSearchUrl(q : String): String = "${baseUrl}?s=${q}&post_type=wp-manga"



     fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val document = Jsoup.parse(html)
        val elements = document.select(".page-listing-item .page-item-detail")

        return elements.mapNotNull { el ->
            // ----- ID & Thumb -----
            val thumb = el.selectFirst(".item-thumb")
            val id = thumb?.attr("data-post-id")?.toIntOrNull() ?: return@mapNotNull null

            // ----- Title & Link -----
            val titleLink = el.selectFirst(".item-summary .post-title a") ?: return@mapNotNull null
            val title = titleLink.text().trim()
            val url = titleLink.attr("href").trim()

            // ----- Image URL -----
            val imgEl = thumb.selectFirst("img")
            // use data-src for the real image
            val imageUrl = imgEl?.attr("data-src")?.trim().orEmpty()

            // ----- Rating -----
            // note: now parsing as Double (e.g. "4.2")
            val ratingText = el.selectFirst(".item-summary .total_votes")?.text()?.trim()
            val rating = ratingText?.toDoubleOrNull() ?: 0.0

            // ----- Chapters -----
            val chapterElements = el.select(".list-chapter .chapter-item")
            val chapters = chapterElements.mapNotNull { chapEl ->
                val linkEl = chapEl.selectFirst(".chapter a")
                val postedOnEl = chapEl.selectFirst(".post-on")
                if (linkEl != null && postedOnEl != null) {
                    val chapTitle = linkEl.text().trim()
                    val chapUrl = linkEl.attr("href").trim()
                    val postedOn = postedOnEl.text().trim()
                    ChapterItem(
                        name = chapTitle,
                        number = chapTitle,
                        url = chapUrl,
                        date = parseChapterDate(postedOn)
                    )
                } else null
            }

            MangaItem(
                title = title,
                url = url,
                imageUrl = imageUrl,
                rating = rating.toInt(),
                chapters = chapters,
                api = API,
                language = LANGUAGE,
                genres = listOf()
            )
        }.toMutableList()
    }

     fun extractMangaList(html: String): List<PopularManga> {
        val document = Jsoup.parse(html)
        val elements = document.select(".page-listing-item .page-item-detail")

        return elements.mapNotNull { el ->
            // ----- ID & Thumb -----
            val thumb = el.selectFirst(".item-thumb")

            // ----- Title & Link -----
            val titleLink = el.selectFirst(".item-summary .post-title a") ?: return@mapNotNull null
            val title = titleLink.text().trim()
            val url = titleLink.attr("href").trim()

            // ----- Image URL -----
            val imgEl = thumb?.selectFirst("img")
            // use data-src for the real image
            val imageUrl = imgEl?.attr("data-src")?.trim().orEmpty()


            PopularManga(
                title = title,
                url = url,
                imageUrl = imageUrl,
                api = API,
                language = LANGUAGE,
            )
        }
    }

     suspend fun extractMangaInfo(html: String, url: String): MangaInfo {
        val doc = Jsoup.parse(html)

        // Title
        val title = doc.selectFirst("div.post-title h1")
            ?.text()?.trim()
            ?: ""  // :contentReference[oaicite:0]{index=0}

        // Cover image (using data-src for the high-res URL)
        val imageUrl = doc.selectFirst("div.summary_image img")
            ?.attr("data-src")
            ?: ""  // :contentReference[oaicite:1]{index=1}

        // Rating (score) and rating count (total votes)
        val rating = doc.selectFirst("div.post-total-rating span.score")
            ?.text()
            ?: "0"  // :contentReference[oaicite:2]{index=2}
        val ratingCount = doc.selectFirst("div.post-total-rating span.total_votes")
            ?.text()
            ?: "0"  // :contentReference[oaicite:3]{index=3}

        // Description
        val description = doc.selectFirst("div.summary_content_wrap div.post-content")
            ?.text()?.trim()
            ?: ""  // :contentReference[oaicite:4]{index=4}

        // Author(s)
        val authors = doc.select("div.author-content a")
            .eachText()
            .toMutableList()  // :contentReference[oaicite:5]{index=5}

        // Artist(s)
        val artists = doc.select("div.artist-content a")
            .eachText()
            .toMutableList()  // :contentReference[oaicite:6]{index=6}

        // Genre(s)
        val genres = doc.select("div.genres-content a")
            .eachText()
            .toMutableList()  // :contentReference[oaicite:7]{index=7}

        // Status (e.g. OnGoing, Completed)
        val status = doc.selectFirst("div.summary-content.mg_status")
            ?.text()?.trim()
            ?: "Unknown"  // :contentReference[oaicite:8]{index=8}

        // Chapters (unchanged from your original logic)
        val chapters = doc.select("ul.main.version-chap li.wp-manga-chapter:not(.premium-block)")
            .map { element ->
                val link = element.selectFirst("a")!!
                val chapterNumber = link.text()
                val chapterUrl = link.attr("href")
                val dateElem = element.selectFirst("span.chapter-release-date")
                val isNew = dateElem?.select("span.c-new-tag")?.isNotEmpty() == true
                val dateText = if (isNew) {
                    dateElem.select("img").attr("alt").ifEmpty { "NEW" }
                } else {
                    dateElem?.select("i")?.text() ?: "UNKNOWN"
                }
                val chpNumOnly = chapterNumber.replace(Regex("[^\\d.]"), "")  // removes all non‐digits, yields "245"

                ChapterItem(
                    number        = chpNumOnly.ifBlank { chapterNumber },
                    name          = chapterNumber,
                    url           = chapterUrl,
                    date          = parseChapterDate(dateText) ?: LocalDate.now(),
                    isDownloaded  = false
                )
            }.toMutableList()

        return MangaInfo(
            api                = API,
            language           = LANGUAGE,
            url                = url,
            title              = title,
            imageUrl           = imageUrl,
            rating             = rating,
            ratingCount        = ratingCount,
            description        = description,
            otherNames         = "",  // not present in this HTML
            author             = authors.toString(),
            artist             = artists.toString(),
            genres             = genres,
            tags               = emptyList(),  // not present in this HTML
            yearOfProduction   = "",           // not present in this HTML
            status             = status,
            favoritesCount     = "0",          // bookmark count selector not in snippet
            chapters           = chapters
        )
    }

      fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)

        return doc
            // each “card” is a row with class c-tabs-item__content
            .select("div.row.c-tabs-item__content")                                       // :contentReference[oaicite:0]{index=0}
            .mapNotNull { card ->
                // 1. Cover image: they lazy-load with data-src, fallback to src if needed
                val imgEl = card.selectFirst("div.tab-thumb a img")
                    ?: return@mapNotNull null
                val imageUrl = imgEl.attr("data-src")
                    .takeIf { it.isNotBlank() }
                    ?: imgEl.absUrl("src")                                                 // :contentReference[oaicite:1]{index=1}

                // 2. Title and page URL
                val titleA = card.selectFirst("div.post-title h2.h5 a")
                    ?: return@mapNotNull null
                val title   = titleA.text().trim()
                val pageUrl = titleA.absUrl("href")                                       // :contentReference[oaicite:2]{index=2}

                // 3. Genres (if any): look for the “mg_genres” block under post-content
                val genres = card
                    .select("div.post-content_item.mg_genres .summary-content a")
                    .map { it.text().trim() }                                              // :contentReference[oaicite:3]{index=3}

                // 4. Rating: count full-star and half-star icons
                val rating = card.selectFirst("div.meta-item.rating")
                    ?.select("i.ion-ios-star, i.ion-ios-star-half")
                    ?.size
                    ?: 0                                                                  // :contentReference[oaicite:4]{index=4}

                MangaItem(
                    api      = API,
                    language = LANGUAGE,
                    title    = title,
                    url      = pageUrl,
                    imageUrl = imageUrl,
                    rating   = rating,
                    chapters = emptyList(),  // search results do not include chapters
                    genres   = genres
                )
            }
    }

     fun getChapterImages(html: String): List<String> {
        val doc: Document = Jsoup.parse(html)
        // select all the chapter images (they’re lazy-loaded with data-src)
        return doc.select("div.read-container img.wp-manga-chapter-img")
            .map { img ->
                // prefer data-src if present, otherwise fall back to src
                val urlAttr = if (img.hasAttr("data-src")) "data-src" else "src"
                img.absUrl(urlAttr)
            }
    }

    private fun parseChapterDate(dateText: String): LocalDate? {
        val now = LocalDate.now()
        val txt = dateText.trim().lowercase(Locale.getDefault())

        // 1) "NEW" → today
        if (txt == "new") return now

        // 2) Relative times: "5 days ago", "2 weeks ago", "3 hours ago", etc.
        val relRegex = """(\d+)\s*(second|minute|hour|day|week|month|year)s?\s*ago""".toRegex()
        relRegex.find(txt)?.let { match ->
            val (amountStr, unit) = match.destructured
            val amount = amountStr.toLong()
            return when (unit) {
                "second", "minute", "hour" -> now
                "day"    -> now.minusDays(amount)
                "week"   -> now.minusWeeks(amount)
                "month"  -> now.minusMonths(amount)
                "year"   -> now.minusYears(amount)
                else     -> now
            }
        }

        // 3) Try absolute date formats
        val formatters = listOf(
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),  // e.g. March 5, 2025
            DateTimeFormatter.ofPattern("MMM d, yyyy",  Locale.ENGLISH),  // e.g. Mar 5, 2025
            DateTimeFormatter.ofPattern("yyyy-MM-dd",     Locale.ENGLISH),  // e.g. 2025-03-05
            DateTimeFormatter.ofPattern("d MMM yyyy",     Locale.ENGLISH)   // e.g. 5 Mar 2025
        )
        for (fmt in formatters) {
            try {
                return LocalDate.parse(dateText, fmt)
            } catch (e: DateTimeParseException) {
                // try next
            }
        }

        // If nothing matched, give up
        return null
    }


     val blackListGenres: Set<String>
        get() = setOf(
//            "Smut",
//            "Yaoi",
//            "Doujinshi",
//            "Lolicon",
//            "Yaoi",
//            "Adult",
//            "Yuri",
//            "Soft Yuri",
//            "Soft Yaoi",
//            "Yaoi",
//            "Shoujo Ai",
//            "Shounen Ai",
        )


}