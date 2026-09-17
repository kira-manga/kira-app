package me.manga.kira.di

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.FeedbackRepository
import me.manga.kira.navigation.routes.ComplaintBackendRequestOpening
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackIntent
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackResult
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackViewModel
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Existing graph fixture plus ordinary lifecycle stores; no Compose automation or new transport fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintBackendRequestOwnershipTest {
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

    private fun candidateGraph(fixture: ComplaintBackendGraphFixture): ComplaintBackendGraph =
        assertIs<AppResult.Success<ComplaintBackendGraph>>(
            createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources()),
        ).value

    private fun assertNoReportWork(fixture: ComplaintBackendGraphFixture) {
        assertEquals(0, fixture.reportIdentifierGenerations + fixture.reportMetadataReads + fixture.mutationCalls)
        assertEquals(0, fixture.credentials.writes + fixture.pending.writes + fixture.generations)
    }
}
