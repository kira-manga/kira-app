package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LibraryCoverAtomicityTest {
    @Test
    fun sharedCoverFailureAfterHistoryWriteRollsBackAndRetryPersistsAllCopies() = runTest {
        LibraryMetadataFixture().use { fixture ->
            val before = fixture.seed()
            val manga = before.manga
            fixture.sql.afterHistoryWrite = { error("cover_history_write_fault") }
            val failed = fixture.shared().updateCoverIfChanged(manga.api, manga.language, manga.title, NEW_COVER)
            assertIs<AppResult.Failure>(failed)
            assertEquals(1, fixture.sql.historyWrites.get(), "fault must reach the real SQL boundary")
            assertEquals(before, fixture.snapshot(manga.id), "saved and history writes must both roll back")

            fixture.sql.afterHistoryWrite = {}
            assertTrue(
                fixture.shared().updateCoverIfChanged(manga.api, manga.language, manga.title, NEW_COVER).isSuccess,
            )
            assertEquals(before.withCover(NEW_COVER), fixture.snapshot(manga.id))
            fixture.reopen()
            assertEquals(before.withCover(NEW_COVER), fixture.snapshot(manga.id))
        }
    }

    @Test
    fun workerFacadeCancellationAfterHistoryWriteRollsBackAndJoinedRetryConverges() = runTest {
        LibraryMetadataFixture().use { fixture ->
            val before = fixture.seed()
            val work = launch(start = CoroutineStart.LAZY) {
                fixture.worker().updateMangaImageUrlEverywhere(before.manga.id, NEW_COVER)
            }
            fixture.sql.afterHistoryWrite = { work.cancel(CancellationException("cancel_cover_fanout")) }
            work.start()
            work.join()
            assertTrue(work.isCancelled)
            assertEquals(1, fixture.sql.historyWrites.get())
            assertEquals(before, fixture.snapshot(before.manga.id))

            fixture.sql.afterHistoryWrite = {}
            fixture.worker().updateMangaImageUrlEverywhere(before.manga.id, NEW_COVER)
            assertEquals(before.withCover(NEW_COVER), fixture.snapshot(before.manga.id))
            fixture.reopen()
            assertEquals(before.withCover(NEW_COVER), fixture.snapshot(before.manga.id))
        }
    }

    @Test
    fun bothEntryPointsRepairEqualSavedCoversAndRejectBlankOrAbsentParents() = runTest {
        LibraryMetadataFixture().use { fixture ->
            val before = fixture.seed()
            val manga = before.manga
            // Simulate a pre-fix partial fan-out: saved cover changed, copies still old.
            fixture.db.mangaDao().updateSavedCover(manga.id, NEW_COVER)
            assertTrue(
                fixture.shared().updateCoverIfChanged(manga.api, manga.language, manga.title, NEW_COVER).isSuccess,
            )
            assertEquals(before.withCover(NEW_COVER), fixture.snapshot(manga.id))

            fixture.db.historyDao().updateMangaImageUrlByUrl(manga.url, OLD_COVER)
            fixture.db.notificationDao().updateMangaImageUrl(manga.id, OLD_COVER)
            fixture.worker().updateMangaImageUrlEverywhere(manga.id, NEW_COVER)
            val repaired = before.withCover(NEW_COVER)
            assertEquals(repaired, fixture.snapshot(manga.id))
            assertTrue(fixture.shared().updateCoverIfChanged(manga.api, manga.language, manga.title, " ").isSuccess)
            fixture.worker().updateMangaImageUrlEverywhere(manga.id, "")
            assertTrue(
                fixture.shared().updateCoverIfChanged("missing", manga.language, manga.title, OLD_COVER).isSuccess,
            )
            fixture.worker().updateMangaImageUrlEverywhere(Long.MAX_VALUE, OLD_COVER)
            assertEquals(repaired, fixture.snapshot(manga.id))
        }
    }
}
