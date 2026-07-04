package me.manga.yamiapk.dex

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup   // <-- Added Jsoup import

data class MangaSourceConfig(
    val name: String,
    val baseUrl: String,
    val chaptersPath: String,
    val mangaPath: String,
    val headers: Map<String, String>
)

object PluginDataParser {

    // -------------------------------
    // Parse SINGLE item JSON → object
    // -------------------------------
    fun parseFromJson(json: String): MangaSourceConfig {
        val obj = JSONObject(json)
        return parseItem(obj)
    }

    // -------------------------------
    // NEW: Parse HTML using Jsoup
    // -------------------------------
    fun parseFromHtml(html: String): MangaSourceConfig {

        val doc = Jsoup.parse(html)

        val name = doc.selectFirst("meta[name=source-name]")?.attr("content") ?: "Unknown"
        val baseUrl = doc.selectFirst("meta[name=base-url]")?.attr("content") ?: ""
        val chaptersPath = doc.selectFirst("meta[name=chapters-path]")?.attr("content") ?: ""
        val mangaPath = doc.selectFirst("meta[name=manga-path]")?.attr("content") ?: ""

        // optional headers
        val headers = mutableMapOf<String, String>()
        doc.select("meta[name^=header-]").forEach { tag ->
            val key = tag.attr("name").removePrefix("header-")
            headers[key] = tag.attr("content")
        }

        return MangaSourceConfig(
            name, baseUrl, chaptersPath, mangaPath, headers
        )
    }

    // -------------------------------
    fun parseList(jsonList: String): List<MangaSourceConfig> {
        val arr = JSONArray(jsonList)
        val list = mutableListOf<MangaSourceConfig>()

        for (i in 0 until arr.length()) {
            val itemObj = arr.getJSONObject(i)
            list.add(parseItem(itemObj))
        }

        return list
    }

    private fun parseItem(obj: JSONObject): MangaSourceConfig {

        val headersMap = mutableMapOf<String, String>()
        if (obj.has("headers")) {
            val headersJson = obj.getJSONObject("headers")
            for (key in headersJson.keys()) {
                headersMap[key] = headersJson.getString(key)
            }
        }

        return MangaSourceConfig(
            name = obj.optString("name", "Unknown"),
            baseUrl = obj.optString("baseUrl", ""),
            chaptersPath = obj.optString("chaptersPath", ""),
            mangaPath = obj.optString("mangaPath", ""),
            headers = headersMap
        )
    }
}
