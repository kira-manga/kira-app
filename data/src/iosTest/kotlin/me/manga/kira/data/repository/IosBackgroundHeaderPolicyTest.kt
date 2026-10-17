package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.platform.download.TransferRequest
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.domain.clean.BackgroundUrlSessionDownloadRepository
import me.manga.kira.presentation.features.download.domain.clean.DownloadHeaderPolicy
import me.manga.kira.presentation.features.download.domain.clean.TransferRetryRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real repository, Room and private files; the captured transport is not native URLSession proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class IosBackgroundHeaderPolicyTest {
    @Test
    fun retainedRetryAndPageRetryHonorOptOutWithoutResolvingOrWideningFrozenHeaders() = runTest {
        withHeaderScenario(
            headerFreePage = true,
            initialPolicy = DownloadHeaderPolicy(staticHeaders = CURRENT_STATIC),
            initialCaptures = mapOf("Cookie" to "fresh-but-forbidden", "X-Captured" to "forbidden"),
        ) {
            val initial = retryRetainedOptOut()
            retryPageOptOut(initial)
        }
    }

    @Test
    fun policyIsRecheckedAfterOwnershipAdmissionForBothHandoffsAndOptInStillRefreshes() = runTest {
        withHeaderScenario(
            headerFreePage = false,
            initialPolicy = DownloadHeaderPolicy(usesCapturedHeaders = true, staticHeaders = CURRENT_STATIC),
            initialCaptures = mapOf("Cookie" to "first-fresh-cookie"),
            gated = true,
        ) {
            val initial = optOutDuringInitialAdmission()
            val refreshed = optedInPageRetry(initial)
            optOutDuringRetryAdmission(initial, refreshed)
        }
    }
}

private suspend fun TestScope.withHeaderScenario(
    headerFreePage: Boolean,
    initialPolicy: DownloadHeaderPolicy,
    initialCaptures: Map<String, String>,
    gated: Boolean = false,
    block: suspend HeaderPolicyScenario.() -> Unit,
) {
    val fixture = IosCbzFinalizationFixture()
    val host = SupervisorJob(coroutineContext[Job])
    try {
        val retained = fixture.retainedHeaderCase(headerFreePage)
        val scenario = HeaderPolicyScenario(this, fixture, host, retained, gated)
        try {
            scenario.start(initialPolicy, initialCaptures)
            scenario.block()
        } finally {
            scenario.releaseHolds()
        }
    } finally {
        closeHeaderScenario(fixture, host)
    }
}

private suspend fun closeHeaderScenario(fixture: IosCbzFinalizationFixture, host: Job) = withContext(NonCancellable) {
    try {
        host.cancelAndJoin()
        fixture.operations.withExclusive { }
    } finally {
        fixture.close()
    }
}

private data class HeaderAttempt(
    val requests: List<TransferRequest>,
    val rowId: Long,
    val claim: ChapterArtifactClaim? = null,
)

/** One scenario's collaborators and phase state; no additional artifact owner or fake admission. */
@OptIn(ExperimentalCoroutinesApi::class)
private class HeaderPolicyScenario(
    private val clock: TestScope,
    private val fixture: IosCbzFinalizationFixture,
    host: Job,
    private val retained: RetainedHeaderCase,
    admissionHolds: Boolean,
) {
    private val scope = CoroutineScope(clock.coroutineContext + host)
    private val initialHold = HeaderAdmissionHold()
    private val retryHold = HeaderAdmissionHold()
    // Setup is settled and Room was reopened; only the chosen runtime owns live work.
    private val gated = if (admissionHolds) HeaderAdmissionDao(fixture.db.chapterArtifactDao()) else null
    private val runtime = gated?.let {
        ArtifactTestRuntime(it, fixture.db.chapterArtifactCommitDao(), fixture.appFileSystem,
            IosPageMediaInspector(system = fixture.system))
    }
    private val settings = HeaderReadSettings()
    private val dataStore = DataStoreHelper(settings)
    private val recorded = ArtifactTestTransport(fixture.operations, ready = true)
    private val transport = HeaderPageTransport(recorded)
    private val catalog = IosCatalogAdmissionProbe(fixture.operations, ready = false)
    private val allowed = DownloadHeaderPolicy(usesCapturedHeaders = true, staticHeaders = CURRENT_STATIC)
    private val denied = DownloadHeaderPolicy(staticHeaders = CURRENT_STATIC)
    private lateinit var provider: RetainedHeaderProvider
    private lateinit var engine: BackgroundUrlSessionDownloadRepository

    suspend fun start(initialPolicy: DownloadHeaderPolicy, captures: Map<String, String>) {
        provider = RetainedHeaderProvider(initialPolicy)
        dataStore.saveHeadersForApi("test", captures)
        engine = fixture.engine(scope, transport, downloadArtifacts = runtime?.downloads ?: fixture.artifacts,
            pageProvider = provider, dataStore = dataStore, catalog = catalog.admission)
        catalog.assertParked(clock) // Drain startup; only the explicit call/failed-page delay can pump.
        catalog.ready = true
    }

    suspend fun retryRetainedOptOut(): HeaderAttempt {
        assertTrue(engine.retryChapterDownload(retained.failed))
        val requests = List(2) { recorded.requests.receive() }
        assertEquals(listOf(1, 2), requests.map { it.pageIndex }, "Verified page 0 must not be re-enqueued")
        assertEquals(ONLY_REFERER, requests[0].headers)
        assertEquals(emptyMap(), requests[1].headers, "Do not inject the source's static credentials into a header-free page")
        assertEquals(0, settings.captureReads)
        assertEquals(0, provider.resolutions)
        val token = requests.first().attemptToken
        requests.forEach { retained.assertRequestIdentity(it, token) }
        val replacementId = fixture.assertRetainedHeaderCase(retained, token, pageFailures = 0)
        assertFalse(engine.retryChapterDownload(retained.failed), "An old FAILED row is not another admission")
        return HeaderAttempt(requests, replacementId)
    }

    suspend fun retryPageOptOut(initial: HeaderAttempt) {
        val requests = initial.requests
        val token = requests.first().attemptToken
        failAndAdvance(requests[0], attempt = 1)
        val retry = recorded.requests.receive()
        assertEquals(requests[0], retry, "Only headers may be filtered; URL/page/source-attempt identity is retained")
        assertEquals(0, settings.captureReads, "The delayed retry must not consult captures either")
        assertEquals(0, provider.resolutions)
        assertEquals(initial.rowId, fixture.assertRetainedHeaderCase(retained, token, pageFailures = 1))
        assertEquals(requests + retry, recorded.enqueued)
    }

    suspend fun optOutDuringInitialAdmission(): HeaderAttempt {
        // Arm only after the capture read: earlier custody queries must not consume the hold.
        settings.onNextCaptureRead { checkNotNull(gated).arm(initialHold) }
        val starting = scope.async { engine.retryChapterDownload(retained.failed) }
        val initialClaim = initialHold.entered.await()
        assertEquals(1, settings.captureReads)
        assertTrue(recorded.enqueued.isEmpty())
        provider.policy = denied
        initialHold.release.complete(Unit)
        assertTrue(starting.await())
        val initial = recorded.requests.receive()
        assertEquals(ONLY_REFERER, initial.headers, "Initial reconcile must apply policy after publish admission")
        assertEquals(initialClaim.token, initial.attemptToken)
        retained.assertRequestIdentity(initial, initialClaim.token)
        val replacementId = fixture.assertRetainedHeaderCase(retained, initial.attemptToken, pageFailures = 0)
        return HeaderAttempt(listOf(initial), replacementId, initialClaim)
    }

    suspend fun optedInPageRetry(initial: HeaderAttempt): TransferRequest {
        // Positive control through the real delayed-retry path, not just the pure overlay helper.
        provider.policy = allowed
        val fresh = mapOf("Cookie" to "second-fresh-cookie", "User-Agent" to "fresh-agent", "X-Captured" to "fresh-capture")
        dataStore.saveHeadersForApi("test", fresh)
        failAndAdvance(initial.requests.single(), attempt = 1)
        val refreshed = recorded.requests.receive()
        assertEquals(initial.requests.single().copy(headers = FROZEN_HEADERS + fresh), refreshed)
        assertEquals(2, settings.captureReads)
        return refreshed
    }

    suspend fun optOutDuringRetryAdmission(attempt: HeaderAttempt, refreshed: TransferRequest) {
        val initial = attempt.requests.single()
        dataStore.saveHeadersForApi("test", mapOf("Cookie" to "third-fresh-cookie"))
        settings.onNextCaptureRead { checkNotNull(gated).arm(retryHold) }
        failAndAdvance(refreshed, attempt = 2)
        val retryClaim = retryHold.entered.await()
        assertEquals(attempt.claim, retryClaim, "Page retry must retain custody, not reserve a replacement")
        assertEquals(3, settings.captureReads)
        assertEquals(listOf(initial, refreshed), recorded.enqueued, "No request before admission is released")
        provider.policy = denied
        retryHold.release.complete(Unit)
        val stripped = recorded.requests.receive()
        assertEquals(initial, stripped, "Delayed retry must also discard captures read before opt-out")
        assertEquals(attempt.rowId, fixture.assertRetainedHeaderCase(retained, initial.attemptToken, pageFailures = 2))
        assertEquals(0, provider.resolutions)
        assertEquals(listOf(initial, refreshed, stripped), recorded.enqueued)
    }

    private suspend fun failAndAdvance(request: TransferRequest, attempt: Int) {
        transport.finish(request)
        val acknowledged = CompletableDeferred<Unit>()
        fixture.operations.withOperation { operation ->
            recorded.receiver.onPageFailed(request.mangaId, request.chapterId, request.pageIndex,
                request.attemptToken, "HTTP 500", operation) { acknowledged.complete(Unit) }
        }
        acknowledged.await()
        clock.runCurrent() // Start the real delayed job before advancing its bounded backoff.
        clock.advanceTimeBy(TransferRetryRules.backoffMs(attempt))
        clock.runCurrent()
    }

    fun releaseHolds() {
        initialHold.release.complete(Unit)
        retryHold.release.complete(Unit)
    }
}
