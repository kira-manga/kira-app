package me.manga.kira.navigation.push

/**
 * Pure parser turning a push-notification / in-app-message data payload (`Map<String, String>`)
 * into a typed [PushDestination], or `null` when the payload carries no valid deep link (unknown
 * screen, missing required key, blank value). A `null` result is the safe fallback: the caller does
 * nothing and the app simply opens at its normal start destination.
 *
 * ### Payload contract
 * The FCM message `data` block (or FIAM action-URL query, later) uses these keys:
 * | key             | meaning                        | required for            |
 * |-----------------|--------------------------------|-------------------------|
 * | `screen`        | destination kind (see below)   | all                     |
 * | `api`           | source api id                  | `manga`, `reader`       |
 * | `url`           | manga page url                 | `manga`, `reader`       |
 * | `language`      | source language code           | `reader`                |
 * | `chapterUrl`    | chapter page url               | `reader`                |
 * | `chapterNumber` | chapter number label           | `reader`                |
 * | `title`         | manga title (IDENTITY)         | `reader`                |
 * | `coverUrl`      | manga cover url                | `reader`                |
 * | `chapterName`   | chapter name (cosmetic)        | optional (`reader`)     |
 *
 * `screen` values (case-insensitive, trimmed): `manga` (aliases `manga_details`, `details`),
 * `reader` (alias `chapter`), `updates`, `home`.
 *
 * The parser is intentionally strict: a `reader` payload missing any functional key yields `null`
 * rather than a half-built destination, so a malformed push never lands the user on a broken screen.
 */
object PushPayloadParser {

    const val KEY_SCREEN: String = "screen"
    const val KEY_API: String = "api"
    const val KEY_URL: String = "url"
    const val KEY_LANGUAGE: String = "language"
    const val KEY_CHAPTER_URL: String = "chapterUrl"
    const val KEY_CHAPTER_NUMBER: String = "chapterNumber"
    const val KEY_TITLE: String = "title"
    const val KEY_COVER_URL: String = "coverUrl"
    const val KEY_CHAPTER_NAME: String = "chapterName"

    fun parse(data: Map<String, String>): PushDestination? {
        val screen = data[KEY_SCREEN]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (screen) {
            "manga", "manga_details", "details" -> {
                val api = data.nonBlank(KEY_API) ?: return null
                val url = data.nonBlank(KEY_URL) ?: return null
                PushDestination.MangaDetail(api = api, url = url)
            }

            "reader", "chapter" -> {
                val api = data.nonBlank(KEY_API) ?: return null
                val language = data.nonBlank(KEY_LANGUAGE) ?: return null
                val mangaUrl = data.nonBlank(KEY_URL) ?: return null
                val chapterUrl = data.nonBlank(KEY_CHAPTER_URL) ?: return null
                val chapterNumber = data.nonBlank(KEY_CHAPTER_NUMBER) ?: return null
                // title + coverUrl are REQUIRED, not cosmetic: the reader route seeds a Manga from
                // these args and persists it to history, and (api+language+title) is the manga
                // identity. An omitted title writes a permanent nameless/coverless history row and a
                // phantom identity that never matches the real manga (#4). Only chapterName (the
                // top-bar subtitle) is truly cosmetic and defaults to "".
                val title = data.nonBlank(KEY_TITLE) ?: return null
                val coverUrl = data.nonBlank(KEY_COVER_URL) ?: return null
                PushDestination.Reader(
                    api = api,
                    language = language,
                    mangaUrl = mangaUrl,
                    chapterUrl = chapterUrl,
                    chapterNumber = chapterNumber,
                    title = title,
                    coverUrl = coverUrl,
                    chapterName = data[KEY_CHAPTER_NAME]?.trim().orEmpty(),
                )
            }

            "updates" -> PushDestination.Updates
            "home" -> PushDestination.Home
            else -> null
        }
    }

    private fun Map<String, String>.nonBlank(key: String): String? =
        this[key]?.trim()?.takeIf { it.isNotEmpty() }
}
