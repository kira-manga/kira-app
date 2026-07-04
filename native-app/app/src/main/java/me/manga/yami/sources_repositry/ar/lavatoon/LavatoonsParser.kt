package me.manga.yamiapk.sources_repositry.ar.lavatoon

import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

class LavatoonsParser {



        val parserVersion = 1
        val baseUrlVersion = 1
        val API = "Lavatoons"
        val LANGUAGE = "(AR)"
        val baseUrl = "https://lavatoons.com"
        val popularUrl = "https://lavatoons.com"

}