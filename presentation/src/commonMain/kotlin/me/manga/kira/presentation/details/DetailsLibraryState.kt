package me.manga.kira.presentation.details

import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal fun DetailsState.enteringWork(manga: Manga, adult: Boolean): DetailsState = copy(
    manga = manga,
    isLoading = true,
    details = null,
    error = null,
    isAdult = adult,
    adultGateStep = if (adult) AdultGateStep.AdultWarning else AdultGateStep.None,
    isInLibrary = false,
    savedOwner = null,
    libraryError = null,
    isTogglingBookmark = false,
    selectedChapterUrls = emptySet(),
    chapterDownloads = emptyMap(),
)

internal fun DetailsState.withLibraryOwner(owner: SavedWorkIdentity?): DetailsState = copy(
    isInLibrary = owner != null,
    savedOwner = owner,
    libraryError = null,
)

internal fun DetailsState.withLibraryFailure(failure: AppError): DetailsState = copy(
    isInLibrary = false,
    savedOwner = null,
    libraryError = failure,
    error = failure,
)

internal fun DetailsState.showWorkDetails(fetched: MangaDetails, adult: Boolean): DetailsState = copy(
    manga = manga?.copy(
        language = fetched.language,
        title = fetched.title,
        coverUrl = fetched.coverUrl,
        genres = fetched.genres,
    ),
    details = fetched.expireNewBadges(nowMs()),
    isLoading = false,
    error = libraryError,
    isAdult = adult,
    adultGateStep = when {
        !adult -> AdultGateStep.None
        adultGateStep == AdultGateStep.None -> AdultGateStep.AdultWarning
        else -> adultGateStep
    },
)

/** The network list/order stays the base; saved flags (including false/reset) stay authoritative. */
internal fun MangaDetails.overlaidWith(saved: MangaDetails): MangaDetails {
    if (saved.chapters.isEmpty()) return this
    // Empty transport results preserve last-known-good chapters; explicit local deletion is separate.
    val networkOrLastKnownGood = if (chapters.isEmpty()) saved.chapters else chapters
    val savedByUrl = saved.chapters.associateBy { it.url }
    return copy(chapters = networkOrLastKnownGood.map { chapter ->
        savedByUrl[chapter.url]?.let(chapter::withSavedFlags) ?: chapter
    })
}

private fun Chapter.withSavedFlags(saved: Chapter): Chapter = copy(
    isRead = saved.isRead,
    isDownloaded = saved.isDownloaded,
    isBookmarked = saved.isBookmarked,
    isNew = saved.isNew,
    fetchedAt = saved.fetchedAt,
    lastReadAtEpochMillis = saved.lastReadAtEpochMillis,
)

/** 4 days in milliseconds — the window the NEW badge stays visible after discovery if unopened. */
internal const val NEW_BADGE_WINDOW_MS: Long = 4L * 24 * 60 * 60 * 1000

/**
 * Read-time NEW-badge expiry (#3, deliberate deviation from native, which has no expiry). Forces
 * `isNew = false` on any chapter whose discovery timestamp ([Chapter.fetchedAt]) is older than
 * [NEW_BADGE_WINDOW_MS] (or unknown, i.e. `0`), so the badge auto-disappears 4 days after discovery
 * even if the chapter was never opened. The persisted `isNew` flag is untouched (the badge is
 * re-evaluated against the clock on every emission); an explicit clear-on-open still wins immediately.
 */
internal fun MangaDetails.expireNewBadges(nowMs: Long): MangaDetails {
    if (chapters.none { it.isNew }) return this
    return copy(
        chapters =
            chapters.map { c ->
                if (c.isNew && !isWithinNewWindow(c.fetchedAt, nowMs)) c.copy(isNew = false) else c
            },
    )
}

private fun isWithinNewWindow(
    fetchedAt: Long,
    nowMs: Long,
): Boolean = fetchedAt > 0L && (nowMs - fetchedAt) in 0L until NEW_BADGE_WINDOW_MS

/** Current wall-clock in epoch-millis for the read-time badge-expiry evaluation. */
@OptIn(ExperimentalTime::class)
private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
