package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.platform.storage.CredentialCleanupReason
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationMaterialGenerationResult
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationTemporaryFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

@OptIn(ExperimentalCoroutinesApi::class)
class BackendComplaintHistoryRepositoryTest {
    @Test
    fun retainedPreparedAndDispatchedPendingStillAllowsActualOwnerReadsWithoutWrites() =
        runTest {
            for (dispatched in listOf(false, true)) {
                val fixture = ComplaintHistoryFixture(this)
                val slot = sessionPendingSlot(dispatched = dispatched)
                fixture.storage.pending.slots += slot
                try {
                    assertRefused(Block.RECONCILIATION_REQUIRED, fixture.storage.coordinator.admit())
                    assertIs<ComplaintHistory.Backend>(
                        assertIs<AppResult.Success<*>>(fixture.repository.loadUserComplaints()).value,
                    )
                    assertEquals(1, fixture.historyRequests.size)
                    assertTrue(fixture.enrollment.requests.isEmpty())
                    assertTrue(
                        fixture.enrollment.generator.scopes
                            .isEmpty(),
                    )
                    fixture.assertPreserved(slots = listOf(slot))
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun missingIdentityUsesAcceptedBootstrapDurableEnrollmentThenRealSessionAndHistory() =
        runTest {
            val fixture = ComplaintHistoryFixture(this, storage = InstallationCoordinatorFixture())
            try {
                val history =
                    assertIs<ComplaintHistory.Backend>(
                        assertIs<AppResult.Success<*>>(fixture.repository.loadUserComplaints()).value,
                    )
                assertTrue(history.items.isEmpty())
                assertEquals(listOf(Fixtures.SCOPE), fixture.enrollment.generator.scopes)
                assertEquals(listOf("GET", "POST"), fixture.enrollment.requests.map { it.method.value })
                assertEquals(1, fixture.sessionRequests.size)
                assertEquals(1, fixture.historyRequests.size)
                assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(Fixtures.record()))
                assertEquals(
                    listOf(InstallationStoreStep.CREATE_BEFORE, InstallationStoreStep.CREATE_STORED),
                    fixture.storage.faults.mutations,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun session404RetriesOnlyTheSameStoredIdentityWhileRejectionNeverRegenerates() =
        runTest {
            var calls = 0
            val fixture =
                ComplaintHistoryFixture(this, sessionHandler = {
                    calls++
                    if (calls == 1) {
                        respond(
                            historyInstallationNotFoundProblem(),
                            HttpStatusCode.NotFound,
                            sessionHeaders(HttpStatusCode.NotFound),
                        )
                    } else {
                        respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
                    }
                })
            try {
                assertIs<AppResult.Success<*>>(fixture.repository.loadUserComplaints())
                val enrollment = fixture.enrollment.requests.single()
                assertEquals("POST", enrollment.method.value)
                assertTrue(
                    enrollment.body
                        .toByteArray()
                        .decodeToString()
                        .contains(Fixtures.ID),
                )
                assertTrue(
                    fixture.enrollment.generator.scopes
                        .isEmpty(),
                )
                assertEquals(2, fixture.sessionRequests.size)
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
            val neverClaimed = historyInstallationNotFoundProblem()
            val errors =
                listOf(
                    historyProblem(HttpStatusCode.NotFound),
                    "{malformed",
                    neverClaimed.replace("INSTALLATION_NOT_FOUND", "NOT_FOUND"),
                    neverClaimed.replace("INSTALLATION_NOT_FOUND", "FUTURE_CODE"),
                    neverClaimed.replace("Not Found", "Unavailable"),
                    neverClaimed.replace("404", "401"),
                    neverClaimed.replace("\"status\":404", "\"status\":404,\"status\":404"),
                    neverClaimed.replace("\"code\":", "\"code\":\"NOT_FOUND\",\"code\":"),
                    neverClaimed.replace("}]", "},{\"code\":\"NOT_FOUND\",\"message\":\"Not found.\"}]"),
                    """{"type":"about:blank","title":"Not Found","status":404,"code":"INSTALLATION_NOT_FOUND"}""",
                )
            for (body in errors) assertSessionFailureCannotEnroll(HttpStatusCode.NotFound, body)
            assertSessionFailureCannotEnroll(HttpStatusCode.Unauthorized, historyProblem(HttpStatusCode.Unauthorized))
            for (dispatched in listOf(false, true)) {
                assertSessionFailureCannotEnroll(HttpStatusCode.NotFound, neverClaimed, dispatched)
            }
        }

    @Test
    fun initialSessionAndMissingAdmissionsCannotOutliveLifecycleOrBindingChanges() =
        runTest {
            val changes = listOf("consent", "reset", "deletion", "identity", "generation", "scope", "pending", "close")
            for (change in changes) {
                assertDelayedNotFoundCannotEnroll(change)
            }
            assertCancelledMissingCannotEnroll()
        }

    @Test
    fun checkedCleanupResumesBeforeNewEnrollmentButCorruptOrLockedEvidenceNeverBecomesMissing() =
        runTest {
            val resetting = Fixtures.record(generation = 2, state = InstallationCredentialState.LOCAL_RESET_PENDING)
            val storage = InstallationCoordinatorFixture(resetting)
            storage.credentials.marker = Fixtures.marker(2, CredentialCleanupReason.USER_RESET_CONFIRMED)
            val fixture = ComplaintHistoryFixture(this, storage = storage)
            fixture.enrollment.generator.result =
                InstallationMaterialGenerationResult.Generated(Fixtures.material(id = Fixtures.OTHER_ID))
            try {
                assertIs<AppResult.Success<*>>(fixture.repository.loadUserComplaints())
                val trace = storage.faults.trace
                assertTrue(
                    trace.indexOf(InstallationStoreStep.MARKER_REMOVED) <
                        trace.indexOf(InstallationStoreStep.CREATE_BEFORE),
                )
                assertEquals(
                    Fixtures.OTHER_ID,
                    assertNotNull(storage.credentials.payloadRecord).material.installationId,
                )
                assertNull(storage.credentials.marker)
            } finally {
                fixture.close()
            }
            val changes: List<(InstallationCoordinatorFixture) -> Unit> =
                listOf(
                    { it.pending.slots += Fixtures.slot(1) },
                    { it.pending.slots += sessionPendingSlot(Fixtures.record(generation = 2)) },
                    {
                        it.credentials.readFailure =
                            InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.LOCKED)
                    },
                )
            for (change in changes) {
                val blocked = ComplaintHistoryFixture(this)
                change(blocked.storage)
                try {
                    assertIs<AppResult.Failure>(blocked.repository.loadUserComplaints())
                    assertTrue(
                        blocked.sessionRequests.isEmpty() &&
                            blocked.historyRequests.isEmpty() &&
                            blocked.enrollment.requests.isEmpty(),
                    )
                    assertTrue(
                        blocked.storage.faults.mutations
                            .isEmpty(),
                    )
                    assertTrue(
                        blocked.enrollment.generator.scopes
                            .isEmpty(),
                    )
                } finally {
                    blocked.close()
                }
            }
        }

    @Test
    fun oneMatching401RefreshRetriesOnlyThatPageAndSecond401StopsTheWholeLoad() =
        runTest {
            for (secondUnauthorized in listOf(false, true)) {
                var calls = 0
                val fixture =
                    ComplaintHistoryFixture(this, historyHandler = {
                        calls++
                        when {
                            calls == 1 ->
                                respond(
                                    historyResponse(listOf(historyItem(2)), cursor = "v1.next.mac"),
                                    HttpStatusCode.OK,
                                    sessionHeaders(),
                                )
                            calls == 2 || secondUnauthorized ->
                                respond(
                                    historyProblem(HttpStatusCode.Unauthorized),
                                    HttpStatusCode.Unauthorized,
                                    sessionHeaders(HttpStatusCode.Unauthorized),
                                )
                            else ->
                                respond(historyResponse(listOf(historyItem(1))), HttpStatusCode.OK, sessionHeaders())
                        }
                    })
                try {
                    val result = fixture.repository.loadUserComplaints()
                    if (secondUnauthorized) {
                        assertEquals(
                            401,
                            assertIs<AppError.Network.Http>(assertIs<AppResult.Failure>(result).error).statusCode,
                        )
                    } else {
                        assertEquals(
                            2,
                            assertIs<ComplaintHistory.Backend>(assertIs<AppResult.Success<*>>(result).value).items.size,
                        )
                    }
                    assertEquals(3, fixture.historyRequests.size)
                    assertEquals(2, fixture.sessionRequests.size)
                    assertEquals(fixture.historyRequests[1].url, fixture.historyRequests[2].url)
                    fixture.assertPreserved()
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun refreshedBindingCannotCombineAlreadyReadPagesWithChangedPendingInventory() =
        runTest {
            var historyCalls = 0
            var sessionCalls = 0
            val storage = InstallationCoordinatorFixture(Fixtures.record())
            val slot = sessionPendingSlot()
            val fixture =
                ComplaintHistoryFixture(this, storage = storage, sessionHandler = {
                    if (++sessionCalls == 2) storage.pending.slots += slot
                    respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
                }, historyHandler = {
                    historyCalls++
                    if (historyCalls == 1) {
                        respond(
                            historyResponse(listOf(historyItem(2)), cursor = "v1.next.mac"),
                            HttpStatusCode.OK,
                            sessionHeaders(),
                        )
                    } else {
                        respond(
                            historyProblem(HttpStatusCode.Unauthorized),
                            HttpStatusCode.Unauthorized,
                            sessionHeaders(HttpStatusCode.Unauthorized),
                        )
                    }
                })
            try {
                assertIs<AppResult.Failure>(fixture.repository.loadUserComplaints())
                assertEquals(2, fixture.historyRequests.size)
                assertEquals(2, fixture.sessionRequests.size)
                fixture.assertPreserved(slots = listOf(slot))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun stale401CannotEvictNewerLeaseOrReopenClosedSessionOwner() =
        runTest {
            val fixture = ComplaintHistoryFixture(this)
            try {
                val old = assertIs<ComplaintHistorySessionResult.Ready>(fixture.sessions.historySession()).session
                fixture.sessions.invalidateHistorySession(old)
                val newer = assertIs<ComplaintHistorySessionResult.Ready>(fixture.sessions.historySession()).session
                assertNotSame(old, newer)
                fixture.sessions.invalidateHistorySession(old)
                assertTrue(fixture.sessions.historySessionIsCurrent(newer))
                assertEquals(2, fixture.sessionRequests.size)
                fixture.sessions.close()
                fixture.sessions.invalidateHistorySession(newer)
                assertIs<ComplaintHistorySessionResult.Failed>(fixture.sessions.historySession())
                assertEquals(2, fixture.sessionRequests.size)
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun sessionSuccessDoesNotAuthorizeLaterDispatchAfterConsentOrDeletion() =
        runTest {
            for (change in listOf("none", "consent", "deletion")) {
                val fixture = ComplaintHistoryFixture(this)
                val registered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                try {
                    val session =
                        assertIs<ComplaintHistorySessionResult.Ready>(fixture.sessions.historySession()).session
                    val permit =
                        fixture.storage.coordinator
                            .admit()
                            .success()
                    val load =
                        async {
                            fixture.loads.run { work ->
                                fixture.storage.coordinator
                                    .beginHistory(work)
                                    .success()
                                try {
                                    registered.complete(Unit)
                                    release.await()
                                    fixture.storage.coordinator.readHistoryPage(
                                        session,
                                        fixture.sessions,
                                        work,
                                        fixture.http,
                                        null,
                                    )
                                } finally {
                                    fixture.storage.coordinator.finishHistory(work)
                                }
                            }
                        }
                    registered.await()
                    if (change == "deletion") {
                        fixture.storage.coordinator
                            .beginDeletion(permit, Fixtures.KEY)
                            .success()
                    } else if (change == "consent") {
                        fixture.storage.coordinator
                            .requestRecovery(RecoveryIntent.Reset(permit))
                            .success()
                    }
                    release.complete(Unit)
                    if (change == "none") {
                        assertIs<AppResult.Success<*>>(load.await())
                        assertEquals(1, fixture.historyRequests.size)
                    } else {
                        assertFailsWith<CancellationException> { load.await() }
                        assertTrue(fixture.historyRequests.isEmpty())
                    }
                    load.join()
                } finally {
                    release.complete(Unit)
                    fixture.close()
                }
            }
        }

    @Test
    fun resetConsentAndDeletionProceedWhileHistoryIoIsPausedAndCancelLatePublication() =
        runTest {
            for (change in listOf("consent", "reset", "deletion")) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val fixture =
                    ComplaintHistoryFixture(this, historyHandler = {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        respond(historyResponse(listOf(historyItem())), HttpStatusCode.OK, sessionHeaders())
                    })
                try {
                    val load = async { fixture.repository.loadUserComplaints() }
                    entered.await()
                    val permit =
                        fixture.storage.coordinator
                            .admit()
                            .success()
                    if (change == "deletion") {
                        fixture.storage.coordinator
                            .beginDeletion(permit, Fixtures.KEY)
                            .success()
                    } else {
                        val prompt =
                            fixture.storage.coordinator
                                .requestRecovery(RecoveryIntent.Reset(permit))
                                .success()
                        if (change == "reset") {
                            fixture.storage.coordinator
                                .confirmRecovery(prompt)
                                .success()
                        } else {
                            fixture.storage.coordinator
                                .cancelRecovery(prompt)
                                .success()
                        }
                    }
                    // Reaching here before release proves new history I/O is not holding the credential mutex.
                    val mutations =
                        fixture.storage.faults.mutations
                            .toList()
                    release.complete(Unit)
                    assertFailsWith<CancellationException> { load.await() }
                    load.join()
                    assertEquals(mutations, fixture.storage.faults.mutations)
                    assertEquals(1, fixture.historyRequests.size)
                } finally {
                    release.complete(Unit)
                    fixture.close()
                }
            }
        }

    @Test
    fun changedGenerationScopeOrPendingDuringIoRejectsRatherThanPublishingAnyPrefix() =
        runTest {
            for (change in listOf("generation", "scope", "pending")) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val fixture =
                    ComplaintHistoryFixture(this, historyHandler = {
                        entered.complete(Unit)
                        release.await()
                        respond(historyResponse(listOf(historyItem())), HttpStatusCode.OK, sessionHeaders())
                    })
                try {
                    val load = async { fixture.repository.loadUserComplaints() }
                    entered.await()
                    when (change) {
                        "generation" -> fixture.storage.credentials.install(Fixtures.record(generation = 2))
                        "scope" ->
                            fixture.storage.credentials.install(
                                Fixtures.record(Fixtures.material(scope = Fixtures.OTHER_ID)),
                            )
                        else -> fixture.storage.pending.slots += sessionPendingSlot()
                    }
                    release.complete(Unit)
                    assertIs<AppResult.Failure>(load.await())
                    assertEquals(1, fixture.historyRequests.size)
                    assertTrue(
                        fixture.storage.faults.mutations
                            .isEmpty(),
                    )
                } finally {
                    release.complete(Unit)
                    fixture.close()
                }
            }
        }

    @Test
    fun replacementClosesPreviousLiveBodyBeforeAdmittingAnotherDecode() =
        runTest {
            val channel = HistoryTrackedChannel(ByteChannel(autoFlush = true))
            val entered = CompletableDeferred<Unit>()
            var calls = 0
            val fixture =
                ComplaintHistoryFixture(this, historyHandler = {
                    calls++
                    if (calls == 1) {
                        entered.complete(Unit)
                        respond(channel, HttpStatusCode.OK, sessionHeaders())
                    } else {
                        assertTrue(channel.cancelled, "replacement cannot overtake the real response finally")
                        respond(historyResponse(), HttpStatusCode.OK, sessionHeaders())
                    }
                })
            try {
                val old = async { fixture.repository.loadUserComplaints() }
                entered.await()
                runCurrent()
                val replacement = async { fixture.repository.loadUserComplaints() }
                assertIs<AppResult.Success<*>>(replacement.await())
                assertFailsWith<CancellationException> { old.await() }
                assertTrue(channel.cancelled)
                assertEquals(2, fixture.historyRequests.size)
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun closeDuringFinalDurableCheckFencesResultAndRefusesAnotherLoadWithoutClearingStores() =
        runTest {
            var checksAfterBody = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val body = HistoryTrackedChannel(ByteReadChannel(historyResponse()))
            val fixture =
                ComplaintHistoryFixture(this, historyHandler = {
                    respond(body, HttpStatusCode.OK, sessionHeaders())
                })
            fixture.storage.faults.onStep = { step ->
                // Page-result recheck is first; complete-load publication is second, after real body cleanup.
                if (step == InstallationStoreStep.PENDING_READ && body.cancelled && ++checksAfterBody == 2) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            try {
                val load = async { fixture.repository.loadUserComplaints() }
                entered.await()
                fixture.loads.close()
                fixture.sessions.close()
                fixture.http.close()
                release.complete(Unit)
                assertFailsWith<CancellationException> { load.await() }
                assertIs<AppResult.Failure>(fixture.repository.loadUserComplaints())
                assertEquals(1, fixture.historyRequests.size)
                fixture.assertPreserved()
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.assertSessionFailureCannotEnroll(
    status: HttpStatusCode,
    body: String,
    retainedDispatched: Boolean? = null,
) {
    val fixture = ComplaintHistoryFixture(this, sessionHandler = { respond(body, status, sessionHeaders(status)) })
    val slots = retainedDispatched?.let { listOf(sessionPendingSlot(dispatched = it)) }.orEmpty()
    fixture.storage.pending.slots += slots
    try {
        assertIs<AppResult.Failure>(fixture.repository.loadUserComplaints())
        assertEquals(1, fixture.sessionRequests.size)
        assertTrue(fixture.enrollment.requests.isEmpty())
        assertTrue(fixture.historyRequests.isEmpty())
        assertTrue(
            fixture.enrollment.generator.scopes
                .isEmpty(),
        )
        fixture.assertPreserved(slots = slots)
    } finally {
        fixture.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.assertDelayedNotFoundCannotEnroll(change: String) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val fixture =
        ComplaintHistoryFixture(this, sessionHandler = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            respond(
                historyInstallationNotFoundProblem(),
                HttpStatusCode.NotFound,
                sessionHeaders(HttpStatusCode.NotFound),
            )
        })
    try {
        val load = async { fixture.repository.loadUserComplaints() }
        entered.await()
        changeDelayedNotFoundBinding(fixture, change)
        val mutations =
            fixture.storage.faults.mutations
                .toList()
        val observed = fixture.storage.credentials.payloadRecord
        release.complete(Unit)
        if (change in setOf("consent", "reset", "deletion", "close")) {
            assertFailsWith<CancellationException> { load.await() }
        } else {
            assertIs<AppResult.Failure>(load.await())
        }
        load.join()
        assertEquals(mutations, fixture.storage.faults.mutations)
        assertTrue(
            if (observed == null) {
                fixture.storage.credentials.payloadRecord == null
            } else {
                assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(observed)
            },
        )
        assertEquals(1, fixture.sessionRequests.size)
        assertTrue(fixture.enrollment.requests.isEmpty())
        assertTrue(
            fixture.enrollment.generator.scopes
                .isEmpty(),
        )
        assertTrue(fixture.historyRequests.isEmpty())
        if (change == "reset") fixture.storage.assertAbsent()
    } finally {
        release.complete(Unit)
        fixture.close()
    }
}

private suspend fun changeDelayedNotFoundBinding(
    fixture: ComplaintHistoryFixture,
    change: String,
) {
    when (change) {
        "identity" ->
            fixture.storage.credentials.install(Fixtures.record(Fixtures.material(id = Fixtures.OTHER_ID)))
        "generation" -> fixture.storage.credentials.install(Fixtures.record(generation = 2))
        "scope" ->
            fixture.storage.credentials.install(Fixtures.record(Fixtures.material(scope = Fixtures.OTHER_ID)))
        "pending" -> fixture.storage.pending.slots += sessionPendingSlot()
        "close" -> fixture.loads.close()
        else -> {
            val permit =
                fixture.storage.coordinator
                    .admit()
                    .success()
            if (change == "deletion") {
                fixture.storage.coordinator
                    .beginDeletion(permit, Fixtures.KEY)
                    .success()
            } else {
                val prompt =
                    fixture.storage.coordinator
                        .requestRecovery(RecoveryIntent.Reset(permit))
                        .success()
                if (change == "reset") {
                    fixture.storage.coordinator
                        .confirmRecovery(prompt)
                        .success()
                } else {
                    fixture.storage.coordinator
                        .cancelRecovery(prompt)
                        .success()
                }
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.assertCancelledMissingCannotEnroll() {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val fixture = ComplaintHistoryFixture(this, storage = InstallationCoordinatorFixture())
    var reads = 0
    fixture.storage.faults.onStep = { step ->
        // Startup read, captured Missing read, then the bound enrollment's still-Missing recheck.
        if (step == InstallationStoreStep.CREDENTIAL_READ && ++reads == 3) {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
    }
    try {
        val load = async { fixture.repository.loadUserComplaints() }
        entered.await()
        fixture.loads.close()
        release.complete(Unit)
        assertFailsWith<CancellationException> { load.await() }
        load.join()
        fixture.storage.assertAbsent()
        assertTrue(
            fixture.storage.faults.mutations
                .isEmpty(),
        )
        assertTrue(
            fixture.enrollment.generator.scopes
                .isEmpty(),
        )
        assertTrue(fixture.enrollment.requests.isEmpty())
        assertTrue(fixture.sessionRequests.isEmpty())
        assertTrue(fixture.historyRequests.isEmpty())
    } finally {
        release.complete(Unit)
        fixture.close()
    }
}
