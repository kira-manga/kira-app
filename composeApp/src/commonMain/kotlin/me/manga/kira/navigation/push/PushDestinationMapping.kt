package me.manga.kira.navigation.push

import me.manga.kira.navigation.Screen

/**
 * Maps a parsed [PushDestination] onto a concrete navigation [Screen] route.
 *
 * Kept separate from [PushDestination] (a leaf value type) and free of Compose so it is unit
 * testable. The reader maps onto the rework reader route; `title`/`coverUrl` carry through as the
 * manga's seed identity (required by the parser), and only the cosmetic `chapterName` may be empty.
 */
fun PushDestination.toScreen(): Screen = when (this) {
    is PushDestination.MangaDetail -> Screen.MangaDetails(
        mangaUrl = url,
        api = api,
    )

    is PushDestination.Reader -> Screen.ChapterImagesRework(
        api = api,
        language = language,
        title = title,
        mangaUrl = mangaUrl,
        coverUrl = coverUrl,
        chapterNumber = chapterNumber,
        chapterName = chapterName,
        chapterUrl = chapterUrl,
    )

    PushDestination.Updates -> Screen.Updates
    PushDestination.Home -> Screen.Home
}
