package me.manga.kira.presentation.complaint

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.ComplaintActionRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.usecase.complaint.DeleteComplaintUseCase
import me.manga.kira.domain.usecase.complaint.EditComplaintUseCase
import me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase
import me.manga.kira.domain.usecase.complaint.ReplyToComplaintUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the [ComplaintViewModel] list + action-dialog contract (backlog T1):
 *  - the `init {}` load projects the list (or the non-leaking error sentinel on failure — the
 *    raw Firestore text must never reach state),
 *  - search + status filters compose over the unfiltered `all` list,
 *  - the row-click → MENU → action state machine, the action wires (reply/edit/delete reach the
 *    repository with the right payloads), success closes the dialog + reloads + emits
 *    [ComplaintEffect.ShowActionSuccess], failure keeps the dialog + emits
 *    [ComplaintEffect.ShowActionFailure], and the in-flight guard drops double-submits.
 */
class ComplaintViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun complaint(
        id: String = "c1",
        subject: String = "Subject",
        body: String = "Body",
        status: ComplaintStatus = ComplaintStatus.OPEN,
    ) = ComplaintSummary(
        id = id,
        userId = "u1",
        type = ComplaintType.TECHNICAL,
        subject = subject,
        body = body,
        createdAt = null,
        status = status,
    )

    private class FakeComplaintListRepository(
        var result: () -> Result<List<ComplaintSummary>>,
    ) : ComplaintListRepository {
        var loads = 0
        override suspend fun loadUserComplaints(): Result<List<ComplaintSummary>> {
            loads++
            return result()
        }
    }

    /** Recording action repo whose calls can be gated open (in-flight) and resolved on demand. */
    private class RecordingComplaintActionRepository(
        var result: Result<Unit> = Result.success(Unit),
    ) : ComplaintActionRepository {
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        private suspend fun record(call: String): Result<Unit> {
            calls += call
            gate?.await()
            return result
        }
        override suspend fun replyToComplaint(parent: ComplaintSummary, body: String): Result<Unit> =
            record("reply:${parent.id}:$body")
        override suspend fun editComplaint(original: ComplaintSummary, subject: String, body: String): Result<Unit> =
            record("edit:${original.id}:$subject:$body")
        override suspend fun deleteComplaint(id: String): Result<Unit> =
            record("delete:$id")
    }

    private fun viewModel(
        list: FakeComplaintListRepository = FakeComplaintListRepository({ Result.success(emptyList()) }),
        actions: RecordingComplaintActionRepository = RecordingComplaintActionRepository(),
    ) = ComplaintViewModel(
        ObserveUserComplaintsUseCase(list),
        ReplyToComplaintUseCase(actions),
        EditComplaintUseCase(actions),
        DeleteComplaintUseCase(actions),
    )

    @Test
    fun initLoad_success_projectsAllAndFiltered() = runTest {
        val rows = listOf(complaint(id = "a"), complaint(id = "b"))
        val vm = viewModel(list = FakeComplaintListRepository({ Result.success(rows) }))

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(rows, state.all)
        assertEquals(rows, state.filtered)
    }

    @Test
    fun initLoad_failure_setsNonLeakingErrorSentinel() = runTest {
        val vm = viewModel(
            list = FakeComplaintListRepository({ Result.failure(RuntimeException("PERMISSION_DENIED: raw sdk text")) }),
        )

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
        assertFalse(
            state.error!!.contains("PERMISSION_DENIED"),
            "the raw Firestore text must never leak into state — :ui shows a generic localized message",
        )
        assertTrue(state.all.isEmpty())
    }

    @Test
    fun searchAndStatusFilters_composeOverTheUnfilteredList() = runTest {
        val rows = listOf(
            complaint(id = "a", subject = "Login broken", status = ComplaintStatus.OPEN),
            complaint(id = "b", subject = "Add source", status = ComplaintStatus.RESOLVED),
            complaint(id = "c", body = "cannot LOGIN on iOS", status = ComplaintStatus.RESOLVED),
        )
        val vm = viewModel(list = FakeComplaintListRepository({ Result.success(rows) }))

        vm.submit(ComplaintIntent.OnSearchChange("login"))
        assertEquals(listOf("a", "c"), vm.state.value.filtered.map { it.id }, "case-insensitive subject+body match")

        vm.submit(ComplaintIntent.OnStatusFilter(ComplaintStatus.RESOLVED))
        assertEquals(listOf("c"), vm.state.value.filtered.map { it.id }, "search AND status compose")

        vm.submit(ComplaintIntent.OnClearSearch)
        assertEquals(listOf("b", "c"), vm.state.value.filtered.map { it.id }, "clearing search keeps the status filter")
    }

    @Test
    fun replyAction_success_reachesRepo_closesDialog_reloads_emitsSuccess() = runTest {
        val target = complaint(id = "c9")
        val list = FakeComplaintListRepository({ Result.success(listOf(target)) })
        val actions = RecordingComplaintActionRepository()
        val vm = viewModel(list = list, actions = actions)
        val effects = mutableListOf<ComplaintEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(ComplaintIntent.OnRowClick(target))
        assertEquals(ActionDialogMode.MENU, vm.state.value.actionDialogMode)
        vm.submit(ComplaintIntent.OnSelectAction(ActionDialogMode.REPLY))
        vm.submit(ComplaintIntent.OnSubmitReply("thanks, fixed"))

        assertEquals(listOf("reply:c9:thanks, fixed"), actions.calls)
        assertEquals(ActionDialogMode.NONE, vm.state.value.actionDialogMode, "success closes the dialog")
        assertNull(vm.state.value.activeComplaint)
        assertFalse(vm.state.value.isSubmittingAction)
        assertEquals(2, list.loads, "success reloads the list (init + post-action)")
        assertEquals(listOf<ComplaintEffect>(ComplaintEffect.ShowActionSuccess(ComplaintAction.REPLY_SENT)), effects)
        collector.cancel()
    }

    @Test
    fun deleteAction_failure_keepsDialog_emitsFailure() = runTest {
        val target = complaint(id = "c9")
        val list = FakeComplaintListRepository({ Result.success(listOf(target)) })
        val actions = RecordingComplaintActionRepository(result = Result.failure(RuntimeException("firestore boom")))
        val vm = viewModel(list = list, actions = actions)
        val effects = mutableListOf<ComplaintEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(ComplaintIntent.OnRowClick(target))
        vm.submit(ComplaintIntent.OnSelectAction(ActionDialogMode.DELETE))
        vm.submit(ComplaintIntent.OnConfirmDelete)

        assertEquals(listOf("delete:c9"), actions.calls)
        assertEquals(
            ActionDialogMode.DELETE,
            vm.state.value.actionDialogMode,
            "failure keeps the dialog so the user can retry",
        )
        assertFalse(vm.state.value.isSubmittingAction, "the guard flag resets so retry can run")
        assertEquals(1, list.loads, "no reload on failure")
        assertEquals(listOf<ComplaintEffect>(ComplaintEffect.ShowActionFailure), effects)
        collector.cancel()
    }

    @Test
    fun inFlightGuard_blocksSecondSubmitDismissAndRowClick() = runTest {
        val target = complaint(id = "c9")
        val other = complaint(id = "c2")
        val actions = RecordingComplaintActionRepository()
        actions.gate = CompletableDeferred() // hold the first action in flight
        val vm = viewModel(
            list = FakeComplaintListRepository({ Result.success(listOf(target, other)) }),
            actions = actions,
        )

        vm.submit(ComplaintIntent.OnRowClick(target))
        vm.submit(ComplaintIntent.OnSelectAction(ActionDialogMode.EDIT))
        vm.submit(ComplaintIntent.OnSubmitEdit("s", "b"))
        assertTrue(vm.state.value.isSubmittingAction, "first submit is held in flight by the gate")

        vm.submit(ComplaintIntent.OnSubmitEdit("s2", "b2")) // double tap — must be dropped
        vm.submit(ComplaintIntent.OnDismissActionDialog) // dismissal is blocked while in flight
        vm.submit(ComplaintIntent.OnRowClick(other)) // so is switching the active complaint

        assertEquals(listOf("edit:c9:s:b"), actions.calls, "the in-flight guard drops the second submit")
        assertEquals(ActionDialogMode.EDIT, vm.state.value.actionDialogMode, "dismiss blocked mid-flight")
        assertEquals("c9", vm.state.value.activeComplaint?.id, "row switch blocked mid-flight")

        actions.gate?.complete(Unit)
        assertFalse(vm.state.value.isSubmittingAction, "flag resets once the gated action completes")
        assertEquals(ActionDialogMode.NONE, vm.state.value.actionDialogMode)
    }
}
