package me.manga.kira.presentation.complaint.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.AdminComplaintActionRepository
import me.manga.kira.domain.repository.AdminComplaintListRepository
import me.manga.kira.domain.usecase.complaint.AddClosureReasonUseCase
import me.manga.kira.domain.usecase.complaint.AdminDeleteComplaintUseCase
import me.manga.kira.domain.usecase.complaint.AdminEditComplaintUseCase
import me.manga.kira.domain.usecase.complaint.ChangeComplaintStatusUseCase
import me.manga.kira.domain.usecase.complaint.ObserveAllComplaintsUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Locks the [AdminComplaintViewModel] console contract (backlog T1 — the VM logic only; whether
 * the admin console is REACHABLE is the separate `Admin.isAdmin` gating decision, deliberately
 * untouched here):
 *  - the `init {}` load projects list + computed [AdminComplaintStatistics] (or the non-leaking
 *    error sentinel),
 *  - the four-axis filter (search/status/type/appVersion) + sort compose over `all`,
 *  - a moderation action reaches the repository with the right payload; success closes the
 *    dialog + reloads + recomputes statistics + emits
 *    [AdminComplaintEffect.ShowActionSuccess], failure keeps the dialog + emits
 *    [AdminComplaintEffect.ShowActionFailure],
 *  - [AdminComplaintIntent.OnToggleStatsCard] flips the card visibility.
 */
class AdminComplaintViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun complaint(
        id: String = "c1",
        userId: String = "u1",
        type: ComplaintType = ComplaintType.TECHNICAL,
        subject: String = "Subject",
        status: ComplaintStatus = ComplaintStatus.OPEN,
        appVersion: String? = null,
    ) = ComplaintSummary(
        id = id,
        userId = userId,
        type = type,
        subject = subject,
        body = "Body",
        createdAt = null,
        status = status,
        appVersion = appVersion,
    )

    private class FakeAdminListRepository(
        var result: () -> Result<List<ComplaintSummary>>,
    ) : AdminComplaintListRepository {
        var loads = 0
        override suspend fun loadAllComplaints(): Result<List<ComplaintSummary>> {
            loads++
            return result()
        }
    }

    private class RecordingAdminActionRepository(
        var result: Result<Unit> = Result.success(Unit),
    ) : AdminComplaintActionRepository {
        val calls = mutableListOf<String>()
        override suspend fun changeStatus(complaint: ComplaintSummary, newStatus: ComplaintStatus): Result<Unit> {
            calls += "status:${complaint.id}:$newStatus"
            return result
        }
        override suspend fun addClosureReason(complaint: ComplaintSummary, reason: String): Result<Unit> {
            calls += "closure:${complaint.id}:$reason"
            return result
        }
        override suspend fun deleteComplaint(id: String): Result<Unit> {
            calls += "delete:$id"
            return result
        }
        override suspend fun editComplaint(
            original: ComplaintSummary,
            type: ComplaintType,
            subject: String,
            body: String,
        ): Result<Unit> {
            calls += "edit:${original.id}:$type:$subject:$body"
            return result
        }
    }

    private fun viewModel(
        list: FakeAdminListRepository = FakeAdminListRepository({ Result.success(emptyList()) }),
        actions: RecordingAdminActionRepository = RecordingAdminActionRepository(),
    ) = AdminComplaintViewModel(
        ObserveAllComplaintsUseCase(list),
        ChangeComplaintStatusUseCase(actions),
        AddClosureReasonUseCase(actions),
        AdminDeleteComplaintUseCase(actions),
        AdminEditComplaintUseCase(actions),
    )

    @Test
    fun initLoad_projectsListAndComputedStatistics() = runTest {
        val rows = listOf(
            complaint(id = "a", status = ComplaintStatus.OPEN, appVersion = "1.0.0"),
            complaint(id = "b", status = ComplaintStatus.OPEN, appVersion = "1.0.0"),
            complaint(id = "c", status = ComplaintStatus.RESOLVED, appVersion = "0.9.0"),
        )
        val vm = viewModel(list = FakeAdminListRepository({ Result.success(rows) }))

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals(3, state.all.size)
        assertEquals(3, state.statistics.total)
        assertEquals(2, state.statistics.byStatus[ComplaintStatus.OPEN])
        assertEquals(1, state.statistics.byStatus[ComplaintStatus.RESOLVED])
        assertEquals(mapOf("1.0.0" to 2, "0.9.0" to 1), state.statistics.byAppVersion)
    }

    @Test
    fun initLoad_failure_setsNonLeakingSentinel_andZeroedStatistics() = runTest {
        val vm = viewModel(
            list = FakeAdminListRepository({ Result.failure(RuntimeException("PERMISSION_DENIED: raw sdk text")) }),
        )

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
        assertFalse(state.error!!.contains("PERMISSION_DENIED"), "raw Firestore text must never reach state")
        assertEquals(AdminComplaintStatistics(), state.statistics)
    }

    @Test
    fun filters_composeAcrossAllFourAxes() = runTest {
        val rows = listOf(
            complaint(id = "a", userId = "alice", type = ComplaintType.TECHNICAL, status = ComplaintStatus.OPEN, appVersion = "1.0.0"),
            complaint(id = "b", userId = "alice", type = ComplaintType.FEATURES, status = ComplaintStatus.OPEN, appVersion = "1.0.0"),
            complaint(id = "c", userId = "bob", type = ComplaintType.TECHNICAL, status = ComplaintStatus.OPEN, appVersion = "1.0.0"),
            complaint(id = "d", userId = "alice", type = ComplaintType.TECHNICAL, status = ComplaintStatus.CLOSED, appVersion = "0.9.0"),
        )
        val vm = viewModel(list = FakeAdminListRepository({ Result.success(rows) }))

        vm.submit(AdminComplaintIntent.OnSearchChange("alice"))
        assertEquals(listOf("a", "b", "d"), vm.state.value.filtered.map { it.id }, "search matches userId too")

        vm.submit(AdminComplaintIntent.OnTypeFilter(ComplaintType.TECHNICAL))
        assertEquals(listOf("a", "d"), vm.state.value.filtered.map { it.id })

        vm.submit(AdminComplaintIntent.OnStatusFilter(ComplaintStatus.OPEN))
        vm.submit(AdminComplaintIntent.OnAppVersionFilter("1.0.0"))
        assertEquals(listOf("a"), vm.state.value.filtered.map { it.id }, "all four axes compose")
    }

    @Test
    fun statusChange_success_reachesRepo_closesDialog_reloads_emitsSuccess() = runTest {
        val target = complaint(id = "c9")
        val list = FakeAdminListRepository({ Result.success(listOf(target)) })
        val actions = RecordingAdminActionRepository()
        val vm = viewModel(list = list, actions = actions)
        val effects = mutableListOf<AdminComplaintEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(AdminComplaintIntent.OnRowClick(target))
        vm.submit(AdminComplaintIntent.OnSelectAction(AdminActionDialogMode.STATUS_CHANGE))
        vm.submit(AdminComplaintIntent.OnSubmitStatusChange(ComplaintStatus.RESOLVED))

        assertEquals(listOf("status:c9:RESOLVED"), actions.calls)
        assertEquals(AdminActionDialogMode.NONE, vm.state.value.actionDialogMode)
        assertEquals(2, list.loads, "success reloads (init + post-action)")
        assertEquals(
            listOf<AdminComplaintEffect>(
                AdminComplaintEffect.ShowActionSuccess(AdminComplaintAction.STATUS_UPDATED),
            ),
            effects,
        )
        collector.cancel()
    }

    @Test
    fun closureReason_failure_keepsDialog_emitsFailure() = runTest {
        val target = complaint(id = "c9")
        val list = FakeAdminListRepository({ Result.success(listOf(target)) })
        val actions = RecordingAdminActionRepository(result = Result.failure(RuntimeException("firestore boom")))
        val vm = viewModel(list = list, actions = actions)
        val effects = mutableListOf<AdminComplaintEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(AdminComplaintIntent.OnRowClick(target))
        vm.submit(AdminComplaintIntent.OnSelectAction(AdminActionDialogMode.CLOSURE_REASON))
        vm.submit(AdminComplaintIntent.OnSubmitClosureReason("duplicate of c1"))

        assertEquals(listOf("closure:c9:duplicate of c1"), actions.calls)
        assertEquals(AdminActionDialogMode.CLOSURE_REASON, vm.state.value.actionDialogMode, "failure keeps the dialog")
        assertFalse(vm.state.value.isSubmittingAction)
        assertEquals(1, list.loads, "no reload on failure")
        assertEquals(listOf<AdminComplaintEffect>(AdminComplaintEffect.ShowActionFailure), effects)
        collector.cancel()
    }

    @Test
    fun toggleStatsCard_flipsVisibility() = runTest {
        val vm = viewModel()
        assertTrue(vm.state.value.showStats, "native default-visible posture")

        vm.submit(AdminComplaintIntent.OnToggleStatsCard)
        assertFalse(vm.state.value.showStats)

        vm.submit(AdminComplaintIntent.OnToggleStatsCard)
        assertTrue(vm.state.value.showStats)
    }
}
