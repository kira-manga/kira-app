package me.manga.kira.details

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.presentation.details.DetailsEffect
import me.manga.kira.presentation.features.download.ui.test2.DownloadLocaleTestApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Joined software boundary, not WebView automation, real HTTP or Android scheduler/device proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DownloadLocaleTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidDownloadChallengeRecoveryTest {
    @Test
    fun androidFailedRowReachesDetailsAndSolverReturnRetriesWithFreshStoredHeaders() = androidDetailsChallengeCase {
        runWorker()
        assertChallengeRow()
        val requestId = awaitSolver(1)
        val fetches = metadataFetches.value
        android.responseStatus.set(200)
        returnFromSolver(requestId, capturedHeaders("refreshed"), 1)
        assertEquals(fetches, metadataFetches.value, "a download challenge must not retry metadata")
        runWorker()
        awaitDownloadState(DownloadState.SUCCESS)
        assertTrue(android.rows.saved().isDownloaded)
        val paths = android.rows.saved().localImagePaths
        assertEquals(1, paths.size)
        android.storage.assertImages(paths)
        assertEquals(2, android.requests.get())
        assertEquals(listOf("cf_clearance=expired", "cf_clearance=refreshed"), android.requestHeaders.map { it[HttpHeaders.Cookie] })
        assertEquals(listOf("fixture-expired", "fixture-refreshed"), android.requestHeaders.map { it[HttpHeaders.UserAgent] })
        assertEquals(listOf(capturedHeaders("expired"), capturedHeaders("refreshed")), sourceRequests.map { it.headers })
        assertEquals(1, effects.value.filterIsInstance<DetailsEffect.SolveCloudflareChallenge>().size)
        assertTrue(effects.value.none { it is DetailsEffect.ShowError })
        assertNull(recoveryRequestId())
    }

    @Test
    fun actualAndroidQueuedAndFailedRetriesDoNotRefillTheDetailsSolverBudget() = androidDetailsChallengeCase {
        runWorker()
        repeat(2) { index ->
            val attempt = index + 1
            assertChallengeRow()
            returnFromSolver(awaitSolver(attempt), capturedHeaders("retry-$attempt"), attempt)
            runWorker()
        }
        awaitExhaustedSolver()
        assertChallengeRow()
        assertEquals(2, effects.value.filterIsInstance<DetailsEffect.SolveCloudflareChallenge>().size)
        assertEquals(listOf(AppError.Network.Http(403)), effects.value.filterIsInstance<DetailsEffect.ShowError>().map { it.error })
        assertEquals(2, queue.enqueued.value.size)
        assertEquals(3, android.requests.get())
        assertEquals("cf_clearance=retry-2", android.requestHeaders.last()[HttpHeaders.Cookie])
        assertNull(recoveryRequestId())
    }

    @Test
    fun ordinaryAndroidFailureAndTransportCancellationDoNotRequestASolver() = androidDetailsChallengeCase {
        android.responseStatus.set(404)
        runWorker()
        awaitAbsentDownloadProgress()
        assertEquals(DownloadState.FAILED, mappedDownload().state)
        assertEquals("Image download HTTP 404", mappedDownload().errorMsg)
        assertNoSolver()

        android.resetRow()
        awaitDownloadState(DownloadState.QUEUED)
        android.transportCancellation.set(CancellationException("HTTP 403 Cloudflare"))
        assertFailsWith<CancellationException> { runWorker() }
        awaitDownloadState(DownloadState.QUEUED)
        assertEquals(android.rows.original.download, android.rows.download())
        assertEquals(android.rows.original.saved, android.rows.saved())
        assertNoSolver()
        assertTrue(queue.enqueued.value.isEmpty())
    }
}
