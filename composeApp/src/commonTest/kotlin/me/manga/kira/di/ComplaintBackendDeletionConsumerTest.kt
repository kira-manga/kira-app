package me.manga.kira.di

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.ComplaintInstallationDeletionObservation
import me.manga.kira.domain.repository.ComplaintInstallationDeletionRepository
import me.manga.kira.domain.usecase.feedback.ComplaintInstallationActions
import me.manga.kira.navigation.routes.ComplaintBackendRequestOpening
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackDeletionState
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackIntent
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Two isolated composition proofs, using real owner/use cases/per-opening VM without shipping selection. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintBackendDeletionConsumerTest {
    @Test
    fun disabledSelectionAllocatesNothingAndFifthEngineCannotAliasAnyOtherOwner() {
        runTest {
            var allocations = 0
            val resources = trapGraphResources { allocations++ }
            val disabled =
                selectComplaintBackendCandidate {
                    createComplaintBackendGraph(
                        {
                            allocations++
                            GRAPH_BASE
                        },
                        resources,
                    )
                }
            assertIs<AppResult.Failure>(disabled)
            assertEquals(0, allocations)
            for (alias in listOf("enrollment", "session", "history", "mutation")) {
                val fixture = ComplaintBackendGraphFixture(this)
                val result = createComplaintBackendGraph({ GRAPH_BASE }, aliasDeletionResources(fixture, alias))
                assertIs<AppResult.Failure>(result)
                assertTrue(fixture.owners.values.all { it.closed })
                assertEquals(
                    listOf(alias, "mutation", "history", "session", "enrollment")
                        .distinct()
                        .map { "close:$it" },
                    fixture.events.filter { it.startsWith("close:") },
                )
                assertEquals(0, fixture.deletionKeyGenerations + fixture.reportIdentifierGenerations)
                assertEquals(0, fixture.historyCalls + fixture.sessionCalls + fixture.mutationCalls + fixture.deletionCalls)
                assertEquals(0, fixture.credentials.writes + fixture.pending.writes + fixture.generations)
            }
        }
    }

    @Test
    fun isolatedOwnerUseCasesAndPerOpeningVmConfirmAndContinueOnlyTheSameDurableDeletion() {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = ComplaintBackendGraphFixture(this)
            fixture.credentials.allowDeletionReplacement = true
            val bodies = mutableListOf<String>()
            val keys = mutableListOf<String?>()
            fixture.deletionHandler = { request ->
                assertEquals(InstallationCredentialState.DELETION_PENDING, fixture.credentials.record.state)
                assertEquals(GRAPH_DELETION_KEY, fixture.credentials.record.pendingDeletionKey)
                bodies += request.body.toByteArray().decodeToString()
                keys += request.headers[ComplaintDeletionTransportPolicy.IDEMPOTENCY_HEADER]
                val status = HttpStatusCode.ServiceUnavailable
                respond(GRAPH_DELETION_UNAVAILABLE, status, graphHeaders(status))
            }
            val graph =
                assertIs<AppResult.Success<ComplaintBackendGraph>>(
                    createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources()),
                ).value
            val app = koinApplication { modules(graph.module()) }
            val opening = ComplaintBackendRequestOpening(app.koin, SettingsFeedbackEntry.General)
            try {
                assertSame(graph.installationDeletion, app.koin.get<ComplaintInstallationDeletionRepository>())
                assertConfirmedGraphDeletion(opening, fixture)
                val actions = app.koin.get<ComplaintInstallationActions>()
                assertEquals(
                    ComplaintInstallationDeletionObservation.RemoteDeletionPending,
                    assertIs<AppResult.Success<*>>(actions.deletion.observe()).value,
                )
                opening.close()
                runCurrent()
                assertTrue(fixture.owners.values.none { it.closed })
                assertGraphDeletionReopening(app.koin, fixture)
                assertEquals(listOf(GRAPH_DELETION_KEY, GRAPH_DELETION_KEY), keys)
                assertEquals(bodies.first(), bodies.last())
                assertEquals(1, fixture.credentials.writes)
                assertEquals(0, fixture.pending.writes + fixture.generations + fixture.mutationCalls)
            } finally {
                opening.close()
                runCurrent()
                app.close()
                graph.close()
                Dispatchers.resetMain()
            }
            assertTrue(fixture.owners.values.all { it.closed })
        }
    }
}

private fun aliasDeletionResources(
    fixture: ComplaintBackendGraphFixture,
    alias: String,
): ComplaintBackendResources {
    val original = fixture.resources()
    return ComplaintBackendResources(
        original.credentials,
        original.pending,
        original.generator,
        ComplaintBackendEngineFactories(
            original.engines.enrollment,
            original.engines.session,
            original.engines.history,
            original.engines.mutation,
            deletion = { fixture.owners.getValue(alias) },
        ),
        original.inputs,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.assertConfirmedGraphDeletion(
    opening: ComplaintBackendRequestOpening,
    fixture: ComplaintBackendGraphFixture,
) {
    runCurrent()
    assertEquals(0, fixture.deletionKeyGenerations + fixture.reportIdentifierGenerations + fixture.reportMetadataReads)
    assertEquals(0, fixture.sessionCalls + fixture.historyCalls + fixture.deletionCalls)
    opening.viewModel.submit(SettingsFeedbackIntent.RequestRemoteDeletion)
    runCurrent()
    assertTrue(opening.viewModel.state.value.remoteConfirmationPending)
    assertEquals(0, fixture.deletionKeyGenerations + fixture.credentials.writes + fixture.pending.writes)
    opening.viewModel.submit(SettingsFeedbackIntent.ConfirmRecovery)
    runCurrent()
    assertEquals(0, fixture.sessionCalls + fixture.deletionCalls)
    opening.viewModel.submit(SettingsFeedbackIntent.ConfirmRemoteDeletion)
    runCurrent()
    assertIs<SettingsFeedbackDeletionState.Pending>(opening.viewModel.state.value.deletion)
    assertEquals(1, fixture.deletionCalls)
    assertEquals(1, fixture.sessionCalls)
    assertEquals(1, fixture.deletionKeyGenerations)
    assertEquals(0, fixture.historyCalls + fixture.reportIdentifierGenerations + fixture.reportMetadataReads)
    assertFalse(fixture.events.any { it == "request:enrollment" })
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.assertGraphDeletionReopening(
    candidate: Koin,
    fixture: ComplaintBackendGraphFixture,
) {
    val reopened =
        ComplaintBackendRequestOpening(candidate, SettingsFeedbackEntry.SourceRequest("Synthetic fixed subject"))
    try {
        runCurrent()
        assertIs<SettingsFeedbackDeletionState.Pending>(reopened.viewModel.state.value.deletion)
        assertEquals(1, fixture.deletionCalls)
        reopened.viewModel.submit(SettingsFeedbackIntent.Submit)
        reopened.viewModel.submit(SettingsFeedbackIntent.SetupHistory)
        reopened.viewModel.submit(SettingsFeedbackIntent.RefreshRecovery)
        runCurrent()
        assertEquals(0, fixture.reportIdentifierGenerations + fixture.historyCalls + fixture.mutationCalls)
        assertEquals(1, fixture.deletionCalls)
        reopened.viewModel.submit(SettingsFeedbackIntent.ContinueRemoteDeletion)
        runCurrent()
        assertIs<SettingsFeedbackDeletionState.Pending>(reopened.viewModel.state.value.deletion)
        assertEquals(2, fixture.deletionCalls)
        assertEquals(1, fixture.sessionCalls)
        assertEquals(1, fixture.deletionKeyGenerations)
    } finally {
        reopened.close()
        runCurrent()
    }
}

private const val GRAPH_DELETION_UNAVAILABLE =
    """{"type":"about:blank","title":"Service Unavailable","status":503,"code":"SERVICE_UNAVAILABLE"}"""
