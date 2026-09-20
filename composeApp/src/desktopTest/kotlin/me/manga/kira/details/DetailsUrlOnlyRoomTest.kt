package me.manga.kira.details

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.presentation.details.DetailsIntent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            // Scoped saved details now cache-open and carry membership authority themselves.
            // The historical selector is retained; discovery occurs on explicit refresh.
            assertFalse(fixture.library.ready.isCompleted)
            assertTrue(fixture.vm.state.value.isInLibrary)
            val owner = SavedWorkIdentity(old.mangaId, manga.workLocator())
            assertEquals(owner, fixture.vm.state.value.savedOwner)
            assertTrue(fixture.source.requests.isEmpty(), "A saved URL-only cache hit must not fetch automatically")
            assertEquals(listOf(first.url), assertNotNull(fixture.vm.state.value.details).chapters.map { it.url })
            fixture.vm.submit(DetailsIntent.OnRetry)
            advanceUntilIdle()
            assertFalse(fixture.library.ready.isCompleted)
            val request = fixture.source.requests.single()
            assertEquals(manga, request, "The cached display metadata does not replace the requested URL")
            assertTrue(fixture.library.observedKeys.isEmpty(), "The retained Room owner needs no separate scoped membership query")
            assertEquals(listOf(manga.workLocator()), fixture.library.offeredParents)
            assertEquals(owner, fixture.library.refreshBatches.single().single().owner)
            assertEquals(listOf(false), fixture.library.notifyRequests)
            assertEquals(listOf(1), persistedCounts(fixture))

            val rows = fixture.db.chapterDao().getChaptersByMangaIdR(old.mangaId)
            assertEquals(old, rows.single { it.id == old.id }, "existing read/bookmark/id state is not reset")
            assertNewRow(rows.single { it.url == second.url }, old.mangaId, second.url)
            val projected = assertNotNull(fixture.vm.state.value.details).chapters
            assertEquals(setOf(first.url, second.url), projected.map { it.url }.toSet())
            assertTrue(projected.single { it.url == second.url }.isNew, "real Room emission supplies the NEW badge")
        }

    @Test
    fun unsavedUrlOnlyEntryAndRepeatedFetchesNeverCreateMangaOrChapterRows() =
        roomTest { fixture ->
            fixture.source.answers[manga.url] = AppResult.Success(roomDetails(manga, listOf(second, first)))
            fixture.vm.submit(DetailsIntent.OnEnterByUrl(manga.api, manga.url))
            advanceUntilIdle()
            assertTrue(fixture.vm.state.value.isLoading, "An unresolved scoped membership cannot authorize a fetch")
            assertFalse(fixture.library.ready.isCompleted)
            assertTrue(fixture.source.requests.isEmpty())
            assertEquals(listOf(manga.workLocator()), fixture.library.observedKeys)
            fixture.library.ready.complete(Unit)
            advanceUntilIdle()
            assertFalse(fixture.vm.state.value.isLoading)
            assertEquals(manga, fixture.vm.state.value.manga)
            assertEquals("", fixture.source.requests.single().title)
            assertEquals("", fixture.source.requests.single().language)
            assertTrue(persistedCounts(fixture).isEmpty(), "Unsaved fetches must not be offered as fake zero-count refreshes")
            assertFalse(fixture.vm.state.value.isInLibrary)
            retryDetails(fixture, times = 2)

            assertTrue(persistedCounts(fixture).isEmpty())
            assertTrue(fixture.library.offeredParents.isEmpty())
            assertEquals(List(3) { manga.workLocator() }, fixture.library.observedKeys)
            assertEquals(3, fixture.source.requests.size)
            assertFalse(fixture.vm.state.value.isInLibrary)
            assertNull(fixture.vm.state.value.savedOwner)
            assertDetailsRowCounts(fixture, mangas = 0, chapters = 0)
            assertEquals(2, assertNotNull(fixture.vm.state.value.details).chapters.size, "network-only UI still works")
        }

    @Test
    fun repeatedFetchBeforeAndAfterMembershipKeepsIdsAndReopenObservesPersistence() =
        roomTest { fixture ->
            val old = fixture.seed(manga, first)
            open(fixture, manga, listOf(second, first))
            assertTrue(fixture.source.requests.isEmpty())
            fixture.vm.submit(DetailsIntent.OnRetry)
            advanceUntilIdle()
            val expectedRows = fixture.db.chapterDao().getChaptersByMangaIdR(old.mangaId)
            assertEquals(2, expectedRows.size)
            assertFalse(fixture.library.ready.isCompleted)
            assertTrue(fixture.vm.state.value.isInLibrary, "Typed saved-owner membership precedes any separate query")
            fixture.vm.submit(DetailsIntent.OnRetry)
            advanceUntilIdle()
            fixture.library.ready.complete(Unit)
            advanceUntilIdle()
            assertTrue(fixture.vm.state.value.isInLibrary)
            fixture.vm.submit(DetailsIntent.OnRetry)
            advanceUntilIdle()

            assertEquals(listOf(1, 0, 0), persistedCounts(fixture))
            assertEquals(expectedRows, fixture.db.chapterDao().getChaptersByMangaIdR(old.mangaId))
            assertDetailsChapterCount(fixture, expected = 2)
            fixture.clearViewModel()
            runCurrent()
            fixture.reopen()
            assertEquals(expectedRows, fixture.db.chapterDao().getChaptersByMangaIdR(old.mangaId))
            assertReopenedDetails(fixture, SavedWorkIdentity(old.mangaId, manga.workLocator()), first, second)
        }

    @Test
    fun staleAUrlFetchAfterBEntersCannotPersistOrOverwriteTheNewIdentity() =
        roomTest { fixture ->
            val old = fixture.seed(manga, first)
            val aFetch = CompletableDeferred<Unit>().also { fixture.source.gates[manga.url] = it }
            fixture.source.answers[manga.url] = AppResult.Success(roomDetails(manga, listOf(second, first)))
            fixture.vm.submit(DetailsIntent.OnEnterByUrl(manga.api, manga.url))
            runCurrent()
            assertTrue(fixture.source.requests.isEmpty(), "A's scoped cache is not a network fetch")
            fixture.vm.submit(DetailsIntent.OnRetry)
            runCurrent()
            assertEquals(listOf(manga.url), fixture.source.requests.map { it.url })
            val other = roomManga("unsaved-b")
            fixture.library.ready.complete(Unit)
            open(fixture, other, listOf(roomChapter(other, "1")))
            val before = fixture.vm.state.value
            assertEquals(other.url, before.manga?.url)
            assertTrue(persistedCounts(fixture).isEmpty())
            assertEquals(listOf(manga.url), fixture.source.cancelledRequests.map { it.url })

            aFetch.complete(Unit)
            advanceUntilIdle()

            assertEquals(before, fixture.vm.state.value)
            assertTrue(fixture.library.offeredParents.isEmpty(), "Neither stale A nor unsaved B has refresh authority")
            assertEquals(listOf(other.workLocator()), fixture.library.observedKeys)
            assertTrue(persistedCounts(fixture).isEmpty(), "the stale A payload is not even offered")
            assertEquals(listOf(manga.url, other.url), fixture.source.requests.map { it.url })
            assertEquals(listOf(old), fixture.db.chapterDao().getChaptersByMangaIdR(old.mangaId))
            assertDetailsRowCounts(fixture, mangas = 1, chapters = 1)
        }

    @Test
    fun unsavedMetadataTwinCannotAttachItsChaptersToSavedA() =
        roomTest { fixture ->
            val savedA = fixture.seed(manga, first)
            val other = manga.copy(url = "${manga.url}/unsaved-twin")
            fixture.library.ready.complete(Unit)
            open(fixture, other, listOf(roomChapter(other, "1")))
            assertTrue(persistedCounts(fixture).isEmpty())
            assertTrue(fixture.library.offeredParents.isEmpty())
            assertEquals(listOf(other.workLocator()), fixture.library.observedKeys)

            // Typed membership now rejects the old title/language false positive as well as its write.
            assertFalse(fixture.vm.state.value.isInLibrary, "A metadata twin is not the requested saved owner")
            assertNull(fixture.vm.state.value.savedOwner)
            fixture.vm.submit(DetailsIntent.OnRetry)
            advanceUntilIdle()

            assertTrue(persistedCounts(fixture).isEmpty())
            assertEquals(List(2) { other.workLocator() }, fixture.library.observedKeys)
            assertEquals(2, fixture.source.requests.size)
            assertEquals(listOf(savedA), fixture.db.chapterDao().getChaptersByMangaIdR(savedA.mangaId))
            assertNull(fixture.db.mangaDao().getIdByApiAndUrl(other.api, other.url))
            assertDetailsRowCounts(fixture, mangas = 1, chapters = 1)
        }

    @Test
    fun bothSavedMetadataTwinsPersistOnlyRequestedBEvenWhenPayloadNamesAUrl() =
        roomTest { fixture ->
            verifySavedMetadataTwins(fixture, manga, first)
        }

    private fun TestScope.open(
        fixture: DetailsUrlOnlyRoomFixture,
        owner: Manga,
        chapters: List<Chapter>,
    ) {
        fixture.source.answers[owner.url] = AppResult.Success(roomDetails(owner, chapters))
        fixture.vm.submit(DetailsIntent.OnEnterByUrl(owner.api, owner.url))
        advanceUntilIdle()
        assertFalse(fixture.vm.state.value.isLoading)
        assertEquals(owner, fixture.vm.state.value.manga)
    }

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
