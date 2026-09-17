package me.manga.kira.di

import androidx.lifecycle.viewModelScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.repository.ReadOnlyComplaintActionRepository
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.ComplaintActionRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase
import me.manga.kira.presentation.complaint.ActionDialogMode
import me.manga.kira.presentation.complaint.ComplaintIntent
import me.manga.kira.presentation.complaint.ComplaintViewModel
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Connected domain/data/presentation fixture, not UI automation and not native/platform qualification. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintBackendGraphTest {
    @Test
    fun selectionIsDisabledBeforeConfigurationAndEveryResourceEvenWithAValidUrl() = runTest {
        var configReads = 0
        var allocations = 0
        val resources = trapGraphResources { allocations++ }
        val result = selectComplaintBackendCandidate {
            createComplaintBackendGraph({ configReads++; GRAPH_BASE }, resources)
        }
        assertIs<AppResult.Failure>(result)
        assertEquals(0, configReads)
        assertEquals(0, allocations)
    }

    @Test
    fun invalidEndpointRefusesBeforeStoreGeneratorAndEngineAllocation() = runTest {
        var configReads = 0
        var allocations = 0
        val result = createComplaintBackendGraph(
            { configReads++; "http://complaints.example.invalid" },
            trapGraphResources { allocations++ },
        )
        assertIs<AppResult.Failure>(result)
        assertEquals(1, configReads)
        assertEquals(0, allocations)
    }

    @Test
    fun isolatedKoinGraphConnectsActualOwnerUseCaseAndViewModelAndRetainsStaleRows() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ComplaintBackendGraphFixture(this)
        val graph = assertIs<AppResult.Success<ComplaintBackendGraph>>(createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources())).value
        val app = koinApplication { modules(graph.module()) }
        val vm = app.koin.get<ComplaintViewModel>()
        val release = CompletableDeferred<Unit>()
        try {
            runCurrent()
            val history = assertIs<ComplaintHistory.Backend>(vm.state.value.history)
            assertEquals("Synthetic connected subject", assertIs<ComplaintOwnerRow.Report>(history.items.single()).subject)
            assertFalse(vm.state.value.isLoading)
            assertTrue(vm.state.value.all.isEmpty())
            assertFalse(vm.state.value.legacyActionsAllowed)
            assertEquals(1, fixture.historyCalls)
            assertEquals(1, fixture.sessionCalls)
            assertSame(graph.history, app.koin.get<ComplaintListRepository>())
            assertIs<ReadOnlyComplaintActionRepository>(app.koin.get<ComplaintActionRepository>())
            fixture.historyHandler = {
                release.await()
                respond(
                    """{"type":"about:blank","title":"Service Unavailable","status":503}""",
                    HttpStatusCode.ServiceUnavailable, graphHeaders(HttpStatusCode.ServiceUnavailable),
                )
            }
            vm.submit(ComplaintIntent.OnRetry)
            runCurrent()
            assertTrue(vm.state.value.isLoading)
            assertSame(history, vm.state.value.history)
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.isStale)
            assertSame(history, vm.state.value.history)
            assertIs<AppError.Network.Http>(vm.state.value.error)
            assertNull(vm.state.value.error?.cause)
            assertEquals(0, fixture.credentials.writes)
            assertEquals(0, fixture.pending.writes)
            assertEquals(0, fixture.generations)
            assertFalse(fixture.events.any { it == "request:enrollment" })
        } finally {
            release.complete(Unit)
            vm.viewModelScope.cancel()
            app.close()
            graph.close()
            Dispatchers.resetMain()
        }
        assertEquals(listOf("close:history", "close:session", "close:enrollment"), fixture.events.filter { it.startsWith("close:") })
        assertTrue(fixture.owners.values.all { it.closed })
        assertIs<AppResult.Failure>(graph.history.loadUserComplaints())
    }

    @Test
    fun realOwnerEmptyResultIsNotUnavailabilityAndEveryBackendActionPortIsUnavailable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ComplaintBackendGraphFixture(this)
        fixture.historyHandler = { respond(graphHistoryResponse(empty = true), HttpStatusCode.OK, graphHeaders()) }
        val graph = assertIs<AppResult.Success<ComplaintBackendGraph>>(createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources())).value
        val app = koinApplication { modules(graph.module()) }
        val vm = app.koin.get<ComplaintViewModel>()
        try {
            runCurrent()
            assertTrue(assertIs<ComplaintHistory.Backend>(vm.state.value.history).items.isEmpty())
            assertNull(vm.state.value.error)
            val actions = app.koin.get<ComplaintActionRepository>()
            val injected = ComplaintSummary("legacy-fixture", "genuine-legacy-fixture-user", ComplaintType.TECHNICAL, "Subject", "Body", null, ComplaintStatus.OPEN)
            assertTrue(actions.replyToComplaint(injected, "body").isFailure)
            assertTrue(actions.editComplaint(injected, "subject", "body").isFailure)
            assertTrue(actions.deleteComplaint(injected.id).isFailure)
            vm.submit(ComplaintIntent.OnRowClick(injected))
            vm.submit(ComplaintIntent.OnSelectAction(ActionDialogMode.DELETE))
            vm.submit(ComplaintIntent.OnConfirmDelete)
            runCurrent()
            assertEquals(ActionDialogMode.NONE, vm.state.value.actionDialogMode)
            assertNull(vm.state.value.activeComplaint)
            assertEquals(1, fixture.historyCalls)
            assertEquals(0, fixture.credentials.writes)
            assertEquals(0, fixture.pending.writes)
            // Resolve the actual use case binding, not a fake presentation-only repository.
            assertIs<AppResult.Success<*>>(app.koin.get<ObserveUserComplaintsUseCase>()())
        } finally {
            vm.viewModelScope.cancel()
            app.close()
            graph.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun partialConstructionRetainsEveryOwnerAndUnwindsAllEvenIfOneCloseFails() = runTest {
        val fixture = ComplaintBackendGraphFixture(this)
        fixture.failAllocation = "history"
        fixture.failClose = "session"
        val result = createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources())
        val error = assertIs<AppResult.Failure>(result).error
        assertEquals("complaint_backend_cleanup_failed", assertIs<AppError.Unexpected>(error).message)
        assertNull(error.cause)
        assertEquals(listOf("close:session", "close:enrollment"), fixture.events.filter { it.startsWith("close:") })
        assertTrue(fixture.owners.values.all { it.closed })
        assertEquals(0, fixture.credentials.writes)
        assertEquals(0, fixture.pending.writes)
    }

    @Test
    fun borrowedEngineGetterFailureClosesAllAlreadyCreatedOwnersWithoutLeakingCause() = runTest {
        val fixture = ComplaintBackendGraphFixture(this)
        fixture.failEngineAccess = "session"
        val result = createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources())
        assertNull(assertIs<AppResult.Failure>(result).error.cause)
        assertEquals(listOf("close:history", "close:session", "close:enrollment"), fixture.events.filter { it.startsWith("close:") })
        assertTrue(fixture.owners.values.all { it.closed })
        assertEquals(0, fixture.historyCalls + fixture.sessionCalls + fixture.generations)
    }

    @Test
    fun normalGraphCloseIsIdempotentAttemptsEveryNativeOwnerAndReportsOnlyFixedFailure() = runTest {
        val fixture = ComplaintBackendGraphFixture(this)
        val graph = assertIs<AppResult.Success<ComplaintBackendGraph>>(createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources())).value
        fixture.failClose = "history"
        val failure = assertFailsWith<IllegalStateException> { graph.close() }
        assertEquals("Complaint backend graph close failed", failure.message)
        assertNull(failure.cause)
        assertEquals(listOf("close:history", "close:session", "close:enrollment"), fixture.events.filter { it.startsWith("close:") })
        graph.close()
        assertIs<AppResult.Failure>(graph.history.loadUserComplaints())
        assertEquals(0, fixture.credentials.writes + fixture.pending.writes)
        assertTrue(fixture.owners.values.all { it.closed })
    }
}

/** No fake stores or engines exist before admission; every resource factory is a counted trap. */
private fun trapGraphResources(onAllocation: () -> Unit): ComplaintBackendResources = ComplaintBackendResources(
    credentials = { onAllocation(); error("Credential allocation must not occur") },
    pending = { onAllocation(); error("Pending allocation must not occur") },
    generator = { onAllocation(); error("Generator allocation must not occur") },
    enrollmentEngine = { onAllocation(); error("Enrollment engine allocation must not occur") },
    sessionEngine = { onAllocation(); error("Session engine allocation must not occur") },
    historyEngine = { onAllocation(); error("History engine allocation must not occur") },
)
