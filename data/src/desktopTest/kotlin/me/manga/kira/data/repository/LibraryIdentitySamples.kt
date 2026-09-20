package me.manga.kira.data.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.identity.AcceptedCatalogToken
import me.manga.kira.data.identity.AcceptedSourceAliasRule
import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.data.local.dao.ReaderProgressSnapshot
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.sources.contracts.PreviousHostAuthority
import me.manga.kira.sources.contracts.SelectedCatalogIdentity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SelectedPreviousHost
import me.manga.kira.sources.contracts.SourceSelectionProofRef

internal const val LIBRARY_TEST_API = "source"
internal const val LIBRARY_CURRENT_URL = "https://current.test/work/one"
internal const val LIBRARY_PREVIOUS_URL = "https://old.test/work/one"

internal fun libraryPolicy(revision: Long = 1L, previousHosts: List<String> = listOf("old.test")) =
    SourceAliasSnapshot(
        AcceptedCatalogToken(revision, SelectedCatalogIdentity(SelectedCatalogKind.SIGNED, revision, "a".repeat(64)), revision.toString(16).padStart(64, '0')),
        listOf(AcceptedSourceAliasRule(LIBRARY_TEST_API, "https://current.test", previousHosts.map { host ->
            SelectedPreviousHost(host, PreviousHostAuthority.PROVEN_COMPATIBLE_ROOT,
                SourceSelectionProofRef(SelectedCatalogIdentity(SelectedCatalogKind.BUNDLED, 0, "b".repeat(64)), LIBRARY_TEST_API, null))
        })),
    )

internal fun libraryParent(url: String = LIBRARY_CURRENT_URL, api: String = LIBRARY_TEST_API) = SavedMangaEntity(
    api = api,
    language = "en",
    url = url,
    imageUrl = "https://images.test/old.jpg",
    title = "Same display title",
    description = "Original description",
    author = "Original author",
    status = "ongoing",
    rating = "4.5",
    genres = listOf("original"),
    savedTimestamp = 101L,
    lastOpenTimestamp = 202L,
    isLiked = true,
    isWatchingNow = true,
)

internal fun libraryFetched(
    parent: SavedMangaEntity = libraryParent(),
    chapters: List<Chapter> = emptyList(),
    title: String = "Renamed fetched title",
    url: String = parent.url,
    language: String = "ar",
) = FetchedWorkDetails(
    WorkLocator(parent.api, parent.url),
    MangaDetails(
        api = parent.api, language = language, title = title, url = url,
        coverUrl = "https://images.test/new.jpg", description = "Fetched description", author = "Fetched author",
        rating = "4.8 / 5", status = "Completed", genres = listOf("new", "fantasy"), chapters = chapters,
    ),
)

internal fun libraryRequest(parent: SavedMangaEntity, chapters: List<Chapter> = emptyList()) =
    LibraryRefreshRequest(parent.savedIdentity(), libraryFetched(parent, chapters))

internal fun libraryChapter(url: String, number: String = url.substringAfterLast('/')) = Chapter(
    number = number, name = "Chapter $number", url = url, date = null,
    isDownloaded = false, isBookmarked = false,
)

internal fun librarySavedChapter(parent: SavedMangaEntity, url: String = "${parent.url}/chapter/one") = SavedChapterEntity(
    mangaId = parent.id, name = "Saved chapter", number = "1", url = url, date = null,
    isDownloaded = true, isBookmarked = true, isRead = true, isNew = true,
    lastReadPage = 4, lastReadDate = 303L, localImagePaths = listOf("/fixture/offline.cbz"), fetchedAt = 404L,
)

internal fun libraryDownload(
    parent: SavedMangaEntity,
    chapter: SavedChapterEntity,
    state: DownloadingState = DownloadingState.SUCCESS,
) = ChapterDownloadEntity(
    number = chapter.number, chapterId = chapter.id, mangaId = parent.id, api = parent.api,
    mangaTitle = parent.title, url = chapter.url, state = state, progress = 100, sizeBytes = 505L,
)

internal fun libraryHistory(parent: SavedMangaEntity, linkedId: Long = parent.id) = HistoryItemD(
    api = parent.api, language = parent.language, mangaId = linkedId, mangaUrl = parent.url,
    mangaTitle = parent.title, mangaImageUrl = parent.imageUrl, chapterUrl = "${parent.url}/chapter/one",
    chapterTitle = "Saved chapter", isDownloaded = true, localImagePaths = listOf("/fixture/offline.cbz"),
    lastReadDate = LocalDateTime(2026, 9, 1, 1, 2), lastReadPage = 4, totalPages = 20,
)

internal fun libraryNotification(parent: SavedMangaEntity, chapter: SavedChapterEntity) = ChapterNotification(
    api = parent.api, language = parent.language, mangaId = parent.id, mangaTitle = parent.title,
    mangaImageUrl = parent.imageUrl, mangaUrl = parent.url, chapterId = chapter.id,
    chapterNumber = chapter.number, chapterUrl = chapter.url, notificationDate = LocalDate(2026, 9, 1),
    isRead = true, isDownloaded = true, localImagePaths = chapter.localImagePaths,
)

internal data class LibraryRemovalSeed(
    val parent: SavedMangaEntity,
    val chapter: SavedChapterEntity,
    val progress: ReaderProgressSnapshot,
) {
    val owner get() = parent.savedIdentity()
}

internal suspend fun LibraryIdentityFixture.removalSeed(
    draft: SavedMangaEntity = libraryParent(),
    state: DownloadingState = DownloadingState.SUCCESS,
): LibraryRemovalSeed {
    val parent = parent(draft)
    val chapter = chapter(librarySavedChapter(parent))
    db.chapterDownloadingDao().insert(libraryDownload(parent, chapter, state))
    db.historyDao().insertHistory(libraryHistory(parent, linkedId = 0L))
    db.notificationDao().insertNotificationsList(listOf(libraryNotification(parent, chapter)))
    val progress = db.readerProgressDao().ensureSnapshot(parent.api, parent.url, chapter.url)
    check(db.readerProgressDao().savePosition(progress, 4))
    files.seed(parent.savedIdentity())
    return LibraryRemovalSeed(parent, chapter, progress.copy(pageIndex = 4))
}
