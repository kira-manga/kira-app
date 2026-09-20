package me.manga.kira.presentation.features.download.ui.test2

import androidx.work.ListenableWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.presentation.features.download.data.DownloadState
import me.manga.kira.presentation.features.download.domain.clean.DownloadHttpStatusFailure
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual worker/service and existing generated Android Room fixture; no live HTTP/WorkManager claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DownloadLocaleTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidDownloadChallengeTest {
    @Test
    fun workerCatalogRefusalDoesNotCaptureQueueOrInvokeService() = challengeCase {
        rows.catalogAdmission.refuse = true
        assertEquals(ListenableWorker.Result.failure(), runWorker(), "Refusal must not request a worker retry")
        assertEquals(1, rows.catalogAdmission.preparations.get())
        assertEquals(1, rows.catalogAdmission.attempts.get(), "No preparation/admission scheduling loop")
        assertEquals(0, workerQueueReads.get(), "Admission must precede the first queued-row capture")
        assertEquals(0, workerServiceResolutions.get(), "The worker must not even resolve its download service")
        assertEquals(0, requests.get())
        assertEquals(rows.original.download, rows.download(), "Keep the full QUEUED ledger, including prior error/progress")
        assertEquals(rows.original.saved, rows.saved())
        assertNull(rows.artifacts.ownership.currentClaim(rows.original.saved.id))
        assertTrue(storage.mangaDirectory.walkTopDown().none { it.isFile })
        rows.operations.withExclusive {} // Refusal must release its own operation without creating a claim.
    }

    @Test
    fun workerPreparesOnceWhileEachQueuedScanNeedsAdmission() = challengeCase {
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertFailed(CHALLENGE)
        assertEquals(1, rows.catalogAdmission.preparations.get())
        assertEquals(2, rows.catalogAdmission.attempts.get(), "The processed row and final empty scan each require admission")
        assertEquals(2, workerQueueReads.get())
        assertEquals(1, workerServiceResolutions.get())
        assertEquals(1, requests.get())
    }

    @Test
    fun workerClassifiesResolveFailuresAndPreservesOrdinaryMessages() = challengeCase {
        // RegistryChapterPageProviderTest separately pins the real generic provider to this interface.
        val failures = listOf(
            ResolveHttpFailure(403, "source request rejected") to CHALLENGE,
            ResolveHttpFailure(404, "source /cloudflare/statusCode=403 was not found") to
                "source /cloudflare/statusCode=403 was not found",
            Exception("generic pages() failed: Http(statusCode=403, cause=null)") to CHALLENGE,
            Exception("No images for chapter") to "No images for chapter",
        )
        for ((failure, expected) in failures) {
            resetRow()
            assertEquals(ListenableWorker.Result.success(), runWorker(failure))
            assertFailed(expected)
        }
        assertEquals(0, requests.get(), "resolution failure must not enter page transport")
    }

    @Test
    fun servicePersistsHttpChallengesAndWorkerCannotOverwriteThemWithRawText() = challengeCase {
        for (status in listOf(403, 429, 503, 520, 521, 522, 523, 524, 400, 401, 404, 500)) {
            responseStatus.set(status)
            val expected = if (status in listOf(400, 401, 404, 500)) "Image download HTTP $status" else CHALLENGE

            resetRow()
            val states = downloadService()
            val error = assertIs<DownloadState.Error>(states.last())
            assertEquals(status, assertIs<DownloadHttpStatusFailure>(error.exception).httpStatusCode)
            assertEquals("Image download HTTP $status", error.exception.message)
            assertTrue(states.none { it is DownloadState.Complete })
            assertFailed(expected) // Observe the service's own write, before any worker can repair it.

            resetRow()
            assertEquals(ListenableWorker.Result.success(), runWorker())
            assertFailed(expected) // Actual service → Error collection → worker's token-guarded failure handling.
        }
        assertEquals(24, requests.get())
    }

    @Test
    fun resolveAndTransferCancellationNeverPersistAChallengeOrOrdinaryFailure() = challengeCase {
        val cancelled = CancellationException("HTTP 403 Cloudflare")
        assertFailsWith<CancellationException> { runWorker(cancelled) }
        assertEquals(rows.original.download, rows.download())
        assertEquals(0, requests.get())

        transportCancellation.set(cancelled)
        val states = mutableListOf<DownloadState>()
        assertFailsWith<CancellationException> { downloadService(states) }
        assertTrue(states.none { it is DownloadState.Error || it is DownloadState.Complete })
        assertEquals(rows.original.download, rows.download())

        assertFailsWith<CancellationException> { runWorker() }
        assertEquals(rows.original.download, rows.download(), "worker cancellation requeues, never stamps a challenge")
        assertEquals(rows.original.saved, rows.saved())
    }
}

private fun challengeCase(block: suspend AndroidChallengeCase.() -> Unit) = runBlocking {
    check(GlobalContext.getOrNull() == null) { "Refusing to replace another test's global Koin" }
    CancellationFixtureStorage(RuntimeEnvironment.getApplication()).use { storage ->
        DownloadWorkerCancellationRows(storage, NativeCommitGate()).use { rows ->
            withTimeout(60_000) {
                rows.seed()
                storage.settings.setUseCbzFormat(false)
                AndroidChallengeCase(storage, rows).use { it.block() }
            }
        }
    }
}

private class ResolveHttpFailure(
    override val httpStatusCode: Int,
    message: String,
) : Exception(message), DownloadHttpStatusFailure

private const val CHALLENGE = DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL
