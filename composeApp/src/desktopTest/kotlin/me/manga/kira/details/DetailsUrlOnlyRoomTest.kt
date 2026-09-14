package me.manga.kira.details

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.repository.SavedMangaDetailsRepositoryImpl
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.presentation.details.DetailsIntent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsUrlOnlyRoomTest {
    private val dispatcher = StandardTestDispatcher()
    private val manga = roomManga("saved")
    private val first = roomChapter(manga, "1")
    private val second = roomChapter(manga, "2")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun savedUrlOnlyEntryPersistsOneNewChapterBeforeMembershipEmits() =
        roomTest { fixture ->
            val old = fixture.seed(manga, first)
            open(fixture, manga, listOf(second, first))
            assertFalse(fixture.library.ready.isCompleted)
            assertFalse(fixture.vm.state.value.isInLibrary)
            val request = fixture.source.requests.single()
            assertEquals("", request.title)
            assertEquals("", request.language)
            assertEquals(listOf(MangaKey(manga.api, manga.language, manga.title)), fixture.library.observedKeys)
            assertEquals(fixture.library.observedKeys, fixture.library.offeredKeys)
            assertEquals(listOf(1), persistedCounts(fixture))

            val rows = fixture.db.chapterDao().getChaptersByMangaIdR(old.mangaId)
            assertEquals(old, rows.single { it.id == old.id }, "existing read/bookmark/id state is not reset")
            assertNewRow(rows.single { it.url == second.url }, old.mangaId)
            val projected = assertNotNull(fixture.vm.state.value.details).chapters
            assertEquals(setOf(first.url, second.url), projected.map { it.url }.toSet())
            assertTrue(projected.single { it.url == second.url }.isNew, "real Room emission supplies the NEW badge")
        }

    @Test
    fun unsavedUrlOnlyEntryAndRepeatedFetchesNeverCreateMangaOrChapterRows() =
        roomTest { fixture ->
            open(fixture, manga, listOf(second, first))
            assertEquals(listOf(0), persistedCounts(fixture))
            assertFalse(fixture.vm.state.value.isInLibrary)
            fixture.vm.submit(DetailsIntent.OnRetry)
            advanceUntilIdle()
            fixture.library.ready.complete(Unit)
            advanceUntilIdle()
            fixture.vm.submit(DetailsIntent.OnRetry)
            advanceUntilIdle()

            assertEquals(listOf(0, 0, 0), persistedCounts(fixture))
            assertFalse(fixture.vm.state.value.isInLibrary)
            assertEquals(0, fixture.db.statisticsDeo().getTotalMangaCount().first())
            assertEquals(0, fixture.db.statisticsDeo().getTotalChaptersCount().first())
            assertEquals(2, assertNotNull(fixture.vm.state.value.details).chapters.size, "network-only UI still works")
        }

    @Test
    fun repeatedFetchBeforeAndAfterMembershipKeepsIdsAndReopenObservesPersistence() =
        roomTest { fixture ->
            val old = fixture.seed(manga, first)
            open(fixture, manga, listOf(second, first))
            val expectedRows = fixture.db.chapterDao().getChaptersByMangaIdR(old.mangaId)
            assertEquals(2, expectedRows.size)
            assertFalse(fixture.vm.state.value.isInLibrary)
            fixture.vm.submit(DetailsIntent.OnRetry)
            advanceUntilIdle()
            fixture.library.ready.complete(Unit)
            advanceUntilIdle()
            assertTrue(fixture.vm.state.value.isInLibrary)
            fixture.vm.submit(DetailsIntent.OnRetry)
            advanceUntilIdle()

            assertEquals(listOf(1, 0, 0), persistedCounts(fixture))
            assertEquals(expectedRows, fixture.db.chapterDao().getChaptersByMangaIdR(old.mangaId))
            assertEquals(2, fixture.db.statisticsDeo().getTotalChaptersCount().first())
            fixture.clearViewModel()
            runCurrent()
            fixture.reopen()
            assertEquals(expectedRows, fixture.db.chapterDao().getChaptersByMangaIdR(old.mangaId))
            val saved =
                SavedMangaDetailsRepositoryImpl(fixture.db.mangaDao(), fixture.db.chapterDao(), fixture.dispatchers)
            val reopened = assertNotNull(saved.observeSavedDetails(manga.api, manga.title).first())
            assertEquals(setOf(first.url, second.url), reopened.chapters.map { it.url }.toSet())
            assertTrue(reopened.chapters.single { it.url == second.url }.isNew)
        }

    @Test
    fun staleAUrlFetchAfterBEntersCannotPersistOrOverwriteTheNewIdentity() =
        roomTest { fixture ->
            val old = fixture.seed(manga, first)
            val aFetch = CompletableDeferred<Unit>().also { fixture.source.gates[manga.url] = it }
            fixture.source.answers[manga.url] = AppResult.Success(roomDetails(manga, listOf(second, first)))
            fixture.vm.submit(DetailsIntent.OnEnterByUrl(manga.api, manga.url))
            runCurrent()
            assertEquals(listOf(manga.url), fixture.source.requests.map { it.url })
            val other = roomManga("unsaved-b")
            open(fixture, other, listOf(roomChapter(other, "1")))
            val before = fixture.vm.state.value
            assertEquals(other.url, before.manga?.url)
            assertEquals(listOf(0), persistedCounts(fixture))

            aFetch.complete(Unit)
            advanceUntilIdle()

            assertEquals(before, fixture.vm.state.value)
            assertEquals(listOf(MangaKey(other.api, other.language, other.title)), fixture.library.offeredKeys)
            assertEquals(listOf(0), persistedCounts(fixture), "the stale A payload is not even offered")
            assertEquals(listOf(old), fixture.db.chapterDao().getChaptersByMangaIdR(old.mangaId))
            assertEquals(1, fixture.db.statisticsDeo().getTotalMangaCount().first())
            assertEquals(1, fixture.db.statisticsDeo().getTotalChaptersCount().first())
        }

    private fun TestScope.open(fixture: DetailsUrlOnlyRoomFixture, owner: Manga, chapters: List<Chapter>) {
        fixture.source.answers[owner.url] = AppResult.Success(roomDetails(owner, chapters))
        fixture.vm.submit(DetailsIntent.OnEnterByUrl(owner.api, owner.url))
        advanceUntilIdle()
        assertFalse(fixture.vm.state.value.isLoading)
        assertEquals(owner, fixture.vm.state.value.manga)
    }

    private fun assertNewRow(row: SavedChapterEntity, mangaId: Long) {
        assertEquals(mangaId, row.mangaId)
        assertEquals(second.url, row.url)
        assertTrue(row.isNew)
        assertTrue(row.fetchedAt > 0L)
        assertFalse(row.isRead)
        assertFalse(row.isBookmarked)
    }

    private fun persistedCounts(fixture: DetailsUrlOnlyRoomFixture): List<Int> =
        fixture.library.results.map { assertIs<AppResult.Success<Int>>(it).value }

    private fun roomTest(block: suspend TestScope.(DetailsUrlOnlyRoomFixture) -> Unit) =
        runTest(dispatcher) {
            val fixture = DetailsUrlOnlyRoomFixture(dispatcher)
            try {
                block(fixture)
            } finally {
                fixture.clearViewModel()
                runCurrent()
                fixture.close()
            }
        }
}
