package me.manga.kira.data.local.dao

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.entity.SavedMangaEntity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Real Room/SQLite ownership and partial-column tests, not simulated DAO update behavior. */
class MangaMetadataUpdateTest {
    private lateinit var fixture: MangaMetadataFixture
    private val dao: MangaDao get() = fixture.dao

    @BeforeTest
    fun open() {
        fixture = MangaMetadataFixture()
    }

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun delayedMetadataRefreshPreservesNewLocalStateAndEveryChildColumn() = runTest {
        val seed = fixture.seed()
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val patch = metadataPatch(seed.manga.id)
        val refresh = async {
            val stale = assertNotNull(dao.getMangaById(seed.manga.id))
            captured.complete(Unit)
            release.await()
            dao.updateMetadataForExactOwner(stale.api, stale.url, patch)
        }
        captured.await()
        fixture.writeLocalState(seed.manga)
        val latest = seed.manga.copy(isLiked = true, isWatchingNow = true, lastOpenTimestamp = 999L)
        assertEquals(latest, dao.getMangaById(seed.manga.id))
        release.complete(Unit)
        assertEquals(1, refresh.await())
        assertEquals(expectedMetadata(latest, patch), dao.getMangaById(seed.manga.id))
        fixture.assertChildrenUnchanged(seed)
    }

    @Test
    fun atomicTogglesAndCoverOnlyWriteCannotRestoreOldMetadata() = runTest {
        val seed = fixture.seed()
        val owner = seed.manga
        val patch = metadataPatch(owner.id)
        assertEquals(1, dao.updateMetadataForExactOwner(owner.api, owner.url, patch))
        val toggles = listOf(
            async { dao.toggleLikedForExactOwner(owner.id, owner.api, owner.url) },
            async { dao.toggleLikedForExactOwner(owner.id, owner.api, owner.url) },
            async { dao.toggleWatchingForExactOwner(owner.id, owner.api, owner.url) },
            async { dao.toggleWatchingForExactOwner(owner.id, owner.api, owner.url) },
        )
        toggles.forEach { assertEquals(1, it.await()) }
        val cover = "https://images.test/newer-cover.jpg"
        assertEquals(1, dao.updateCoverForExactOwner(owner.id, owner.api, owner.url, cover))
        assertEquals(expectedMetadata(owner, patch).copy(imageUrl = cover), dao.getMangaById(owner.id))
        fixture.assertChildrenUnchanged(seed)
    }

    @Test
    fun wrongOrMissingOwnerAffectsZeroRowsWithoutTouchingEitherFamily() = runTest {
        val first = fixture.seed()
        val second = fixture.seed(metadataManga(url = "https://current.test/work/two"))
        listOf(
            first.manga.copy(api = "other"),
            first.manga.copy(url = second.manga.url),
            first.manga.copy(id = first.manga.id + 1_000),
        ).forEach { assertRejectedOwner(it) }
        assertEquals(0, dao.updateMetadataColumns(metadataPatch(first.manga.id + 1_000)))
        assertEquals(first.manga, dao.getMangaById(first.manga.id))
        assertEquals(second.manga, dao.getMangaById(second.manga.id))
        fixture.assertChildrenUnchanged(first)
        fixture.assertChildrenUnchanged(second)
    }

    @Test
    fun exactLookupKeepsSameTitleLanguagesUrlsAndSourceOwnershipSeparate() = runTest {
        val first = fixture.seed()
        val second = fixture.seed(metadataManga(url = "https://current.test/work/two", language = "ar"))
        val third = fixture.seed(metadataManga(url = "https://other.test/work/one", api = "other"))
        val seeds = listOf(first, second, third)
        seeds.forEach { seed ->
            val owner = seed.manga
            assertEquals(owner, dao.getMangaByExactUrl(owner.url))
            assertEquals(owner, dao.getMangaByExactOwner(owner.id, owner.api, owner.url))
        }
        assertEquals(setOf(first.manga.id, second.manga.id), dao.getMangaByApi("source").map { it.id }.toSet())
        assertEquals(-1L, fixture.db.backupDao().insertMangaRow(first.manga.copy(id = 0, api = "other")))
        assertEquals(first.manga, dao.getMangaByExactUrl(first.manga.url))
        assertNull(dao.getMangaByExactOwner(first.manga.id, "other", first.manga.url))
        assertEquals(3, fixture.db.backupDao().getAllSavedManga().size)
        seeds.forEach { fixture.assertChildrenUnchanged(it) }
    }

    @Test
    fun preservationFixtureReallyRejectsOrphansAndCascadesParentDeletion() = runTest {
        val seed = fixture.seed()
        assertFails {
            fixture.db.backupDao().insertChapterRow(seed.chapter.copy(id = 0, mangaId = seed.manga.id + 1_000))
        }
        fixture.assertChildrenUnchanged(seed)
        assertEquals(1, fixture.db.libraryDeo().deleteMangaById(seed.manga.id))
        assertNull(fixture.db.chapterDao().getChapterByIdSuspend(seed.chapter.id))
        assertNull(fixture.db.chapterDownloadingDao().getDownloadByChapter(seed.chapter.id))
    }

    private suspend fun assertRejectedOwner(owner: SavedMangaEntity) {
        assertNull(dao.getMangaByExactOwner(owner.id, owner.api, owner.url))
        assertEquals(0, dao.updateMetadataForExactOwner(owner.api, owner.url, metadataPatch(owner.id)))
        assertEquals(0, dao.updateCoverForExactOwner(owner.id, owner.api, owner.url, "unowned-cover"))
        assertEquals(0, dao.toggleLikedForExactOwner(owner.id, owner.api, owner.url))
        assertEquals(0, dao.toggleWatchingForExactOwner(owner.id, owner.api, owner.url))
        assertEquals(0, dao.updateOpenedForExactOwner(owner.id, owner.api, owner.url, 999L))
    }
}
