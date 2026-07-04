package me.manga.kira.domain.testing

import kotlin.time.Instant
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga

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
): LibraryManga = LibraryManga(
    manga = manga,
    addedAt = Instant.fromEpochMilliseconds(addedAtEpochMillis),
    unreadCount = unreadCount,
    hasDownloads = hasDownloads,
    totalChapters = totalChapters,
    lastReadAt = lastReadAtEpochMillis?.let { Instant.fromEpochMilliseconds(it) },
    lastOpenedAt = Instant.fromEpochMilliseconds(lastOpenedAtEpochMillis),
    bookmarkedCount = bookmarkedCount,
    downloadedCount = downloadedCount,
    isLiked = isLiked,
    isWatchingNow = isWatchingNow,
)
