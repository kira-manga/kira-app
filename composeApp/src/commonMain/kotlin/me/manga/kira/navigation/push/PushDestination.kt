package me.manga.kira.navigation.push

/**
 * A resolved deep-link target parsed from a push-notification (or in-app-message action) data
 * payload. Pure, platform-agnostic value type — the platform edges (Android FCM service /
 * MainActivity, iOS AppDelegate) only ever hand a raw `Map<String, String>` to [PushPayloadParser],
 * which produces one of these; navigation-layer code turns it into a concrete
 * [me.manga.kira.navigation.Screen] route via `PushDestination.toScreen()`.
 *
 * Fields carry the data the target route needs. The reader route seeds a Manga/Chapter directly from
 * these — it does NOT re-fetch title/cover — and (api+language+title) is the manga identity, so for a
 * [Reader] link `title` and `coverUrl` are required (the parser rejects a link missing them). Only
 * `chapterName` (the top-bar subtitle) is cosmetic and defaults to empty string.
 */
sealed interface PushDestination {

    /** Manga detail page. Targets [me.manga.kira.navigation.Screen.MangaDetails], whose adapter
     * hydrates the full manga from `(api, url)` via the details VM's by-URL entry. */
    data class MangaDetail(
        val api: String,
        val url: String,
    ) : PushDestination

    /** Chapter reader. Targets [me.manga.kira.navigation.Screen.ChapterImagesRework]; the reader
     * fetches pages from `chapterUrl` and takes its manga identity + chrome (title/cover) from these
     * args (not from a re-fetch), so `title`/`coverUrl` are required by the parser. */
    data class Reader(
        val api: String,
        val language: String,
        val mangaUrl: String,
        val chapterUrl: String,
        val chapterNumber: String,
        val title: String,
        val coverUrl: String,
        val chapterName: String,
    ) : PushDestination

    /** The Updates tab. */
    data object Updates : PushDestination

    /** The Home tab (explicit "open home"; distinct from a null parse, which means "just open the
     * app at its normal start destination"). */
    data object Home : PushDestination
}
