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
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.feedback.ComplaintReportDraft
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import me.manga.kira.domain.repository.ComplaintActionRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.repository.ComplaintReportRepository
import me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase
import me.manga.kira.domain.usecase.feedback.ComplaintReportActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportRecoveryActions
import me.manga.kira.presentation.complaint.ActionDialogMode
import me.manga.kira.presentation.complaint.ComplaintIntent
import me.manga.kira.presentation.complaint.ComplaintViewModel
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackViewModel
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Connected domain/data/presentation fixture, not UI automation and not native/platform qualification. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // Extend this existing graph fixture for the process selection boundary.
class ComplaintBackendGraphTest {
    @Test
    fun launchSelectionRefusesInvalidInputsDebugAndDesktopBeforeEveryResource() =
        runTest {
            val valid = graphLaunchInputs()
            fun record(value: String) = valid.copy(
                record = value,
                binding = valid.binding.copy(approvedRecordSha256 = complaintLaunchSha256(value)),
            )
            val invalid = listOf(
                valid.copy(record = ""), valid.copy(record = COMPLAINT_LAUNCH_DISABLED),
                record(valid.record + "\n"), record(valid.record.replace("contract=1", "contract=2")),
                record(valid.record.replace("mode=LIVE", "mode=TEST")),
                record(valid.record.replace("dataScopeId=$GRAPH_SCOPE", "dataScopeId=not-a-scope")),
                record(valid.record.replace("contract=1", "contract=1\ncontract=1")),
                record(valid.record.replace(GRAPH_BASE, "https://user:password@complaints.example.invalid"))
                    .copy(sourceBackend = "https://user:password@complaints.example.invalid"),
                valid.copy(sourceBackend = "$GRAPH_BASE/other"),
                valid.copy(binding = valid.binding.copy(approvedRecordSha256 = "")),
                valid.copy(binding = valid.binding.copy(approvedRecordSha256 = "d".repeat(64))),
                valid.copy(binding = valid.binding.copy(deploymentSha256 = "")),
                valid.copy(binding = valid.binding.copy(deploymentSha256 = "d".repeat(64))),
                valid.copy(binding = valid.binding.copy(buildInputsSha256 = "")),
                valid.copy(binding = valid.binding.copy(buildInputsSha256 = "d".repeat(64))),
                valid.copy(binding = valid.binding.copy(iosDefaultAccessGroup = "unexpected")),
            )
            val refusedHosts = listOf(
                graphLaunchRuntime().copy(isDebug = true),
                graphLaunchRuntime().copy(applicationId = "me.manga.kira.debug"),
                graphLaunchRuntime(ComplaintBackendPlatform.DESKTOP),
                graphLaunchRuntime(ComplaintBackendPlatform.IOS),
            )
            val cases = invalid.map { it to graphLaunchRuntime() } + refusedHosts.map { valid to it } + listOf(
                graphLaunchInputs(ComplaintBackendPlatform.IOS) to graphLaunchRuntime(ComplaintBackendPlatform.IOS).copy(isDebug = true),
            )
            for ((inputs, runtime) in cases) {
                val fixture = ComplaintBackendGraphFixture(this)
                var resourceReads = 0
                val app = koinApplication {
                    modules(fixture.hostModule(inputs, runtime) { resourceReads++; fixture.resources() })
                }
                try {
                    val host = app.koin.get<ComplaintBackendHostOwner>()
                    ComplaintBackendEntrypoint.entries.forEach { assertIs<AppResult.Failure>(host.candidate(it)) }
                    assertEquals(1, fixture.launchReads)
                    assertEquals(0, resourceReads)
                    assertTrue(fixture.events.isEmpty())
                    assertEquals(0, fixture.reportIdentifierGenerations + fixture.reportMetadataReads + fixture.generations)
                } finally {
                    app.close()
                }
            }
        }

    @Test
    fun iosLaunchRequiresIndependentExactDefaultGroupInputAndEvidence() =
        runTest {
            val valid = graphLaunchInputs(ComplaintBackendPlatform.IOS)
            val invalid = listOf(
                valid.copy(binding = valid.binding.copy(iosDefaultAccessGroup = "")),
                valid.copy(binding = valid.binding.copy(iosDefaultAccessGroup = "OTHER.me.manga.kira")),
                valid.copy(binding = valid.binding.copy(iosAccessGroupEvidenceSha256 = "")),
                valid.copy(binding = valid.binding.copy(iosAccessGroupEvidenceSha256 = "d".repeat(64))),
            )
            for (inputs in invalid + valid) {
                val fixture = ComplaintBackendGraphFixture(this)
                val app = koinApplication { modules(fixture.hostModule(inputs, graphLaunchRuntime(ComplaintBackendPlatform.IOS))) }
                try {
                    val selected = app.koin.get<ComplaintBackendHostOwner>().candidate(ComplaintBackendEntrypoint.COMPLAINT)
                    if (inputs === valid) {
                        assertIs<AppResult.Success<*>>(selected)
                        assertEquals(5, fixture.owners.size)
                    } else {
                        assertIs<AppResult.Failure>(selected)
                        assertTrue(fixture.events.isEmpty())
                    }
                } finally {
                    app.close()
                }
                assertTrue(fixture.owners.values.all { it.closed })
            }
        }

    @Test
    fun processHostUnwindsFailedStartupAndRetiresBeforeOnceOnlyCloseFailure() =
        runTest {
            val failed = ComplaintBackendGraphFixture(this).apply { failAllocation = "report-inputs" }
            val refused = koinApplication { modules(failed.hostModule()) }
            try {
                assertIs<AppResult.Failure>(refused.koin.get<ComplaintBackendHostOwner>().candidate(ComplaintBackendEntrypoint.SETTINGS))
            } finally {
                refused.close()
            }
            assertEquals(5, failed.events.count { it.startsWith("close:") })
            assertTrue(failed.owners.values.all { it.closed })

            val fixture = ComplaintBackendGraphFixture(this)
            val app = koinApplication { modules(fixture.hostModule()) }
            val host = app.koin.get<ComplaintBackendHostOwner>()
            val selected = assertIs<AppResult.Success<Koin>>(host.selection.value)
            val graph = selected.value.get<ComplaintBackendGraph>()
            fixture.failClose = "history"
            try {
                val failure = assertFailsWith<IllegalStateException> { host.close() }
                assertEquals("Complaint backend host close failed", failure.message)
                assertNull(failure.cause)
                host.close()
                assertIs<AppResult.Failure>(host.candidate(ComplaintBackendEntrypoint.COMPLAINT))
                assertTrue(fixture.owners.values.all { it.closed })
                assertEquals(5, fixture.events.count { it.startsWith("close:") })
                assertEquals(0, fixture.credentials.writes + fixture.pending.writes)
                assertFalse(graph.acceptsOpenings)
                assertIs<AppResult.Failure>(host.candidate(ComplaintBackendEntrypoint.COMPLAINT, selected))
            } finally {
                app.close()
            }
            assertEquals(5, fixture.events.count { it.startsWith("close:") })
        }

    @Test
    fun selectionIsDisabledBeforeConfigurationAndEveryResourceEvenWithAValidUrl() =
        runTest {
            var configReads = 0
            var allocations = 0
            val resources = trapGraphResources { allocations++ }
            val result =
                selectComplaintBackendCandidate {
                    createComplaintBackendGraph({
                        configReads++
                        GRAPH_BASE
                    }, resources)
                }
            assertIs<AppResult.Failure>(result)
            assertEquals(0, configReads)
            assertEquals(0, allocations)
        }

    @Test
    fun invalidEndpointRefusesBeforeStoreGeneratorAndEngineAllocation() =
        runTest {
            var configReads = 0
            var allocations = 0
            val result =
                createComplaintBackendGraph(
                    {
                        configReads++
                        "http://complaints.example.invalid"
                    },
                    trapGraphResources { allocations++ },
                )
            assertIs<AppResult.Failure>(result)
            assertEquals(1, configReads)
            assertEquals(0, allocations)
        }

    @Test
    fun isolatedKoinGraphConnectsActualOwnerUseCaseAndViewModelAndRetainsStaleRows() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = ComplaintBackendGraphFixture(this)
            val graph =
                assertIs<AppResult.Success<ComplaintBackendGraph>>(
                    createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources()),
                ).value
            val app = koinApplication { modules(graph.module()) }
            val vm = app.koin.get<ComplaintViewModel>()
            val release = CompletableDeferred<Unit>()
            try {
                runCurrent()
                val history = vm.assertLoadedReadOnlyHistory()
                assertEquals(1, fixture.historyCalls)
                assertEquals(1, fixture.sessionCalls)
                assertSame(graph.history, app.koin.get<ComplaintListRepository>())
                assertIs<ReadOnlyComplaintActionRepository>(app.koin.get<ComplaintActionRepository>())
                fixture.historyHandler = {
                    release.await()
                    respond(
                        """{"type":"about:blank","title":"Service Unavailable","status":503}""",
                        HttpStatusCode.ServiceUnavailable,
                        graphHeaders(HttpStatusCode.ServiceUnavailable),
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
                assertNull(
                    vm.state.value.error
                        ?.cause,
                )
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
            assertEquals(
                listOf("close:deletion", "close:mutation", "close:history", "close:session", "close:enrollment"),
                fixture.events.filter { it.startsWith("close:") },
            )
            assertTrue(fixture.owners.values.all { it.closed })
            assertIs<AppResult.Failure>(graph.history.loadUserComplaints())
        }

    @Test
    fun realOwnerEmptyResultIsNotUnavailabilityAndEveryBackendActionPortIsUnavailable() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = ComplaintBackendGraphFixture(this)
            fixture.historyHandler = { respond(graphHistoryResponse(empty = true), HttpStatusCode.OK, graphHeaders()) }
            val graph =
                assertIs<AppResult.Success<ComplaintBackendGraph>>(
                    createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources()),
                ).value
            val app = koinApplication { modules(graph.module()) }
            val vm = app.koin.get<ComplaintViewModel>()
            try {
                runCurrent()
                assertTrue(assertIs<ComplaintHistory.Backend>(vm.state.value.history).items.isEmpty())
                assertNull(vm.state.value.error)
                val actions = app.koin.get<ComplaintActionRepository>()
                val injected =
                    ComplaintSummary(
                        "legacy-fixture",
                        "genuine-legacy-fixture-user",
                        ComplaintType.TECHNICAL,
                        "Subject",
                        "Body",
                        null,
                        ComplaintStatus.OPEN,
                    )
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
    fun candidateReportBindingsPrepareOnlyThroughExplicitActionAndNeverPersistOrDispatchDraft() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = ComplaintBackendGraphFixture(this)
            val graph =
                assertIs<AppResult.Success<ComplaintBackendGraph>>(
                    createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources()),
                ).value
            val app = koinApplication { modules(graph.module()) }
            val vm = app.koin.get<SettingsFeedbackViewModel>()
            try {
                runCurrent()
                assertSame(graph.reports, app.koin.get<ComplaintReportRepository>())
                assertIs<ReadOnlyComplaintActionRepository>(app.koin.get<ComplaintActionRepository>())
                assertEquals(0, fixture.reportIdentifierGenerations + fixture.reportMetadataReads)
                val actions = app.koin.get<ComplaintReportActions>()
                val prepared =
                    assertIs<AppResult.Success<ComplaintReportPreparation>>(
                        actions.prepare(
                            ComplaintReportDraft(ComplaintType.TECHNICAL, "Synthetic subject", "Synthetic report body"),
                        ),
                    ).value
                assertIs<ComplaintReportPreparation.Ready>(prepared)
                assertIs<AppResult.Success<*>>(app.koin.get<ComplaintReportRecoveryActions>().reconcile())
                assertEquals(1, fixture.reportIdentifierGenerations)
                assertEquals(1, fixture.reportMetadataReads)
                assertEquals(0, fixture.credentials.writes + fixture.pending.writes)
                assertEquals(
                    0,
                    fixture.generations + fixture.mutationCalls + fixture.historyCalls + fixture.sessionCalls,
                )
                assertFalse(fixture.events.any { it == "request:enrollment" })
            } finally {
                vm.viewModelScope.cancel()
                app.close()
                graph.close()
                Dispatchers.resetMain()
            }
            assertTrue(fixture.owners.values.all { it.closed })
        }

    @Test
    fun partialConstructionRetainsEveryOwnerAndUnwindsAllEvenIfOneCloseFails() =
        runTest {
            val failures =
                listOf(
                    "history" to listOf("session", "enrollment"),
                    "mutation" to listOf("history", "session", "enrollment"),
                    "deletion" to listOf("mutation", "history", "session", "enrollment"),
                    "report-inputs" to listOf("deletion", "mutation", "history", "session", "enrollment"),
                )
            for ((stage, closed) in failures) {
                val fixture = ComplaintBackendGraphFixture(this)
                fixture.failAllocation = stage
                fixture.failClose = "session"
                val result = createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources())
                val error = assertIs<AppResult.Failure>(result).error
                assertEquals("complaint_backend_cleanup_failed", assertIs<AppError.Unexpected>(error).message)
                assertNull(error.cause)
                assertEquals(closed.map { "close:$it" }, fixture.events.filter { it.startsWith("close:") })
                assertTrue(fixture.owners.values.all { it.closed })
                assertEquals(0, fixture.credentials.writes + fixture.pending.writes)
                assertEquals(0, fixture.reportIdentifierGenerations + fixture.reportMetadataReads)
            }
        }

    @Test
    fun borrowedEngineGetterFailureClosesAllAlreadyCreatedOwnersWithoutLeakingCause() =
        runTest {
            for (stage in listOf("session", "mutation", "deletion")) {
                val fixture = ComplaintBackendGraphFixture(this)
                fixture.failEngineAccess = stage
                val result = createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources())
                assertNull(assertIs<AppResult.Failure>(result).error.cause)
                assertEquals(
                    listOf("close:deletion", "close:mutation", "close:history", "close:session", "close:enrollment"),
                    fixture.events.filter { it.startsWith("close:") },
                )
                assertTrue(fixture.owners.values.all { it.closed })
                assertEquals(
                    0,
                    fixture.historyCalls + fixture.sessionCalls + fixture.generations + fixture.mutationCalls,
                )
                assertEquals(0, fixture.reportIdentifierGenerations + fixture.reportMetadataReads)
            }
        }

    @Test
    fun normalGraphCloseIsIdempotentAttemptsEveryNativeOwnerAndReportsOnlyFixedFailure() =
        runTest {
            val fixture = ComplaintBackendGraphFixture(this)
            val graph =
                assertIs<AppResult.Success<ComplaintBackendGraph>>(
                    createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources()),
                ).value
            fixture.failClose = "mutation"
            val failure = assertFailsWith<IllegalStateException> { graph.close() }
            assertEquals("Complaint backend graph close failed", failure.message)
            assertNull(failure.cause)
            assertEquals(
                listOf("close:deletion", "close:mutation", "close:history", "close:session", "close:enrollment"),
                fixture.events.filter { it.startsWith("close:") },
            )
            graph.close()
            assertIs<AppResult.Failure>(graph.history.loadUserComplaints())
            assertEquals(0, fixture.credentials.writes + fixture.pending.writes)
            assertTrue(fixture.owners.values.all { it.closed })
        }
}
