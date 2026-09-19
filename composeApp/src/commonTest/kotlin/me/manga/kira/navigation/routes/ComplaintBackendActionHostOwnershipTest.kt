package me.manga.kira.navigation.routes

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.di.ComplaintBackendGraph
import me.manga.kira.di.GRAPH_EDIT_ID
import me.manga.kira.di.graphEditTarget
import me.manga.kira.di.selectComplaintBackendCandidate
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintNotice
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
import me.manga.kira.domain.repository.ComplaintDetailRepository
import me.manga.kira.domain.repository.ComplaintEditRepository
import me.manga.kira.domain.repository.ComplaintInstallationDeletionRepository
import me.manga.kira.domain.repository.ComplaintInstallationRecoveryRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.repository.ComplaintOwnerDeleteRepository
import me.manga.kira.domain.repository.ComplaintReplyRepository
import me.manga.kira.domain.repository.ComplaintReportRepository
import me.manga.kira.domain.usecase.feedback.ComplaintReplyActions
import me.manga.kira.navigation.routes.ComplaintBackendAction.DELETE
import me.manga.kira.navigation.routes.ComplaintBackendAction.EDIT
import me.manga.kira.navigation.routes.ComplaintBackendAction.REPLY
import me.manga.kira.presentation.complaint.ComplaintDetailState
import me.manga.kira.presentation.settings.feedback.edit.BackendComplaintEditIntent
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Direct owner hooks and real candidate VMs; no Compose/native execution is implied by these tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintBackendActionHostOwnershipTest {
    @BeforeTest
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun onlyTheFreshSnapshotCanOccupyOneSlotAndAnotherActionRequiresReselection() =
        runTest {
            withActionHost {
                val old = select()
                owner.open(EDIT, ComplaintDetail.Owned(old.item))
                assertNull(owner.state.value)
                setVersion(8)
                val fresh = refresh()
                assertNotSame(old, fresh)
                assertFalse(owner.canOpen(EDIT, old))
                owner.open(EDIT, fresh)
                val opening = assertIs<ComplaintBackendActionSlot.Action>(owner.state.value).opening
                owner.open(DELETE, fresh)
                idle()
                assertSame(opening, assertIs<ComplaintBackendActionSlot.Action>(owner.state.value).opening)
                assertFalse(owner.canSelect)
                collectFinish(opening)
                requestClose(opening, recovery = false)
                assertNull(owner.state.value)
                assertNull(detail.detail.state.value.selectedId)
                assertFalse(owner.canOpen(EDIT, fresh))
                val replacement = open(DELETE)
                assertNotSame(opening.viewModel, replacement.viewModel)
                assertNoWriteOrSetup()
            }
        }

    @Test
    fun editKeepsTheOriginalTypedReadAndTagDespiteAnIndependentLaterDetailRead() =
        runTest {
            withActionHost {
                val original = select()
                val opening = open(EDIT, original)
                changeDraft(opening)
                setVersion(9)
                val later = select()
                assertEquals(9L, assertIs<ComplaintOwnerRow.Content>(later.item).fields.version)
                val reads = backend.historyCalls
                submit(opening)
                edit.assertExactEdit()
                assertTrue(assertIs<ComplaintBackendActionPanel.Edit>(opening.panel).viewModel.state.value.result.completed)
                assertSame(later, detail.detail.state.value.detail)
                assertTrue(edit.pending.slots.isEmpty())
                collectFinish(opening)
                requestClose(opening, recovery = false)
                assertEquals(reads, backend.historyCalls)
                assertNull(detail.detail.state.value.selectedId)
                assertEquals(7L, assertIs<ComplaintOwnerRow.Content>(original.item).fields.version)
            }
        }

    @Test
    fun staleUnknownReadsCannotGrantActionsAndKnownNoticeRepliesKeepBodyOnlyEdits() =
        runTest {
            assertReadEligibility()
            withActionHost {
                val report = graphEditTarget()
                val reply = ComplaintOwnerRow.Reply(report.fields, report.type, report.subject, GRAPH_EDIT_ID)
                assertTrue(REPLY.accepts(ComplaintDetail.Owned(reply)))
                for (key in listOf("complaints.notice.future-policy", "complaints.notice.content-policy ", "admin")) {
                    val unknown = ComplaintDetail.Owned(ComplaintOwnerRow.NoticeReply(report.fields, key, GRAPH_EDIT_ID))
                    assertSame(unknown, ComplaintDetailState(GRAPH_EDIT_ID, unknown).actionTarget())
                    for (action in ComplaintBackendAction.entries) {
                        assertFalse(action.accepts(unknown))
                        assertFailsWith<IllegalStateException> { ComplaintBackendActionOpening(app.koin, action, unknown) }
                    }
                }
                assertKnownNoticeReply("complaints.notice.content-policy")
                assertKnownNoticeReply("complaints.notice.source-requirements")
                assertNoWriteOrSetup()
            }
        }

    @Test
    fun everyMismatchedCandidatePortFailsBeforeVmWorkAndShippingSelectionStaysDisabled() =
        runTest {
            for (replace in mismatchedPorts()) {
                withActionHost {
                    val target = select()
                    val reads = credentials.reads
                    app.koin.loadModules(listOf(replace(graph)))
                    assertFailsWith<IllegalStateException> { owner.open(REPLY, target) }
                    runCurrent()
                    assertNull(owner.state.value)
                    assertTrue(owner.canSelect)
                    assertEquals(reads, credentials.reads)
                    assertTrue(backend.owners.values.none { it.closed })
                    assertNoWriteOrSetup()
                }
            }
            var allocated = false
            val selected = selectComplaintBackendCandidate {
                allocated = true
                AppResult.Success(Unit)
            }
            assertIs<AppResult.Failure>(selected)
            assertFalse(allocated)
        }

    @Test
    fun factoryFailureReleasesReservationAndReentrantAbandonmentCancelsBeforeInitialization() =
        runTest {
            withActionHost {
                val target = select()
                val reads = credentials.reads
                val failing = module { factory<ComplaintReplyActions> { error("Synthetic factory failure") } }
                app.koin.loadModules(listOf(failing))
                assertFails { owner.open(REPLY, target) }
                assertTrue(owner.canOpen(REPLY, target))
                assertNull(owner.state.value)
                val abandoning = module {
                    factory {
                        owner.onAbandoned()
                        ComplaintReplyActions(get(), get(), get())
                    }
                }
                app.koin.loadModules(listOf(abandoning))
                owner.open(REPLY, target)
                runCurrent()
                assertFalse(owner.canSelect)
                assertNull(owner.state.value)
                assertEquals(reads, credentials.reads)
                assertTrue(detail.history.viewModelScope.isActive && detail.detail.viewModelScope.isActive)
                assertTrue(backend.owners.values.none { it.closed })
                assertNoWriteOrSetup()
            }
        }

    private fun ComplaintBackendActionHostFixture.assertKnownNoticeReply(key: String) {
        val report = graphEditTarget()
        val target = ComplaintDetail.Owned(ComplaintOwnerRow.NoticeReply(report.fields, key, GRAPH_EDIT_ID))
        assertTrue(REPLY.accepts(target))
        assertTrue(EDIT.accepts(target) && DELETE.accepts(target))
        val opening = ComplaintBackendActionOpening(app.koin, EDIT, target)
        try {
            val vm = assertIs<ComplaintBackendActionPanel.Edit>(opening.panel).viewModel
            assertNull(vm.state.value.draft.subject)
            vm.submit(BackendComplaintEditIntent.ChangeSubject("Must not become an editable subject"))
            idle()
            assertNull(vm.state.value.draft.subject)
            assertEquals(report.fields.body, vm.state.value.draft.body)
        } finally {
            opening.close()
        }
    }
}

private fun assertReadEligibility() {
    val report = graphEditTarget()
    val owned = ComplaintDetail.Owned(report)
    val fresh = ComplaintDetailState(GRAPH_EDIT_ID, owned)
    assertSame(owned, fresh.actionTarget())
    assertTrue(ComplaintBackendAction.entries.all { it.accepts(owned) })
    val unknown = UnknownComplaintItem(report.id, "FUTURE", report.createdAt, report.updatedAt)
    val type = ComplaintOwnerRow.Report(report.fields, ComplaintHistoryType.Unrecognized, report.subject)
    val notice = ComplaintNotice(report.id, "complaints.notice.content-policy", report.createdAt, report.updatedAt, 1)
    val system = ComplaintDetail.Notice(notice)
    assertSame(system, fresh.copy(detail = system).actionTarget())
    assertTrue(REPLY.accepts(system))
    assertFalse(EDIT.accepts(system) || DELETE.accepts(system))
    listOf(
        fresh.copy(isLoading = true),
        fresh.copy(error = AppError.Unexpected("synthetic-read-failure")),
        fresh.copy(selectedId = null),
        fresh.copy(selectedId = "00000000-0000-0000-0000-000000000000"),
        fresh.copy(detail = null),
        fresh.copy(detail = ComplaintDetail.Unavailable),
        fresh.copy(detail = ComplaintDetail.Owned(unknown)),
        fresh.copy(detail = ComplaintDetail.Owned(type)),
    ).forEach { assertNull(it.actionTarget()) }
}

private fun mismatchedPorts(): List<(ComplaintBackendGraph) -> Module> =
    listOf<(ComplaintBackendGraph) -> Module>(
        { graph -> module { single<ComplaintListRepository> { object : ComplaintListRepository by graph.history {} } } },
        { graph ->
            module { single<ComplaintDetailRepository> { object : ComplaintDetailRepository by graph.details {} } }
        },
        { graph ->
            module { single<ComplaintReportRepository> { object : ComplaintReportRepository by graph.reports {} } }
        },
        { graph -> module { single<ComplaintReplyRepository> { object : ComplaintReplyRepository by graph.replies {} } } },
        { graph -> module { single<ComplaintEditRepository> { object : ComplaintEditRepository by graph.edits {} } } },
        { graph ->
            module {
                single<ComplaintOwnerDeleteRepository> { object : ComplaintOwnerDeleteRepository by graph.ownerDeletes {} }
            }
        },
    ) + mismatchedRecoveryPorts()

private fun mismatchedRecoveryPorts(): List<(ComplaintBackendGraph) -> Module> =
    listOf(
        { graph ->
            module {
                single<ComplaintInstallationRecoveryRepository> {
                    object : ComplaintInstallationRecoveryRepository by graph.installationRecovery {}
                }
            }
        },
        { graph ->
            module {
                single<ComplaintInstallationDeletionRepository> {
                    object : ComplaintInstallationDeletionRepository by graph.installationDeletion {}
                }
            }
        },
    )
