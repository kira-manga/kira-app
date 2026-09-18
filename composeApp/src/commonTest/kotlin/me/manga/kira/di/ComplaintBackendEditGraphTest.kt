package me.manga.kira.di

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.repository.ReadOnlyComplaintActionRepository
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintEditDraft
import me.manga.kira.domain.model.feedback.ComplaintEditPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.repository.ComplaintActionRepository
import me.manga.kira.domain.repository.ComplaintDetailRepository
import me.manga.kira.domain.repository.ComplaintEditRepository
import me.manga.kira.domain.repository.ComplaintInstallationRecoveryRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.repository.ComplaintReplyRepository
import me.manga.kira.domain.repository.ComplaintReportRepository
import me.manga.kira.domain.usecase.complaint.LoadComplaintDetailUseCase
import me.manga.kira.domain.usecase.feedback.ComplaintEditActions
import me.manga.kira.domain.usecase.feedback.ComplaintReplyActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportActions
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComplaintBackendEditGraphTest {
    @Test
    fun typedEditUsesTheSameReportReplyRecoveryConsumerAndOnlyItsKeySupplierReachesPatch() =
        runTest {
            val fixture = ComplaintBackendGraphFixture(this)
            val edit = MobileEditGraphFixture(this, fixture)
            val graph = createComplaintBackendGraph({ GRAPH_BASE }, edit.resources()).editGraphSuccess()
            val app = koinApplication { modules(graph.module()) }
            try {
                assertSharedEditGraph(app.koin, graph)
                val actions = app.koin.get<ComplaintEditActions>()
                val ready =
                    assertIs<ComplaintEditPreparation.Ready>(actions.prepare(editGraphDraft()).editGraphSuccess())
                assertTrue(edit.requests.isEmpty() && edit.pending.transitions.isEmpty())
                val result =
                    assertIs<ComplaintReportAttempt.Completed>(actions.submit(ready.edit).editGraphSuccess().attempt)
                val application = assertIs<ComplaintReportApplication.Edit>(result.application)
                assertEquals(8L, assertIs<ComplaintEditApplication.Applied>(application.application).version)
                assertIs<AppResult.Failure>(actions.retry(ready.edit))
                edit.assertExactEdit()
                assertTrue(edit.pending.slots.isEmpty())
                assertEquals(1, edit.pending.deletes)
                assertEditOnlyGraphWork(fixture, edit)
            } finally {
                app.close()
                graph.close()
            }
            assertTrue(fixture.owners.values.all { it.closed })
        }

    @Test
    fun actualDetailReadCanObserveButCannotClearAnUncertainEditInTheSameOwnerGraph() =
        runTest {
            val fixture = ComplaintBackendGraphFixture(this)
            fixture.historyHandler = { respond(graphEditDetail().toString(), HttpStatusCode.OK, graphEditHeaders(7)) }
            val edit = MobileEditGraphFixture(this, fixture).apply { rejectDirect = true }
            val graph = createComplaintBackendGraph({ GRAPH_BASE }, edit.resources()).editGraphSuccess()
            val app = koinApplication { modules(graph.module()) }
            try {
                val actions = app.koin.get<ComplaintEditActions>()
                val ready =
                    assertIs<ComplaintEditPreparation.Ready>(actions.prepare(editGraphDraft()).editGraphSuccess())
                assertIs<ComplaintReportAttempt.Unresolved>(actions.submit(ready.edit).editGraphSuccess().attempt)
                assertDetailCannotClearEdit(app.koin, fixture, edit)
            } finally {
                app.close()
                graph.close()
            }
        }

    @Test
    fun editRegistrationCannotOpenShippingSelectionOrAllocateAnyCandidateResource() {
        var allocations = 0
        val result =
            selectComplaintBackendCandidate {
                createComplaintBackendGraph(
                    {
                        allocations++
                        GRAPH_BASE
                    },
                    trapGraphResources { allocations++ },
                )
            }
        assertIs<AppResult.Failure>(result)
        assertEquals(0, allocations)
    }
}

private suspend fun assertDetailCannotClearEdit(
    koin: Koin,
    fixture: ComplaintBackendGraphFixture,
    edit: MobileEditGraphFixture,
) {
    val slot = edit.pending.slots.single()
    val detail = koin.get<LoadComplaintDetailUseCase>()(GRAPH_EDIT_ID).editGraphSuccess()
    assertEquals(GRAPH_EDIT_ID, assertIs<ComplaintDetail.Owned>(detail).item.id)
    assertTrue(slot.sameAs(edit.pending.slots.single()))
    assertEquals(0, edit.pending.deletes)
    assertEquals(2, edit.pending.transitions.size)
    assertEquals(1, fixture.historyCalls)
    assertEquals(1, fixture.sessionCalls)
    assertEditOnlyGraphWork(fixture, edit)
}

private fun assertSharedEditGraph(koin: Koin, graph: ComplaintBackendGraph) {
    val reports = koin.get<ComplaintReportRepository>()
    val edits = koin.get<ComplaintEditRepository>()
    assertSame(graph.edits, edits)
    assertSame<Any?>(reports, edits)
    assertSame<Any?>(reports, koin.get<ComplaintReplyRepository>())
    assertSame<Any?>(reports, koin.get<ComplaintInstallationRecoveryRepository>())
    assertSame(graph.history, koin.get<ComplaintListRepository>())
    assertSame(graph.details, koin.get<ComplaintDetailRepository>())
    assertIs<ReadOnlyComplaintActionRepository>(koin.get<ComplaintActionRepository>())
    koin.get<ComplaintReportActions>()
    koin.get<ComplaintReplyActions>()
}

private fun assertEditOnlyGraphWork(fixture: ComplaintBackendGraphFixture, edit: MobileEditGraphFixture) {
    assertEquals(1, edit.editKeyGenerations)
    assertEquals(1, fixture.mutationCalls)
    assertEquals(0, fixture.reportIdentifierGenerations + fixture.reportMetadataReads)
    assertEquals(0, fixture.deletionCalls + fixture.generations + fixture.deletionKeyGenerations)
    assertEquals(0, fixture.credentials.writes + fixture.pending.writes)
    assertTrue(fixture.events.none { it == "request:enrollment" })
}

private fun editGraphDraft(): ComplaintEditDraft =
    ComplaintEditDraft(graphEditTarget(), "Replacement subject", GRAPH_EDIT_BODY)

private fun <T> AppResult<T>.editGraphSuccess(): T = assertIs<AppResult.Success<T>>(this).value
