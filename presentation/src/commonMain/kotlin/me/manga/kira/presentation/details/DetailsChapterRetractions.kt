package me.manga.kira.presentation.details

import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails

/** Successful deletions stay hidden until a source fetch requested after that deletion rediscovers them. */
internal class DetailsChapterRetractions {
    private var versions: Map<String, Long> = emptyMap()
    private var versionCounter = 0L

    /** Writes replace the map, so an in-flight fetch keeps its immutable request-time snapshot. */
    val snapshot: Map<String, Long>
        get() = versions

    val urls: Set<String>
        get() = versions.keys

    fun clearVisit() {
        // The counter survives navigation because earlier-visit fetches retain their snapshots.
        versions = emptyMap()
    }

    fun retractIfOwned(
        owner: Manga,
        active: Manga?,
        chapterUrl: String,
    ): Boolean {
        // Resolution, download cleanup and row deletion can each suspend across navigation.
        if (active == null || active.api != owner.api || active.url != owner.url) return false
        // Versions remain monotonic across visits: an old A fetch cannot clear a newer A deletion.
        versionCounter++
        versions += chapterUrl to versionCounter
        return true
    }

    fun acceptFetch(
        fetched: MangaDetails,
        beforeFetch: Map<String, Long>,
    ): MangaDetails {
        // Saved emissions and an older in-flight refresh cannot undo the explicit action.
        // Filter the older payload before both rendering and offering it to persistence.
        val fetchedUrls = fetched.chapters.mapTo(HashSet()) { it.url }
        versions =
            versions.filterNot { (url, version) ->
                url in fetchedUrls && beforeFetch[url] == version
            }
        return fetched.withoutChapterUrls(urls)
    }
}

internal fun MangaDetails.withoutChapterUrls(urls: Set<String>): MangaDetails =
    if (urls.isEmpty()) this else copy(chapters = chapters.filterNot { it.url in urls })

/** Pure projection; the owner's retraction bookkeeping is updated before entering the state CAS. */
internal fun DetailsState.withoutDeletedChapter(
    chapterUrl: String,
    retractedChapterUrls: Set<String>,
): DetailsState =
    copy(
        details = details?.withoutChapterUrls(retractedChapterUrls),
        selectedChapterUrls = selectedChapterUrls - chapterUrl,
        chapterDownloads = chapterDownloads - chapterUrl,
    )
