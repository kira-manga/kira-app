package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The exact stop-request boundary used by Android, backed by real Room and real producer permits. */
class FailedCleanupStopBoundaryTest {
    @Test
    fun historicalCleanupDoesNotStopAnUnrelatedInFlightProducerOrDiscardItsPages() = downloadRecoveryTest {
        val legacy = seed(DownloadingState.FAILED)
        val settled = seed(DownloadingState.QUEUED)
        val idle = seed(DownloadingState.QUEUED)
        val other = seed(DownloadingState.RUNNING)
        val runtime = artifactRuntime
        val settledClaim = assertNotNull(runtime.downloads.claim(settled.download))
        assertTrue(runtime.downloads.fail(settledClaim, "retained failure"))
        assertTrue(runtime.downloads.settle(settledClaim))
        assertNull(runtime.dao.get(settled.saved.id)?.token)
        val idleClaim = assertNotNull(runtime.downloads.claim(idle.download))
        assertTrue(runtime.downloads.fail(idleClaim, "producer already unwound"))
        val otherClaim = assertNotNull(runtime.downloads.claim(other.download))
        val otherPage = appFileSystem.chapterDir(other.saved.mangaId, other.saved.id) / "image_0.png"
        fs.write(otherPage) { writeUtf8("unrelated resumable progress") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stops = 0
        coroutineScope {
            val producer = async {
                runtime.ownership.producing(otherClaim) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                entered.await()
                for (historical in listOf(legacy, settled, idle)) {
                    val partial = appFileSystem.chapterDir(historical.saved.mangaId, historical.saved.id) / "image_0.png"
                    fs.write(partial) { writeUtf8("failed attempt partial") }
                    assertTrue(runtime.downloads.deleteAttempt(download(historical)) { cleanup ->
                        assertFalse(runtime.ownership.requestStopIfProducing(cleanup) {
                            // Models the destructive shared-worker signal; it must never be sent.
                            stops++
                            producer.cancel()
                        })
                    })
                    assertFalse(fs.exists(partial))
                    assertNull(dao.getDownloadByChapter(historical.saved.id))
                }
                assertEquals(0, stops)
                assertTrue(producer.isActive)
                assertTrue(runtime.dao.canPublish(otherClaim))
                assertEquals(other.download, download(other))
                assertEquals("unrelated resumable progress", fs.read(otherPage) { readUtf8() })
            } finally {
                release.complete(Unit)
            }
            producer.await()
        }
    }

    @Test
    fun cleanupStillRequestsStopForTheExactOldTokenAndWaitsForItsActualDrain() = downloadRecoveryTest {
        val original = seed(DownloadingState.RUNNING)
        val runtime = artifactRuntime
        val claim = assertNotNull(runtime.downloads.claim(original.download))
        val page = appFileSystem.chapterDir(original.saved.mangaId, original.saved.id) / "image_0.png"
        fs.write(page) { writeUtf8("owned partial") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stopRequested = CompletableDeferred<Unit>()
        var stops = 0
        coroutineScope {
            val producer = async {
                runtime.ownership.producing(claim) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                entered.await()
                assertTrue(runtime.downloads.fail(claim, "bounded failure"))
                val failed = download(original)
                val deletion = async {
                    runtime.downloads.deleteAttempt(failed) { cleanup ->
                        assertEquals(claim.token, cleanup.token)
                        assertTrue(runtime.ownership.requestStopIfProducing(cleanup) {
                            stops++
                            stopRequested.complete(Unit)
                        })
                    }
                }
                stopRequested.await()
                assertEquals(1, stops)
                assertFalse(deletion.isCompleted)
                assertTrue(fs.exists(page))
                assertEquals(failed, download(original))
                release.complete(Unit)
                producer.await()
                assertTrue(deletion.await())
                assertFalse(fs.exists(page))
                assertNull(dao.getDownloadByChapter(original.saved.id))
                assertFalse(runtime.ownership.requestStopIfProducing(claim) { stops++ })
                assertEquals(1, stops)
            } finally {
                release.complete(Unit)
            }
        }
    }
}
