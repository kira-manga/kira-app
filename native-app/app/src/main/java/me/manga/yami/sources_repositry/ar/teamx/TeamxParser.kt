package me.manga.yamiapk.sources_repositry.ar.teamx

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.sources_repositry.data.MangaSource
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

class TeamxParser {

        val parserVersion = 1
        val baseUrlVersion = 1
        val API = "Team X"
        val LANGUAGE = "(AR)"
        val baseUrl = "https://olympustaff.com/"
        val popularUrl = "https://olympustaff.com/"

}