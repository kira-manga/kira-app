package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.domain.service.FileService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Persisted update IDs are consumed by the actual read, navigation resolver and download adapter. */
class RefreshDiscoveryOwnershipTest {
    @Test
    fun sameChapterUrlUnderSameTitledParentsKeepsEveryConsumerMangaScoped() = runBlocking {
        RefreshDiscoveryFixture().use { fixture ->
            val a = fixture.parent("a")
            val b = fixture.parent("b")
            val repo = fixture.repository()
            val chapter = refreshChapter()
            assertEquals(AppResult.Success(1), repo.persistNewChaptersAndNotify(a.refreshManga(), listOf(chapter)))
            val staleMetadata = b.refreshManga().copy(title = "old title", coverUrl = "old cover", language = "old")
            assertEquals(AppResult.Success(1), repo.persistNewChaptersAndNotify(staleMetadata, listOf(chapter)))
            val updates = UpdatesRepositoryImpl(fixture.db.notificationDao(), fixture.db.libraryDeo())
            val entries = updates.observeUpdates().first()
            val entryA = entries.single { it.mangaId == a.id }
            val entryB = entries.single { it.mangaId == b.id }
            assertNotEquals(entryA.chapterId, entryB.chapterId)
            assertEquals(b.title, entryB.mangaTitle)
            assertEquals(b.imageUrl, entryB.mangaImageUrl)
            assertEquals(b.language, entryB.language)
            assertEquals(b.url, entryB.mangaUrl)

            assertConsumersUseB(fixture, a = entryA, b = entryB, parent = b)
        }
    }

    private suspend fun assertConsumersUseB(
        fixture: RefreshDiscoveryFixture, a: UpdateEntry, b: UpdateEntry, parent: SavedMangaEntity,
    ) {
        val updates = UpdatesRepositoryImpl(fixture.db.notificationDao(), fixture.db.libraryDeo())
        updates.markAsRead(b)
        assertFalse(assertNotNull(fixture.db.chapterDao().getChapterByIdSuspend(a.chapterId)).isRead)
        val savedB = assertNotNull(fixture.db.chapterDao().getChapterByIdSuspend(b.chapterId))
        assertTrue(savedB.isRead)
        assertEquals(parent.id, savedB.mangaId, "reader's stored chapter ID resolves to B")
        assertEquals(b.chapterId, ChapterIdResolverImpl(fixture.db.chapterDao()).resolveChapterId(parent.refreshManga(), b.chapterUrl))

        val requested = mutableListOf<SavedChapterEntity>()
        val downloads = object : FakeDownloadRepository() {
            override suspend fun enqueueChapterDownload(chapter: SavedChapterEntity, title: String, mangaApi: String) {
                requested += chapter
            }
        }
        val actions = DownloadsActionRepositoryImpl(
            downloads, fixture.db.chapterDownloadingDao(), fixture.db.chapterDao(), fixture.files, FileService(fixture.files),
        )
        assertTrue(actions.enqueueDownload(b.chapterId, b.mangaTitle, b.api).isSuccess)
        assertEquals(listOf(savedB), requested, "download engine receives B's actual saved row")
    }

    @Test
    fun absentParentOrWrongApiNeverFallsBackToSameTitle() = runBlocking {
        RefreshDiscoveryFixture().use { fixture ->
            val parent = fixture.parent()
            val manga = parent.refreshManga()
            val repo = fixture.repository()
            assertEquals(AppResult.Success(0), repo.persistNewChaptersAndNotify(manga.copy(url = "https://manga.test/absent"), listOf(refreshChapter())))
            assertEquals(AppResult.Success(0), repo.persistNewChaptersAndNotify(manga.copy(api = "other"), listOf(refreshChapter())))
            assertTrue(fixture.updates().isEmpty())
            assertTrue(fixture.db.chapterDao().getChaptersByMangaIdR(parent.id).isEmpty())
        }
    }

    @Test
    fun uniquenessAndUndoPreserveTheNewerRowsIdentityAndUserState() = runBlocking {
        RefreshDiscoveryFixture().use { fixture ->
            val parent = fixture.parent()
            fixture.repository().persistNewChaptersAndNotify(parent.refreshManga(), listOf(refreshChapter()))
            val dao = fixture.db.notificationDao()
            val updates = UpdatesRepositoryImpl(dao, fixture.db.libraryDeo())
            val stale = updates.observeUpdates().first().single()
            val original = fixture.updates().single()
            updates.deleteEntry(stale)
            val newer = original.copy(id = 0, isRead = true, isDownloaded = true, localImagePaths = listOf("owned/page"))
            val newerId = dao.insertNotificationsList(listOf(newer)).single()
            assertTrue(newerId > original.id)

            updates.restoreEntry(stale)
            updates.restoreEntry(stale)
            assertEquals(listOf(newer.copy(id = newerId)), fixture.updates())
            assertEquals(listOf(-1L), dao.insertNotificationsList(listOf(original.copy(id = 0))))

            updates.deleteAll()
            updates.restoreEntry(stale)
            assertEquals(listOf(original), fixture.updates(), "ordinary Undo still restores its original ID/date/position")
        }
    }
}
