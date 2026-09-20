package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
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
            val failed = fixture.covers().updateCoverIfChanged(manga.savedIdentity(), manga.savedIdentity().locator, NEW_COVER)
            assertIs<AppResult.Failure>(failed)
            assertEquals(1, fixture.sql.historyWrites.get(), "fault must reach the real SQL boundary")
            assertEquals(before, fixture.snapshot(manga.id), "saved and history writes must both roll back")

            fixture.sql.afterHistoryWrite = {}
            assertTrue(
                fixture.covers().updateCoverIfChanged(manga.savedIdentity(), manga.savedIdentity().locator, NEW_COVER).isSuccess,
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
                fixture.worker().updateMangaImageUrlEverywhere(before.manga.savedIdentity(), before.manga.savedIdentity().locator, NEW_COVER)
            }
            fixture.sql.afterHistoryWrite = { work.cancel(CancellationException("cancel_cover_fanout")) }
            work.start()
            work.join()
            assertTrue(work.isCancelled)
            assertEquals(1, fixture.sql.historyWrites.get())
            assertEquals(before, fixture.snapshot(before.manga.id))

            fixture.sql.afterHistoryWrite = {}
            fixture.worker().updateMangaImageUrlEverywhere(before.manga.savedIdentity(), before.manga.savedIdentity().locator, NEW_COVER)
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
                fixture.covers().updateCoverIfChanged(manga.savedIdentity(), manga.savedIdentity().locator, NEW_COVER).isSuccess,
            )
            assertEquals(before.withCover(NEW_COVER), fixture.snapshot(manga.id))

            fixture.db.historyDao().updateMangaImageUrlByUrl(manga.url, OLD_COVER)
            fixture.db.notificationDao().updateMangaImageUrl(manga.id, OLD_COVER)
            fixture.worker().updateMangaImageUrlEverywhere(manga.savedIdentity(), manga.savedIdentity().locator, NEW_COVER)
            val repaired = before.withCover(NEW_COVER)
            assertEquals(repaired, fixture.snapshot(manga.id))
            assertTrue(fixture.covers().updateCoverIfChanged(manga.savedIdentity(), manga.savedIdentity().locator, " ").isSuccess)
            fixture.worker().updateMangaImageUrlEverywhere(manga.savedIdentity(), manga.savedIdentity().locator, "")
            val missing = SavedWorkIdentity(Long.MAX_VALUE, WorkLocator(manga.api, "https://current.test/missing"))
            assertIs<AppResult.Failure>(fixture.covers().updateCoverIfChanged(missing, missing.locator, OLD_COVER))
            assertIs<AppResult.Failure>(fixture.worker().updateMangaImageUrlEverywhere(missing, missing.locator, OLD_COVER))
            assertEquals(repaired, fixture.snapshot(manga.id))
        }
    }
}
