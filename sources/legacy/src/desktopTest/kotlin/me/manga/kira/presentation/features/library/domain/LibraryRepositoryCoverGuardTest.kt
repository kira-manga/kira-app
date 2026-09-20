package me.manga.kira.presentation.features.library.domain

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.repository.LibraryMetadataRepository
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.FileSystem
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The legacy facade must not bypass the scoped metadata writer. Actual blank-cover preservation,
 * partial writes, related-row ownership and rollback are covered in :data's real-Room tests.
 */
class LibraryRepositoryCoverGuardTest {
    private val db = Room.inMemoryDatabaseBuilder<MangaDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
    private val metadata = RecordingMetadataRepository()
    private val repository = LibraryRepository(
        mangaDao = db.mangaDao(),
        chapterDao = db.chapterDao(),
        libraryDeo = db.libraryDeo(),
        metadata = metadata,
        fileService = FileService(UnusedAppFileSystem),
    )

    @AfterTest
    fun close() = db.close()

    @Test
    fun blank_cover_delegates_both_addresses_and_the_retained_owner() = runTest {
        val owner = SavedWorkIdentity(41L, WorkLocator("source", "https://current.test/work/one"))
        val returned = WorkLocator("source", "https://old.test/work/one")

        val result = repository.updateMangaImageUrlEverywhere(owner, returned, "")

        assertEquals(AppResult.Success(Unit), result)
        assertEquals(CoverRequest(owner, returned, ""), metadata.request)
    }

    @Test
    fun scoped_cover_failure_is_not_converted_to_success_or_an_id_only_write() = runTest {
        val owner = SavedWorkIdentity(41L, WorkLocator("source", "https://current.test/work/one"))
        val returned = WorkLocator("source", "https://unrelated.test/work/one")
        val error = AppError.Storage.Constraint("unproven returned owner")
        metadata.result = AppResult.Failure(error)

        val result = repository.updateMangaImageUrlEverywhere(owner, returned, "https://images.test/new.jpg")

        assertEquals(AppResult.Failure(error), result)
        assertEquals(CoverRequest(owner, returned, "https://images.test/new.jpg"), metadata.request)
    }

    private data class CoverRequest(val owner: SavedWorkIdentity, val returned: WorkLocator, val cover: String)

    private class RecordingMetadataRepository : LibraryMetadataRepository {
        var request: CoverRequest? = null
        var result: AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun updateCoverIfChanged(
            owner: SavedWorkIdentity,
            fetched: WorkLocator,
            newCoverUrl: String,
        ): AppResult<Unit> {
            request = CoverRequest(owner, fetched, newCoverUrl)
            return result
        }
    }

    /** No cover call touches files; this required dependency must not create a directory. */
    private object UnusedAppFileSystem : AppFileSystem {
        override val filesDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "unused-cover-guard-files"
        override val cacheDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "unused-cover-guard-cache"

        override fun fileSystem(): FileSystem = FileSystem.SYSTEM
    }
}
