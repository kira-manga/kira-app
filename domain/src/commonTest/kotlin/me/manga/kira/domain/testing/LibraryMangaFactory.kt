package me.manga.kira.domain.testing

import kotlin.time.Instant
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.LibraryActivity
import me.manga.kira.domain.model.library.LibraryAffinity
import me.manga.kira.domain.model.library.LibraryChapterCounts

/**
 * Test data factories for the Library domain types.
 *
 * Every parameter has a sensible default so a test names only the field it cares about
 * (`sampleLibraryManga(unreadCount = 3)`), keeping intent obvious and tests resilient to future
 * field additions. `Instant.fromEpochMilliseconds(...)` mirrors the production mapper
 * (`data/.../mapper/LibraryMappers.kt:69`) and needs no `@OptIn` in this toolchain (only
 * `Clock.System.now()` is experimental, not `Instant` construction).
 */
fun sampleManga(
    api: String = "test-api",
    language: String = "en",
    title: String = "Test Manga",
    url: String = "https://example.test/manga",
    coverUrl: String = "",
    rating: Int? = null,
    genres: List<String> = emptyList(),
): Manga = Manga(
    api = api,
    language = language,
    title = title,
    url = url,
    coverUrl = coverUrl,
    rating = rating,
    genres = genres,
)

fun sampleChapter(
    number: String = "1",
    name: String = "",
    url: String = "https://example.test/chapter/$number",
    isDownloaded: Boolean = false,
    isBookmarked: Boolean = false,
    isRead: Boolean = false,
): Chapter = Chapter(
    number = number,
    name = name,
    url = url,
    date = null,
    isDownloaded = isDownloaded,
    isBookmarked = isBookmarked,
    isRead = isRead,
)

fun sampleLibraryManga(
    manga: Manga = sampleManga(),
    addedAtEpochMillis: Long = 0L,
    unreadCount: Int = 0,
    hasDownloads: Boolean = false,
    totalChapters: Int = 0,
    lastReadAtEpochMillis: Long? = null,
    lastOpenedAtEpochMillis: Long = 0L,
    bookmarkedCount: Int = 0,
    downloadedCount: Int = 0,
    isLiked: Boolean = false,
    isWatchingNow: Boolean = false,
    id: Long = 1L,
): LibraryManga = LibraryManga(
    manga = manga,
    identity = SavedWorkIdentity(id, WorkLocator(manga.api, manga.url)),
    activity = LibraryActivity(
        Instant.fromEpochMilliseconds(addedAtEpochMillis),
        Instant.fromEpochMilliseconds(lastOpenedAtEpochMillis),
        lastReadAtEpochMillis?.let(Instant::fromEpochMilliseconds),
    ),
    counts = LibraryChapterCounts(totalChapters, unreadCount, maxOf(downloadedCount, if (hasDownloads) 1 else 0), bookmarkedCount),
    affinity = LibraryAffinity(isLiked, isWatchingNow),
)
