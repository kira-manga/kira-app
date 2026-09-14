package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.reader.PageDownloadProgress
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageProgressRepositoryRaceTest {
    @Test
    fun concurrentReportingAndReleaseCannotReinsertOrMutateTheNextOwner() = runTest {
        val repository = PageProgressRepositoryImpl()
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(64) {
                val old = repository.observe("same-page")
                val attempt = assertNotNull(repository.beginAttempt(old.handle))
                val start = CyclicBarrier(2)
                val reports = workers.submit {
                    start.await(5, TimeUnit.SECONDS)
                    repeat(64) { attempt.report(PageDownloadProgress.InProgress(it / 64f)) }
                }
                val release = workers.submit {
                    start.await(5, TimeUnit.SECONDS)
                    repository.clear(old.handle)
                }
                release.get(5, TimeUnit.SECONDS)
                val next = repository.observe("same-page")
                val current = assertNotNull(repository.beginAttempt(next.handle))
                current.report(PageDownloadProgress.InProgress(0.125f))
                reports.get(5, TimeUnit.SECONDS)
                attempt.report(PageDownloadProgress.Complete)
                assertEquals(PageDownloadProgress.Idle, old.progress.first())
                assertEquals(PageDownloadProgress.InProgress(0.125f), next.progress.first())
                assertNull(repository.beginAttempt(old.handle))
                assertEquals(1, repository.activeSlotCount)
                assertEquals(1, repository.activeAttemptCount)
                repository.clear(next.handle)
                assertEquals(0, repository.activeSlotCount)
            }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
