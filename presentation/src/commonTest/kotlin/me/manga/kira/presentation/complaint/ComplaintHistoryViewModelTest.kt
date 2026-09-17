package me.manga.kira.presentation.complaint

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.model.complaint.ComplaintHistoryPlatform
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintOwnerFields
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintHistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = mutableListOf<ComplaintViewModel>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun refreshAndFailureKeepLastSourceTaggedRowsVisibleWithoutLegacyActionShape() = runTest {
        val history = ComplaintHistory.Backend(emptyList(), listOf(ownerRow()))
        val list = TypedHistoryRepository { AppResult.Success(history) }
        val vm = viewModel(list)
        val release = CompletableDeferred<Unit>()
        list.load = { release.await(); AppResult.Failure(AppError.Network.Http(503)) }
        vm.submit(ComplaintIntent.OnRetry)
        assertTrue(vm.state.value.isLoading)
        assertSame(history, vm.state.value.history)
        assertEquals(1, vm.state.value.backendItems.size)
        assertTrue(vm.state.value.all.isEmpty() && vm.state.value.filtered.isEmpty())
        assertFalse(vm.state.value.legacyActionsAllowed)
        release.complete(Unit)
        assertTrue(vm.state.value.isStale)
        assertFalse(vm.state.value.isLoading)
        assertSame(history, vm.state.value.history)
        assertIs<AppError.Network.Http>(vm.state.value.error)
        assertNull(vm.state.value.error?.cause)
    }

    @Test
    fun genuineEmptySnapshotSurvivesFailureAndIsNotInitialUnavailability() = runTest {
        val empty = ComplaintHistory.Backend(emptyList(), emptyList())
        val list = TypedHistoryRepository { AppResult.Success(empty) }
        val vm = viewModel(list)
        list.load = { AppResult.Failure(AppError.Network.NoConnectivity()) }
        vm.submit(ComplaintIntent.OnRetry)
        assertSame(empty, vm.state.value.history)
        assertTrue(vm.state.value.isStale)
        val unavailable = viewModel(TypedHistoryRepository { AppResult.Failure(AppError.Network.NoConnectivity()) })
        assertNull(unavailable.state.value.history)
        assertFalse(unavailable.state.value.isStale)
        assertFalse(unavailable.state.value.legacyActionsAllowed)
    }

    @Test
    fun legacySnapshotKeepsGenuineRowsAndSelectedLegacyActionsOnRefreshError() = runTest {
        val row = legacyRow()
        val history = ComplaintHistory.Legacy(listOf(row))
        val list = TypedHistoryRepository { AppResult.Success(history) }
        val vm = viewModel(list)
        list.load = { error("synthetic raw SDK content must not escape") }
        vm.submit(ComplaintIntent.OnRetry)
        assertSame(history, vm.state.value.history)
        assertEquals(listOf(row), vm.state.value.filtered)
        assertTrue(vm.state.value.isStale && vm.state.value.legacyActionsAllowed)
        assertNull(vm.state.value.error?.cause)
        assertEquals("complaint_history_failed", assertIs<AppError.Unexpected>(vm.state.value.error).message)
    }

    @Test
    fun everyInjectedLegacyActionIntentIsRejectedByTheBackendViewModel() = runTest {
        val actions = CountingActions()
        val vm = viewModel(TypedHistoryRepository { AppResult.Success(ComplaintHistory.Backend(emptyList(), listOf(ownerRow()))) }, actions)
        val effects = mutableListOf<ComplaintEffect>()
        val collector = backgroundScope.launch(dispatcher) { vm.effects.collect { effects += it } }
        listOf(
            ComplaintIntent.OnRowClick(legacyRow()),
            ComplaintIntent.OnSelectAction(ActionDialogMode.REPLY),
            ComplaintIntent.OnSubmitReply("synthetic body"),
            ComplaintIntent.OnSubmitEdit("synthetic subject", "synthetic body"),
            ComplaintIntent.OnConfirmDelete,
            ComplaintIntent.OnCopyBody,
            ComplaintIntent.OnDismissActionDialog,
        ).forEach(vm::submit)
        assertEquals(0, actions.calls)
        assertEquals(ActionDialogMode.NONE, vm.state.value.actionDialogMode)
        assertNull(vm.state.value.activeComplaint)
        assertFalse(vm.state.value.isSubmittingAction)
        assertTrue(effects.isEmpty())
        collector.cancel()
    }

    @Test
    fun futureStatusIsNeverTheLiteralUnknownFilterAndUnknownKindTokenIsNotSearchableProse() = runTest {
        val literal = ownerRow("literal", ComplaintHistoryStatus.Known(ComplaintStatus.UNKNOWN))
        val future = ownerRow("future", ComplaintHistoryStatus.Unrecognized)
        val unknown = UnknownComplaintItem("unknown-kind-id", "FUTURE_KIND_TOKEN", TIME, TIME)
        val vm = viewModel(TypedHistoryRepository {
            AppResult.Success(ComplaintHistory.Backend(emptyList(), listOf(literal, future, unknown)))
        })
        vm.submit(ComplaintIntent.OnStatusFilter(ComplaintStatus.UNKNOWN))
        assertEquals(listOf("literal"), vm.state.value.backendItems.map { it.id })
        vm.submit(ComplaintIntent.OnStatusFilter(null))
        vm.submit(ComplaintIntent.OnSearchChange("FUTURE_KIND_TOKEN"))
        assertTrue(vm.state.value.backendItems.isEmpty())
        vm.submit(ComplaintIntent.OnSearchChange("unknown-kind-id"))
        assertEquals(listOf(unknown), vm.state.value.backendItems)
    }

    @Test
    fun canceledIntermediateRetryCannotOvertakeOldestResponseCleanupOrPublishItsResult() = runTest {
        val closing = CompletableDeferred<Unit>()
        val allowClose = CompletableDeferred<Unit>()
        var closed = false
        var calls = 0
        val latest = ComplaintHistory.Backend(emptyList(), emptyList())
        val list = TypedHistoryRepository {
            calls++
            if (calls == 1) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        closing.complete(Unit)
                        allowClose.await()
                        closed = true
                    }
                }
            } else {
                assertTrue(closed)
                AppResult.Success(latest)
            }
        }
        val vm = viewModel(list)
        try {
            vm.submit(ComplaintIntent.OnRetry)
            closing.await()
            vm.submit(ComplaintIntent.OnRetry)
            runCurrent()
            assertEquals(1, calls)
            assertNull(vm.state.value.history)
            allowClose.complete(Unit)
            runCurrent()
            assertEquals(2, calls)
            assertSame(latest, vm.state.value.history)
            assertFalse(vm.state.value.isLoading)
            assertNull(vm.state.value.error)
        } finally {
            allowClose.complete(Unit)
        }
    }

    private fun viewModel(list: TypedHistoryRepository, actions: CountingActions = CountingActions()): ComplaintViewModel =
        ComplaintViewModel(
            ObserveUserComplaintsUseCase(list), ReplyToComplaintUseCase(actions), EditComplaintUseCase(actions), DeleteComplaintUseCase(actions),
        ).also { models += it }

    private class TypedHistoryRepository(var load: suspend () -> AppResult<ComplaintHistory>) : ComplaintListRepository {
        override suspend fun loadUserComplaints(): AppResult<ComplaintHistory> = load()
    }

    private class CountingActions : ComplaintActionRepository {
        var calls = 0
        private fun called(): Result<Unit> { calls++; return Result.success(Unit) }
        override suspend fun replyToComplaint(parent: ComplaintSummary, body: String): Result<Unit> = called()
        override suspend fun editComplaint(original: ComplaintSummary, subject: String, body: String): Result<Unit> = called()
        override suspend fun deleteComplaint(id: String): Result<Unit> = called()
    }

    private fun legacyRow() = ComplaintSummary("legacy", "genuine-legacy-user", ComplaintType.TECHNICAL, "Subject", "Body", null, ComplaintStatus.OPEN)

    private fun ownerRow(id: String = "backend", status: ComplaintHistoryStatus = ComplaintHistoryStatus.Known(ComplaintStatus.OPEN)): ComplaintOwnerRow =
        ComplaintOwnerRow.Report(
            ComplaintOwnerFields(id, "Synthetic body", status, TIME, TIME, 1, "fixture-tag", null, ComplaintHistoryPlatform.ANDROID, null, null, null, null),
            ComplaintHistoryType.Known(ComplaintType.TECHNICAL), "Synthetic subject",
        )

    private companion object {
        val TIME: Instant = Instant.parse("2026-09-17T00:00:00Z")
    }
}
