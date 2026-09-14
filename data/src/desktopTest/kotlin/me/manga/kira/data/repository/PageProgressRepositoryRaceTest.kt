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

private const val RACE_ITERATIONS = 64
private const val REPORT_TICKS = 64
private const val WORKER_TIMEOUT_SECONDS = 5L
private const val CURRENT_OWNER_FRACTION = 0.125f

class PageProgressRepositoryRaceTest {
    @Test
    fun concurrentReportingAndReleaseCannotReinsertOrMutateTheNextOwner() =
        runTest {
            val repository = PageProgressRepositoryImpl()
            val workers = Executors.newFixedThreadPool(2)
            try {
                repeat(RACE_ITERATIONS) {
                    val old = repository.observe("same-page")
                    val attempt = assertNotNull(repository.beginAttempt(old.handle))
                    val start = CyclicBarrier(2)
                    val reports =
                        workers.submit {
                            start.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            repeat(REPORT_TICKS) {
                                attempt.report(PageDownloadProgress.InProgress(it / REPORT_TICKS.toFloat()))
                            }
                        }
                    val release =
                        workers.submit {
                            start.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            repository.clear(old.handle)
                        }
                    release.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    val next = repository.observe("same-page")
                    val current = assertNotNull(repository.beginAttempt(next.handle))
                    current.report(PageDownloadProgress.InProgress(CURRENT_OWNER_FRACTION))
                    reports.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    attempt.report(PageDownloadProgress.Complete)
                    assertEquals(PageDownloadProgress.Idle, old.progress.first())
                    assertEquals(PageDownloadProgress.InProgress(CURRENT_OWNER_FRACTION), next.progress.first())
                    assertNull(repository.beginAttempt(old.handle))
                    assertEquals(1, repository.activeSlotCount)
                    assertEquals(1, repository.activeAttemptCount)
                    repository.clear(next.handle)
                    assertEquals(0, repository.activeSlotCount)
                }
            } finally {
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        }
}
