package me.manga.kira.data.repository.library

import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.repository.progress.ProgressStorage
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.library.FetchedWorkDetails

/** Library writer collaborators, assembled explicitly by composition. No fallback implementations. */
class LibraryWriteDependencies(
    val owners: LibraryOwnerTransactions,
    val libraryDao: LibraryDeo,
    val metadata: LibraryMetadataWriter,
    val chapters: LibraryChapterWriter,
    val removal: LibraryRemovalWriter,
)

/** Local stores participating in the same checked removal transaction. */
class LibraryRemovalStorage(
    val libraryDao: LibraryDeo,
    val progress: ProgressStorage,
    val downloads: ChapterDownloadDao,
)

internal data class LibraryRefreshPlan(
    val owner: SavedMangaEntity,
    val fetched: FetchedWorkDetails,
    val newChapters: List<Chapter>,
    val related: LibraryRelatedRows,
)

internal data class LibraryRemovalPlan(
    val owner: SavedMangaEntity,
    val related: LibraryRelatedRows,
    val downloadCount: Int,
)
