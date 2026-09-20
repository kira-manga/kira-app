package me.manga.kira.presentation.complaint

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistory
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
 *    [ComplaintEffect.ShowActionSuccess], failure keeps the dialog with a non-leaking error flag
 *    and no screen effect, and the in-flight guard drops double-submits.
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

        override suspend fun loadUserComplaints(): AppResult<ComplaintHistory> {
            loads++
            return result().fold(
                onSuccess = { AppResult.Success(ComplaintHistory.Legacy(it)) },
                onFailure = { AppResult.Failure(AppError.Network.NoConnectivity()) },
            )
        }
    }

    /** Recording action repo whose calls can be gated open (in-flight) and resolved on demand. */
    private class RecordingComplaintActionRepository(
        var result: Result<Unit> = Result.success(Unit),
    ) : ComplaintActionRepository {
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var thrownFailure: Throwable? = null

        private suspend fun record(call: String): Result<Unit> {
            calls += call
            gate?.await()
            thrownFailure?.let { throw it }
            return result
        }

        override suspend fun replyToComplaint(
            parent: ComplaintSummary,
            body: String,
        ): Result<Unit> = record("reply:${parent.id}:$body")

        override suspend fun editComplaint(
            original: ComplaintSummary,
            subject: String,
            body: String,
        ): Result<Unit> = record("edit:${original.id}:$subject:$body")

        override suspend fun deleteComplaint(id: String): Result<Unit> = record("delete:$id")
    }

    private data class RetryScenario(
        val mode: ActionDialogMode,
        val intent: ComplaintIntent,
        val expectedCall: String,
        val success: ComplaintAction,
    )

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
    fun initLoad_success_projectsAllAndFiltered() =
        runTest {
            val rows = listOf(complaint(id = "a"), complaint(id = "b"))
            val vm = viewModel(list = FakeComplaintListRepository({ Result.success(rows) }))

            val state = vm.state.value
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertEquals(rows, state.all)
            assertEquals(rows, state.filtered)
        }

    @Test
    fun initLoad_failure_setsNonLeakingErrorSentinel() =
        runTest {
            val vm =
                viewModel(
                    list =
                        FakeComplaintListRepository({
                            Result.failure(RuntimeException("PERMISSION_DENIED: raw sdk text"))
                        }),
                )

            val state = vm.state.value
            assertFalse(state.isLoading)
            assertNotNull(state.error)
            assertNull(state.error!!.cause, "raw Firestore causes must not cross the typed read boundary")
            assertTrue(state.all.isEmpty())
        }

    @Test
    fun searchAndStatusFilters_composeOverTheUnfilteredList() =
        runTest {
            val rows =
                listOf(
                    complaint(id = "a", subject = "Login broken", status = ComplaintStatus.OPEN),
                    complaint(id = "b", subject = "Add source", status = ComplaintStatus.RESOLVED),
                    complaint(id = "c", body = "cannot LOGIN on iOS", status = ComplaintStatus.RESOLVED),
                )
            val vm = viewModel(list = FakeComplaintListRepository({ Result.success(rows) }))

            vm.submit(ComplaintIntent.OnSearchChange("login"))
            assertEquals(
                listOf("a", "c"),
                vm.state.value.filtered
                    .map { it.id },
                "case-insensitive subject+body match",
            )

            vm.submit(ComplaintIntent.OnStatusFilter(ComplaintStatus.RESOLVED))
            assertEquals(
                listOf("c"),
                vm.state.value.filtered
                    .map { it.id },
                "search AND status compose",
            )

            vm.submit(ComplaintIntent.OnClearSearch)
            assertEquals(
                listOf("b", "c"),
                vm.state.value.filtered
                    .map { it.id },
                "clearing search keeps the status filter",
            )
        }

    @Test
    fun replyAction_success_reachesRepo_closesDialog_reloads_emitsSuccess() =
        runTest {
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
            assertFalse(vm.state.value.actionFailed)
            assertEquals(2, list.loads, "success reloads the list (init + post-action)")
            assertEquals(listOf<ComplaintEffect>(ComplaintEffect.ShowActionSuccess(ComplaintAction.REPLY_SENT)), effects)
            collector.cancel()
        }

    @Test
    fun deleteAction_failure_keepsDialog_setsModalErrorWithoutEffect() = runTest {
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
        assertEquals(target, vm.state.value.activeComplaint)
        assertFalse(vm.state.value.isSubmittingAction, "the guard flag resets so retry can run")
        assertTrue(vm.state.value.actionFailed)
        assertEquals(1, list.loads, "no reload on failure")
        assertTrue(effects.isEmpty(), "failure must not enqueue a snackbar behind the modal")
        collector.cancel()
    }

    @Test
    fun inFlightGuard_blocksSecondSubmitDismissAndRowClick() =
        runTest {
            val target = complaint(id = "c9")
            val other = complaint(id = "c2")
            val actions = RecordingComplaintActionRepository()
            actions.gate = CompletableDeferred() // hold the first action in flight
            val vm =
                viewModel(
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
            assertEquals(
                "c9",
                vm.state.value.activeComplaint
                    ?.id,
                "row switch blocked mid-flight",
            )

            actions.gate?.complete(Unit)
            assertFalse(vm.state.value.isSubmittingAction, "flag resets once the gated action completes")
            assertEquals(ActionDialogMode.NONE, vm.state.value.actionDialogMode)
        }

    @Test
    fun actionFailures_allowOneExplicitRetryWithSamePayload_andClearOnSuccess() = runTest {
        val scenarios = listOf(
            RetryScenario(
                ActionDialogMode.REPLY,
                ComplaintIntent.OnSubmitReply("reply draft"),
                "reply:c9:reply draft",
                ComplaintAction.REPLY_SENT,
            ),
            RetryScenario(
                ActionDialogMode.EDIT,
                ComplaintIntent.OnSubmitEdit("edited subject", "edited body"),
                "edit:c9:edited subject:edited body",
                ComplaintAction.UPDATED,
            ),
            RetryScenario(
                ActionDialogMode.DELETE,
                ComplaintIntent.OnConfirmDelete,
                "delete:c9",
                ComplaintAction.DELETED,
            ),
        )

        for (scenario in scenarios) {
            val target = complaint(id = "c9")
            val list = FakeComplaintListRepository({ Result.success(listOf(target)) })
            val actions = RecordingComplaintActionRepository(result = Result.failure(RuntimeException("action failed")))
            val vm = viewModel(list = list, actions = actions)
            val effects = mutableListOf<ComplaintEffect>()
            val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

            vm.submit(ComplaintIntent.OnRowClick(target))
            vm.submit(ComplaintIntent.OnSelectAction(scenario.mode))
            vm.submit(scenario.intent)

            assertEquals(listOf(scenario.expectedCall), actions.calls)
            assertEquals(scenario.mode, vm.state.value.actionDialogMode)
            assertEquals(target, vm.state.value.activeComplaint)
            assertTrue(vm.state.value.actionFailed)
            assertFalse(vm.state.value.isSubmittingAction)
            assertEquals(1, list.loads, "a failed mutation must not reload/reset the form")
            assertTrue(effects.isEmpty())

            actions.result = Result.success(Unit)
            actions.gate = CompletableDeferred()
            // Simulate the retained form sending its unchanged payload through the same control.
            // Actual rememberSaveable draft/focus retention belongs to the manual UI checklist.
            vm.submit(scenario.intent)
            assertTrue(vm.state.value.isSubmittingAction)
            assertFalse(vm.state.value.actionFailed, "a new attempt clears the old error")

            vm.submit(scenario.intent) // duplicate retry
            vm.submit(ComplaintIntent.OnDismissActionDialog)
            vm.submit(ComplaintIntent.OnSelectAction(ActionDialogMode.MENU))
            vm.submit(ComplaintIntent.OnRowClick(complaint(id = "other")))

            assertEquals(listOf(scenario.expectedCall, scenario.expectedCall), actions.calls)
            assertEquals(scenario.mode, vm.state.value.actionDialogMode, "retry retains its mode")
            assertEquals(target, vm.state.value.activeComplaint, "retry retains its target")

            actions.gate?.complete(Unit)

            assertFalse(vm.state.value.isSubmittingAction)
            assertFalse(vm.state.value.actionFailed)
            assertEquals(ActionDialogMode.NONE, vm.state.value.actionDialogMode)
            assertNull(vm.state.value.activeComplaint)
            assertEquals(2, list.loads, "only success reloads the list")
            assertEquals(listOf<ComplaintEffect>(ComplaintEffect.ShowActionSuccess(scenario.success)), effects)
            collector.cancel()
        }
    }

    @Test
    fun thrownActionFailure_keepsModalTargetAndList_untilExplicitRetrySucceeds() = runTest {
        val target = complaint(id = "c9")
        val list = FakeComplaintListRepository({ Result.success(listOf(target)) })
        val actions = RecordingComplaintActionRepository().apply {
            thrownFailure = IllegalStateException("private transport diagnostic")
        }
        val vm = viewModel(list = list, actions = actions)
        val effects = mutableListOf<ComplaintEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(ComplaintIntent.OnRowClick(target))
        vm.submit(ComplaintIntent.OnSelectAction(ActionDialogMode.DELETE))
        vm.submit(ComplaintIntent.OnConfirmDelete)

        assertEquals(listOf("delete:c9"), actions.calls)
        assertEquals(ActionDialogMode.DELETE, vm.state.value.actionDialogMode)
        assertEquals(target, vm.state.value.activeComplaint)
        assertEquals(listOf(target), vm.state.value.all)
        assertEquals(1, list.loads)
        assertTrue(vm.state.value.actionFailed)
        assertFalse(vm.state.value.isSubmittingAction)
        assertNull(vm.state.value.error, "an action exception must not replace the list with an error")
        assertTrue(effects.isEmpty(), "failure stays in the modal, without exposing raw diagnostics")

        actions.thrownFailure = null
        vm.submit(ComplaintIntent.OnConfirmDelete)

        assertEquals(listOf("delete:c9", "delete:c9"), actions.calls)
        assertFalse(vm.state.value.actionFailed)
        assertEquals(ActionDialogMode.NONE, vm.state.value.actionDialogMode)
        assertNull(vm.state.value.activeComplaint)
        assertEquals(2, list.loads)
        assertEquals(listOf<ComplaintEffect>(ComplaintEffect.ShowActionSuccess(ComplaintAction.DELETED)), effects)
        collector.cancel()
    }

    @Test
    fun actionFailure_clearsOnModeChangeDismissalAndNewTarget() = runTest {
        val target = complaint(id = "c9")
        val other = complaint(id = "other")
        val actions = RecordingComplaintActionRepository(result = Result.failure(RuntimeException("action failed")))
        val vm = viewModel(actions = actions)

        vm.submit(ComplaintIntent.OnRowClick(target))
        vm.submit(ComplaintIntent.OnSelectAction(ActionDialogMode.REPLY))
        vm.submit(ComplaintIntent.OnSubmitReply("reply draft"))
        assertTrue(vm.state.value.actionFailed)

        vm.submit(ComplaintIntent.OnSelectAction(ActionDialogMode.EDIT))
        assertFalse(vm.state.value.actionFailed)
        assertEquals(target, vm.state.value.activeComplaint)

        vm.submit(ComplaintIntent.OnSubmitEdit("edited subject", "edited body"))
        assertTrue(vm.state.value.actionFailed)
        vm.submit(ComplaintIntent.OnDismissActionDialog)
        assertFalse(vm.state.value.actionFailed)
        assertEquals(ActionDialogMode.NONE, vm.state.value.actionDialogMode)
        assertNull(vm.state.value.activeComplaint)

        vm.submit(ComplaintIntent.OnRowClick(target))
        assertFalse(vm.state.value.actionFailed, "reopening has no stale error")
        vm.submit(ComplaintIntent.OnSelectAction(ActionDialogMode.DELETE))
        vm.submit(ComplaintIntent.OnConfirmDelete)
        assertTrue(vm.state.value.actionFailed)
        vm.submit(ComplaintIntent.OnRowClick(other))
        assertFalse(vm.state.value.actionFailed)
        assertEquals(ActionDialogMode.MENU, vm.state.value.actionDialogMode)
        assertEquals(other, vm.state.value.activeComplaint)
        assertEquals(3, actions.calls.size, "navigation never retries a failed mutation")
    }
}
