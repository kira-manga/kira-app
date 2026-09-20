package me.manga.kira.di

import androidx.lifecycle.viewModelScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.ComplaintInstallationRecoveryRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.repository.FeedbackRepository
import me.manga.kira.navigation.routes.ActionHostCredentialReads
import me.manga.kira.navigation.routes.ComplaintBackendDetailOpening
import me.manga.kira.navigation.routes.ComplaintBackendRequestHostOwner
import me.manga.kira.navigation.routes.ComplaintBackendRequestOpening
import me.manga.kira.platform.storage.CleanupMarkerReadResult
import me.manga.kira.platform.storage.CredentialReplaceResult
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.InstallationValueResult
import me.manga.kira.presentation.complaint.ComplaintIntent
import me.manga.kira.presentation.complaint.ComplaintViewModel
import me.manga.kira.presentation.complaint.admin.AdminComplaintViewModel
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackDeletionState
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEffect
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackIntent
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackResult
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackViewModel
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Existing graph fixture plus ordinary lifecycle stores; no Compose automation or new transport fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // Existing fixtures cover the three added route ownership boundaries.
class ComplaintBackendRequestOwnershipTest {
    @Test
    fun processHostSharesOneGraphAndRefusesLegacyAdminAndRetiredEntrypoints() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = ComplaintBackendGraphFixture(this)
            var legacyResolutions = 0
            val legacy = module {
                factory<ComplaintViewModel> { legacyResolutions++; error("Global complaint VM must not resolve") }
                factory<AdminComplaintViewModel> { legacyResolutions++; error("Mobile admin VM must not resolve") }
                factory<SettingsFeedbackViewModel> { legacyResolutions++; error("Global feedback VM must not resolve") }
                single<ComplaintListRepository> { legacyResolutions++; error("Legacy history must not resolve") }
            }
            val app = koinApplication { modules(legacy, fixture.hostModule()) }
            // Eager startup has already selected/allocated, before the first route/host lookup.
            assertEquals(1, fixture.launchReads)
            assertEquals(5, fixture.owners.size)
            val process = app.koin.get<ComplaintBackendHostOwner>()
            val observed = assertIs<AppResult.Success<Koin>>(process.selection.value)
            val candidate = observed.value
            val graph = candidate.get<ComplaintBackendGraph>()
            val first = ComplaintBackendRequestHostOwner(candidate)
            val second = ComplaintBackendRequestHostOwner(candidate)
            val retiredProcessRoute = ComplaintBackendRequestHostOwner(candidate)
            try {
                assertSame(process, app.koin.get<ComplaintBackendHostOwner>())
                assertNull(app.koin.getOrNull<ComplaintBackendGraph>())
                for (entry in ComplaintBackendEntrypoint.entries) {
                    when (entry) {
                        ComplaintBackendEntrypoint.COMPLAINT_ADMIN, ComplaintBackendEntrypoint.COMPLAINT_ADMIN_REWORK ->
                            assertIs<AppResult.Failure>(process.candidate(entry, observed))
                        else -> assertSame(candidate, assertIs<AppResult.Success<Koin>>(process.candidate(entry, observed)).value)
                    }
                }
                first.request(SettingsFeedbackEntry.General)
                second.request(SettingsFeedbackEntry.LanguageRequest("Synthetic language subject"))
                val firstRequest = assertNotNull(first.requestOpening)
                val secondRequest = assertNotNull(second.requestOpening)
                assertNotSame(firstRequest.viewModel, secondRequest.viewModel)
                first.close()
                runCurrent()
                assertFalse(firstRequest.viewModel.viewModelScope.isActive)
                assertTrue(secondRequest.viewModel.viewModelScope.isActive)
                assertTrue(graph.acceptsOpenings && fixture.owners.values.none { it.closed })
                val detail = ComplaintBackendDetailOpening(candidate)
                try {
                    runCurrent()
                    assertIs<ComplaintHistory.Backend>(detail.history.state.value.history)
                    fixture.historyHandler = {
                        respond(
                            """{"type":"about:blank","title":"Service Unavailable","status":503}""",
                            HttpStatusCode.ServiceUnavailable,
                            graphHeaders(HttpStatusCode.ServiceUnavailable),
                        )
                    }
                    detail.history.submit(ComplaintIntent.OnRetry)
                    runCurrent()
                    assertNotNull(detail.history.state.value.error)
                    assertSame(candidate, assertIs<AppResult.Success<Koin>>(process.candidate(ComplaintBackendEntrypoint.COMPLAINT)).value)
                } finally {
                    detail.close()
                }
                second.close()
                runCurrent()
                assertEquals(0, legacyResolutions)
                assertNoReportWork(fixture)
                assertEquals(1, fixture.launchReads)
                val staleRequest = { retiredProcessRoute.request(SettingsFeedbackEntry.General) }
                val staleHistory = retiredProcessRoute::openHistory
                app.close()
                assertFalse(graph.acceptsOpenings)
                assertIs<AppResult.Failure>(process.candidate(ComplaintBackendEntrypoint.COMPLAINT, observed))
                val replacementFixture = ComplaintBackendGraphFixture(this)
                val replacementApp = koinApplication { modules(replacementFixture.hostModule()) }
                try {
                    val replacement = replacementApp.koin.get<ComplaintBackendHostOwner>()
                    assertNotSame(candidate, assertIs<AppResult.Success<Koin>>(replacement.selection.value).value)
                    staleRequest()
                    staleHistory()
                    assertNull(retiredProcessRoute.requestOpening)
                    assertNull(retiredProcessRoute.historyOpening)
                    assertEquals(0, replacementFixture.historyCalls + replacementFixture.sessionCalls)
                    assertEquals(5, fixture.events.count { it.startsWith("close:") })
                    assertEquals(0, legacyResolutions)
                } finally {
                    replacementApp.close()
                }
            } finally {
                first.close()
                second.close()
                retiredProcessRoute.close()
                runCurrent()
                app.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun processHostPendingDeletionCannotSendCredentialsToAnotherLaunchScope() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = ComplaintBackendGraphFixture(this)
                val retained = assertIs<InstallationValueResult.Valid<InstallationCredentialRecord>>(
                    fixture.credentials.record.beginDeletion(GRAPH_DELETION_KEY),
                ).value
                // Seed the old durable pending tuple before this process owner starts; no new consent or session.
                fixture.credentials.allowDeletionReplacement = true
                assertIs<CredentialReplaceResult.Stored>(
                    fixture.credentials.replace(fixture.credentials.record.localGeneration, retained),
                )
                fixture.credentials.allowDeletionReplacement = false
                val writesBeforeStartup = fixture.credentials.writes
                val launch = graphLaunchInputs()
                val record = launch.record.replace("mode=LIVE", "mode=TEST")
                    .replace("dataScopeId=$GRAPH_SCOPE", "dataScopeId=66666666-6666-4666-8666-666666666666")
                val inputs = launch.copy(
                    record = record,
                    binding = launch.binding.copy(approvedRecordSha256 = complaintLaunchSha256(record)),
                )
                val app = koinApplication { modules(fixture.hostModule(inputs)) }
                try {
                    val process = app.koin.get<ComplaintBackendHostOwner>()
                    val candidate = assertIs<AppResult.Success<Koin>>(
                        process.candidate(ComplaintBackendEntrypoint.SETTINGS),
                    ).value
                    val host = ComplaintBackendRequestHostOwner(candidate)
                    try {
                        host.request(SettingsFeedbackEntry.General)
                        val vm = assertNotNull(host.requestOpening).viewModel
                        runCurrent()
                        assertNull(assertIs<SettingsFeedbackDeletionState.Pending>(vm.state.value.deletion).error)
                        assertTrue(vm.state.value.canContinueRemoteDeletion)
                        assertEquals(1, fixture.launchReads)
                        assertTrue(fixture.events.none { it.startsWith("request:") })
                        repeat(2) {
                            vm.submit(SettingsFeedbackIntent.ContinueRemoteDeletion)
                            runCurrent()
                            val pending = assertIs<SettingsFeedbackDeletionState.Pending>(vm.state.value.deletion)
                            assertIs<AppError.Network.Serialization>(pending.error)
                            assertTrue(vm.state.value.canContinueRemoteDeletion)
                            assertSame(retained, fixture.credentials.record)
                            assertEquals(writesBeforeStartup, fixture.credentials.writes)
                            assertEquals(0, fixture.pending.writes + fixture.generations + fixture.deletionKeyGenerations)
                            assertEquals(0, fixture.reportIdentifierGenerations + fixture.reportMetadataReads)
                            assertEquals(CleanupMarkerReadResult.Missing, fixture.credentials.readCleanupMarker())
                            assertTrue(fixture.events.none { it.startsWith("request:") })
                        }
                    } finally {
                        host.close()
                        runCurrent()
                    }
                } finally {
                    app.close()
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun candidateHistoryUsesSameGraphAndRefusesDuplicateOrStaleOpenings() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = ComplaintBackendGraphFixture(this)
            val graph = candidateGraph(fixture)
            val app = koinApplication { modules(graph.module()) }
            val host = ComplaintBackendRequestHostOwner(app.koin)
            try {
                host.openHistory()
                val history = assertNotNull(host.historyOpening)
                assertSame(app.koin, history.candidate)
                host.openHistory()
                host.request(SettingsFeedbackEntry.General)
                assertSame(history, host.historyOpening)
                assertNull(host.requestOpening)
                val detail = ComplaintBackendDetailOpening(history.candidate)
                try {
                    runCurrent()
                    assertIs<ComplaintHistory.Backend>(detail.history.state.value.history)
                    assertEquals(1, fixture.historyCalls)
                    assertNoReportWork(fixture)
                } finally {
                    detail.close()
                    runCurrent()
                }
                host.historyClosed(history)
                assertNull(host.historyOpening)
                host.openHistory()
                val replacement = assertNotNull(host.historyOpening)
                assertNotSame(history, replacement)
                host.historyClosed(history)
                assertSame(replacement, host.historyOpening)
                assertTrue(fixture.owners.values.none { it.closed })
            } finally {
                host.close()
                runCurrent()
                app.close()
                graph.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun retiredRequestHostCannotOpenHistoryOrRetargetCallbacks() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = ComplaintBackendGraphFixture(this)
            val graph = candidateGraph(fixture)
            val app = koinApplication { modules(graph.module()) }
            try {
                val retirements = listOf(
                    ComplaintBackendRequestHostOwner::onForgotten,
                    ComplaintBackendRequestHostOwner::onAbandoned,
                    ComplaintBackendRequestHostOwner::close,
                )
                for (retire in retirements) {
                    val host = ComplaintBackendRequestHostOwner(app.koin)
                    val replacement = ComplaintBackendRequestHostOwner(app.koin)
                    try {
                        val oldRequest = { host.request(SettingsFeedbackEntry.General) }
                        val oldHistory = host::openHistory
                        oldRequest()
                        val request = assertNotNull(host.requestOpening)
                        oldRequest()
                        assertSame(request, host.requestOpening)
                        runCurrent()
                        retire(host)
                        runCurrent()
                        assertFalse(request.viewModel.viewModelScope.isActive)
                        replacement.openHistory()
                        val history = assertNotNull(replacement.historyOpening)
                        oldRequest()
                        oldHistory()
                        host.requestClosed(request)
                        host.historyClosed(history)
                        assertNull(host.requestOpening)
                        assertNull(host.historyOpening)
                        assertSame(history, replacement.historyOpening)
                        assertTrue(fixture.owners.values.none { it.closed })
                    } finally {
                        host.close()
                        replacement.close()
                        runCurrent()
                    }
                }
                assertEquals(0, fixture.historyCalls + fixture.sessionCalls)
                assertNoReportWork(fixture)
            } finally {
                app.close()
                graph.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun requestHostKeepsHistoryClosedUntilRealRequestChildDrains() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = ComplaintBackendGraphFixture(this)
            val credentials = ActionHostCredentialReads(fixture.credentials)
            val original = fixture.resources()
            val resources = ComplaintBackendResources(
                { credentials }, original.pending, original.generator, original.engines, original.inputs,
            )
            val app = koinApplication { modules(fixture.hostModule(resourceFactory = { resources })) }
            val process = app.koin.get<ComplaintBackendHostOwner>()
            val candidate = assertIs<AppResult.Success<Koin>>(process.candidate(ComplaintBackendEntrypoint.SETTINGS)).value
            val host = ComplaintBackendRequestHostOwner(candidate)
            try {
                host.request(SettingsFeedbackEntry.General)
                val request = assertNotNull(host.requestOpening)
                runCurrent()
                val hold = credentials.holdNext()
                request.viewModel.submit(SettingsFeedbackIntent.ResumeCleanup)
                runCurrent()
                val work = hold.entered.await()
                val closed = backgroundScope.launch {
                    request.viewModel.effects.first { it == SettingsFeedbackEffect.Closed }
                    host.requestClosed(request)
                }
                request.viewModel.submit(SettingsFeedbackIntent.Close)
                runCurrent()
                assertTrue(work.isCancelled && hold.closing.isCompleted)
                assertFalse(work.isCompleted || closed.isCompleted)
                val reads = credentials.reads
                host.openHistory()
                host.request(SettingsFeedbackEntry.General)
                assertSame(request, host.requestOpening)
                assertNull(host.historyOpening)
                assertEquals(reads, credentials.reads)
                hold.release.complete(Unit)
                runCurrent()
                assertTrue(work.isCompleted && closed.isCompleted)
                assertFalse(request.viewModel.viewModelScope.isActive)
                assertNull(host.requestOpening)
                host.openHistory()
                val history = assertNotNull(host.historyOpening)
                host.requestClosed(request)
                assertSame(history, host.historyOpening)
                assertEquals(0, fixture.historyCalls + fixture.sessionCalls)
                assertNoReportWork(fixture)
                assertTrue(fixture.owners.values.none { it.closed })
            } finally {
                credentials.releaseAll()
                host.close()
                runCurrent()
                app.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun fixedRequestsOwnSeparateViewModelStoresWithoutOwningTheSharedGraph() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = ComplaintBackendGraphFixture(this)
            val graph = candidateGraph(fixture)
            val app = koinApplication { modules(graph.module()) }
            val entry = SettingsFeedbackEntry.SourceRequest("Synthetic source request")
            val source = ComplaintBackendRequestOpening(app.koin, entry)
            val languageEntry = SettingsFeedbackEntry.LanguageRequest("Synthetic language request")
            val language = ComplaintBackendRequestOpening(app.koin, languageEntry)
            try {
                assertFixedOpenings(app.koin, entry, source, language)
                assertOnlySourceClosed(source, language)
                assertFreshSourceOpening(app.koin, source, entry)
                assertEquals(0, fixture.historyCalls + fixture.sessionCalls)
                assertNoReportWork(fixture)
                assertTrue(fixture.owners.values.none { it.closed })
            } finally {
                source.close()
                language.close()
                runCurrent()
                app.close()
                graph.close()
                Dispatchers.resetMain()
            }
            assertTrue(fixture.owners.values.all { it.closed })
        }

    @Test
    fun explicitSetupUsesTheSameOwnerHistoryAndLeavesTheFixedRequestUnsent() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = ComplaintBackendGraphFixture(this)
            fixture.credentials.missing = true
            val graph = candidateGraph(fixture)
            val app = koinApplication { modules(graph.module()) }
            val entry = SettingsFeedbackEntry.LanguageRequest("Synthetic language request")
            val opening = ComplaintBackendRequestOpening(app.koin, entry)
            try {
                assertMissingBeforeSetup(opening.viewModel, fixture)
                assertExplicitHistorySetup(opening.viewModel, fixture)
            } finally {
                opening.close()
                runCurrent()
                app.close()
                graph.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun installationRecoveryBindingUsesTheReportIssuerAndActiveCleanupIsNeutral() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = ComplaintBackendGraphFixture(this)
            val graph = candidateGraph(fixture)
            val app = koinApplication { modules(graph.module()) }
            val entry = SettingsFeedbackEntry.SourceRequest("Synthetic source request")
            val opening = ComplaintBackendRequestOpening(app.koin, entry)
            try {
                assertSame<Any>(graph.reports, graph.installationRecovery)
                assertSame(graph.installationRecovery, app.koin.get<ComplaintInstallationRecoveryRepository>())
                assertNeutralActiveCleanup(opening.viewModel, fixture)
            } finally {
                opening.close()
                runCurrent()
                app.close()
                graph.close()
                Dispatchers.resetMain()
            }
        }

    private fun TestScope.assertFixedOpenings(
        candidate: Koin,
        entry: SettingsFeedbackEntry,
        source: ComplaintBackendRequestOpening,
        language: ComplaintBackendRequestOpening,
    ) {
        runCurrent()
        assertNotSame(source.viewModel, language.viewModel)
        assertSame(entry, source.viewModel.state.value.entry)
        assertNull(candidate.getOrNull<FeedbackRepository>())
        source.viewModel.submit(SettingsFeedbackIntent.ChangeBody("Synthetic source draft"))
        language.viewModel.submit(SettingsFeedbackIntent.ChangeBody("Synthetic language draft"))
        runCurrent()
    }

    private fun TestScope.assertOnlySourceClosed(
        source: ComplaintBackendRequestOpening,
        language: ComplaintBackendRequestOpening,
    ) {
        source.close()
        source.close()
        runCurrent()
        assertFalse(source.viewModel.viewModelScope.isActive)
        assertEquals("", source.viewModel.state.value.draft.body)
        assertTrue(language.viewModel.viewModelScope.isActive)
        assertEquals("Synthetic language draft", language.viewModel.state.value.draft.body)
        assertEquals(ComplaintType.LANGUAGES, language.viewModel.state.value.draft.type)
    }

    private fun TestScope.assertFreshSourceOpening(
        candidate: Koin,
        source: ComplaintBackendRequestOpening,
        entry: SettingsFeedbackEntry,
    ) {
        val replacement = ComplaintBackendRequestOpening(candidate, entry)
        try {
            runCurrent()
            assertNotSame(source.viewModel, replacement.viewModel)
            assertEquals("", replacement.viewModel.state.value.draft.body)
            assertEquals(ComplaintType.SITES_ADD, replacement.viewModel.state.value.draft.type)
        } finally {
            replacement.close()
            runCurrent()
        }
    }

    private fun TestScope.assertMissingBeforeSetup(
        vm: SettingsFeedbackViewModel,
        fixture: ComplaintBackendGraphFixture,
    ) {
        runCurrent()
        vm.submit(SettingsFeedbackIntent.ChangeBody("Synthetic retained request"))
        runCurrent()
        assertTrue(vm.state.value.canSetupHistory)
        assertEquals(0, fixture.historyCalls + fixture.sessionCalls)
        assertNoReportWork(fixture)
    }

    private fun TestScope.assertExplicitHistorySetup(
        vm: SettingsFeedbackViewModel,
        fixture: ComplaintBackendGraphFixture,
    ) {
        // A sibling history consumer has installed the fixture identity. This case proves
        // the real bound history call, not bootstrap/storage behavior already tested in data.
        fixture.credentials.missing = false
        vm.submit(SettingsFeedbackIntent.SetupHistory)
        runCurrent()
        assertIs<SettingsFeedbackResult.HistorySetupCompleted>(vm.state.value.result)
        assertFalse(vm.state.value.canSetupHistory)
        assertEquals("Synthetic retained request", vm.state.value.draft.body)
        assertEquals(ComplaintType.LANGUAGES, vm.state.value.draft.type)
        assertEquals(1, fixture.historyCalls)
        assertEquals(1, fixture.sessionCalls)
        assertNoReportWork(fixture)
        assertFalse(fixture.events.any { it == "request:enrollment" })
    }

    private fun TestScope.assertNeutralActiveCleanup(
        vm: SettingsFeedbackViewModel,
        fixture: ComplaintBackendGraphFixture,
    ) {
        runCurrent()
        vm.submit(SettingsFeedbackIntent.ChangeBody("Synthetic cleanup-check draft"))
        runCurrent()
        vm.submit(SettingsFeedbackIntent.ResumeCleanup)
        runCurrent()
        assertIs<SettingsFeedbackResult.CleanupCheckCompleted>(vm.state.value.result)
        assertEquals("Synthetic cleanup-check draft", vm.state.value.draft.body)
        assertFalse(vm.state.value.confirmationPending)
        assertEquals(0, fixture.historyCalls + fixture.sessionCalls)
        assertNoReportWork(fixture)
        assertFalse(fixture.events.any { it == "request:enrollment" })
    }

    private fun candidateGraph(fixture: ComplaintBackendGraphFixture): ComplaintBackendGraph =
        assertIs<AppResult.Success<ComplaintBackendGraph>>(
            createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources()),
        ).value

    private fun assertNoReportWork(fixture: ComplaintBackendGraphFixture) {
        assertEquals(0, fixture.reportIdentifierGenerations + fixture.reportMetadataReads + fixture.mutationCalls)
        assertEquals(0, fixture.credentials.writes + fixture.pending.writes + fixture.generations)
    }
}
