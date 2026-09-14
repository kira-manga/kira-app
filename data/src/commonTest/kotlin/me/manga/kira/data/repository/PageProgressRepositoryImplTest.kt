package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.PageProgressHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class PageProgressRepositoryImplTest {
    @Test
    fun cancellingNewestProducerRestoresSurvivorWithoutAffectingAnotherReader() =
        runTest {
            val repository = PageProgressRepositoryImpl()
            val firstReader = repository.observe("same-page")
            val otherReader = repository.observe("same-page")
            assertNotSame(firstReader.handle, otherReader.handle)
            val survivor = assertNotNull(repository.beginAttempt(firstReader.handle))
            val newest = assertNotNull(repository.beginAttempt(firstReader.handle))
            val independent = assertNotNull(repository.beginAttempt(otherReader.handle))
            survivor.report(PageDownloadProgress.InProgress(0.25f))
            newest.report(PageDownloadProgress.InProgress(0.75f))
            independent.report(PageDownloadProgress.InProgress(0.5f))
            assertEquals(PageDownloadProgress.InProgress(0.75f), firstReader.progress.first())
            newest.report(PageDownloadProgress.Idle)
            newest.report(PageDownloadProgress.Complete)
            assertEquals(PageDownloadProgress.InProgress(0.25f), firstReader.progress.first())
            repository.clear(firstReader.handle)
            survivor.report(PageDownloadProgress.Complete)
            assertEquals(PageDownloadProgress.Idle, firstReader.progress.first())
            assertEquals(PageDownloadProgress.InProgress(0.5f), otherReader.progress.first())
            assertEquals(1, repository.activeSlotCount)
            assertEquals(1, repository.activeAttemptCount)
            repository.clear(otherReader.handle)
            assertEquals(0, repository.activeSlotCount)
        }

    @Test
    fun returningToSameUrlRejectsOldAttemptsAndUnknownHandles() =
        runTest {
            val repository = PageProgressRepositoryImpl()
            val oldA = repository.observe("A")
            val oldAttempt = assertNotNull(repository.beginAttempt(oldA.handle))
            repository.clear(oldA.handle)
            val b = repository.observe("B")
            repository.clear(b.handle)
            val newA = repository.observe("A")
            assertNotSame(oldA.handle, newA.handle)
            assertNull(repository.beginAttempt(oldA.handle))
            assertNull(repository.beginAttempt(PageProgressHandle("A")))
            oldAttempt.report(PageDownloadProgress.InProgress(0.9f))
            oldAttempt.report(PageDownloadProgress.Complete)
            repository.clear(oldA.handle)
            repository.clear(PageProgressHandle("A"))
            assertEquals(PageDownloadProgress.Idle, newA.progress.first())
            assertEquals(1, repository.activeSlotCount)
            val current = assertNotNull(repository.beginAttempt(newA.handle))
            current.report(PageDownloadProgress.Failed)
            assertEquals(PageDownloadProgress.Failed, newA.progress.first())
            repository.clear(newA.handle)
            assertEquals(0, repository.activeSlotCount)
        }

    @Test
    fun finishedAttemptsAreDiscardedAndCannotOverwriteRetry() =
        runTest {
            val repository = PageProgressRepositoryImpl()
            val observation = repository.observe("retry")
            val terminalStates =
                listOf(PageDownloadProgress.Complete, PageDownloadProgress.Failed, PageDownloadProgress.Idle)
            repeat(96) { index ->
                val finished = assertNotNull(repository.beginAttempt(observation.handle))
                val terminal = terminalStates[index % terminalStates.size]
                finished.report(terminal)
                assertEquals(0, repository.activeAttemptCount)
                assertEquals(terminal, observation.progress.first())
                val retry = assertNotNull(repository.beginAttempt(observation.handle))
                finished.report(PageDownloadProgress.InProgress(1f))
                finished.report(PageDownloadProgress.Idle)
                assertEquals(PageDownloadProgress.Started, observation.progress.first())
                assertEquals(1, repository.activeAttemptCount)
                retry.report(PageDownloadProgress.Idle)
            }
            repository.clear(observation.handle)
            assertEquals(0, repository.activeSlotCount)
            assertEquals(0, repository.activeAttemptCount)
        }

    @Test
    fun repeatedSessionsRetainOnlyCurrentlyAcquiredPagesIncludingUnreportedOnes() =
        runTest {
            val repository = PageProgressRepositoryImpl()
            repeat(48) { session ->
                val pages = List(24) { repository.observe("session-$session/page-$it") }
                val attempted = assertNotNull(repository.beginAttempt(pages.first().handle))
                assertEquals(pages.size, repository.activeSlotCount)
                pages.forEach { repository.clear(it.handle) }
                attempted.report(PageDownloadProgress.Complete)
                assertEquals(PageDownloadProgress.Idle, pages.first().progress.first())
                assertEquals(0, repository.activeSlotCount)
                assertEquals(0, repository.activeAttemptCount)
            }
        }
}
