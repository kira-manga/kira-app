package me.manga.kira.navigation.routes

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.navigation.routes.ComplaintBackendAction.EDIT
import me.manga.kira.platform.storage.PendingComplaintSlot
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackIntent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Lifetime tests consume actual VM effects and hold cancellation-safe children, not UI CLOSED flags. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // Four focused selectors share bounded lifetime assertions.
class ComplaintBackendActionHostLifecycleTest {
    @BeforeTest
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun everyActionKeepsItsSlotUntilChildDrainBeforeSameCandidateRecoveryAndFencesOldRecovery() =
        runTest {
            for (action in ComplaintBackendAction.entries) {
                withActionHost {
                    val opening = open(action)
                    val hold = holdAction(opening)
                    val delivery = collectFinish(opening)
                    val reads = credentials.reads
                    requestClose(opening, recovery = true)
                    assertHeldSlot(opening, hold, delivery)
                    assertEquals(reads, credentials.reads)
                    hold.release.complete(Unit)
                    idle()
                    assertTrue(hold.entered.await().isCompleted && delivery.isCompleted)
                    assertFalse(opening.viewModel.viewModelScope.isActive)
                    val recovery = assertIs<ComplaintBackendActionSlot.Recovery>(owner.state.value).opening
                    assertSame(SettingsFeedbackEntry.General, recovery.viewModel.state.value.entry)
                    assertEquals("", recovery.viewModel.state.value.draft.body)
                    assertFalse(recovery.viewModel.state.value.canRetry)
                    assertTrue(credentials.reads > reads)
                    checkComplaintBackendActionBindings(app.koin)
                    replaceRecoveryAndRejectOldEffect(recovery)
                    assertNoWriteOrSetup()
                }
            }
        }

    @Test
    fun plainCloseAlsoWaitsForDrainAndColdReselectionCannotInheritDraftOrOldEffects() =
        runTest {
            for (action in ComplaintBackendAction.entries) {
                withActionHost {
                    val target = select()
                    val opening = open(action, target)
                    val hold = holdAction(opening)
                    val delivery = collectFinish(opening)
                    requestClose(opening, recovery = false)
                    assertHeldSlot(opening, hold, delivery)
                    hold.release.complete(Unit)
                    idle()
                    assertTrue(delivery.isCompleted)
                    assertNull(owner.state.value)
                    assertFalse(owner.canOpen(action, target))
                    val replacement = open(action)
                    assertNotSame(opening.viewModel, replacement.viewModel)
                    assertColdState(replacement, assertIs<ComplaintOwnerRow.Report>(target.item))
                    val reads = credentials.reads
                    owner.actionFinished(opening, openRecovery = true)
                    assertSame(replacement, assertIs<ComplaintBackendActionSlot.Action>(owner.state.value).opening)
                    assertEquals(reads, credentials.reads)
                    assertNoWriteOrSetup()
                }
            }
        }

    @Test
    fun forgottenAbandonedAndExplicitDisposalFenceAllActionsWithoutClosingSiblingStores() =
        runTest {
            val disposals = listOf(
                ComplaintBackendActionHostOwner::onForgotten,
                ComplaintBackendActionHostOwner::onAbandoned,
                ComplaintBackendActionHostOwner::close,
            )
            for (action in ComplaintBackendAction.entries) {
                for (dispose in disposals) withActionHost { assertDisposedAction(action, dispose) }
            }
        }

    @Test
    fun uncertainEditDisposalPreservesExactPendingAndANewOwnerHasNoLiveRetryOrProse() =
        runTest {
            withActionHost {
                edit.rejectDirect = true
                val opening = open(EDIT)
                changeDraft(opening)
                submit(opening)
                val vm = assertIs<ComplaintBackendActionPanel.Edit>(opening.panel).viewModel
                assertTrue(vm.state.value.canRetry)
                val pending = edit.pending.slots.single()
                edit.assertExactEdit()
                owner.onAbandoned()
                idle()
                assertTrue(opening.isUiClosed())
                assertFalse(vm.viewModelScope.isActive)
                assertEquals("", vm.state.value.draft.body)
                assertColdOwnerKeepsPending(pending)
                assertTrue(backend.owners.values.none { it.closed })
                assertEquals(1, backend.mutationCalls)
                assertEquals(0, backend.credentials.writes + backend.deletionCalls + backend.generations)
            }
        }

    private suspend fun ComplaintBackendActionHostFixture.assertHeldSlot(
        opening: ComplaintBackendActionOpening,
        hold: ActionHostReadHold,
        delivery: Job,
    ) {
        val work = hold.entered.await()
        assertTrue(hold.closing.isCompleted && work.isCancelled)
        assertFalse(work.isCompleted)
        assertTrue(opening.isUiClosed())
        assertSame(opening, assertIs<ComplaintBackendActionSlot.Action>(owner.state.value).opening)
        assertFalse(owner.canSelect || delivery.isCompleted)
    }

    private fun ComplaintBackendActionHostFixture.replaceRecoveryAndRejectOldEffect(old: ComplaintBackendRequestOpening) {
        val closed = collectFinish(old)
        old.viewModel.submit(SettingsFeedbackIntent.Close)
        idle()
        assertTrue(closed.isCompleted)
        assertNull(owner.state.value)
        val action = open(EDIT)
        collectFinish(action)
        requestClose(action, recovery = true)
        val replacement = assertIs<ComplaintBackendActionSlot.Recovery>(owner.state.value).opening
        assertNotSame(old.viewModel, replacement.viewModel)
        val reads = credentials.reads
        owner.recoveryFinished(old)
        assertSame(replacement, assertIs<ComplaintBackendActionSlot.Recovery>(owner.state.value).opening)
        assertEquals(reads, credentials.reads)
        owner.onForgotten()
        idle()
        assertFalse(replacement.viewModel.viewModelScope.isActive)
        assertTrue(detail.history.viewModelScope.isActive && detail.detail.viewModelScope.isActive)
        assertTrue(backend.owners.values.none { it.closed })
    }

    private suspend fun ComplaintBackendActionHostFixture.assertDisposedAction(
        action: ComplaintBackendAction,
        dispose: (ComplaintBackendActionHostOwner) -> Unit,
    ) {
        val sibling = ComplaintBackendRequestOpening(app.koin, SettingsFeedbackEntry.General)
        try {
            idle()
            sibling.viewModel.submit(SettingsFeedbackIntent.ChangeBody("Sibling draft"))
            idle()
            val opening = open(action)
            val hold = holdAction(opening)
            val delivery = collectFinish(opening)
            dispose(owner)
            idle()
            assertTrue(opening.isUiClosed())
            assertNull(owner.state.value)
            assertFalse(owner.canSelect)
            owner.actionFinished(opening, openRecovery = true)
            hold.release.complete(Unit)
            idle()
            assertTrue(hold.entered.await().isCompleted)
            assertFalse(delivery.isCompleted)
            assertSiblingAlive(sibling)
            assertNoWriteOrSetup()
        } finally {
            sibling.close()
            idle()
        }
    }

    private fun ComplaintBackendActionHostFixture.assertSiblingAlive(sibling: ComplaintBackendRequestOpening) {
        assertTrue(sibling.viewModel.viewModelScope.isActive)
        assertEquals("Sibling draft", sibling.viewModel.state.value.draft.body)
        assertTrue(detail.history.viewModelScope.isActive && detail.detail.viewModelScope.isActive)
        assertTrue(backend.owners.values.none { it.closed })
    }

    private fun ComplaintBackendActionHostFixture.assertColdOwnerKeepsPending(pending: PendingComplaintSlot) {
        val replacementOwner = ComplaintBackendActionHostOwner(app.koin, detail.detail)
        try {
            val fresh = select()
            replacementOwner.open(EDIT, fresh)
            idle()
            val replacement = assertIs<ComplaintBackendActionSlot.Action>(replacementOwner.state.value).opening
            assertColdState(replacement, assertIs<ComplaintOwnerRow.Report>(fresh.item))
            assertTrue(pending.sameAs(edit.pending.slots.single()))
            assertEquals(0, edit.pending.deletes)
            assertEquals(2, edit.pending.transitions.size)
        } finally {
            replacementOwner.close()
            idle()
        }
    }
}

private fun assertColdState(opening: ComplaintBackendActionOpening, original: ComplaintOwnerRow.Report) {
    when (val panel = opening.panel) {
        is ComplaintBackendActionPanel.Reply -> {
            assertEquals("", panel.viewModel.state.value.body)
            assertFalse(panel.viewModel.state.value.canRetry)
        }
        is ComplaintBackendActionPanel.Edit -> {
            assertEquals(original.subject, panel.viewModel.state.value.draft.subject)
            assertEquals(original.fields.body, panel.viewModel.state.value.draft.body)
            assertFalse(panel.viewModel.state.value.canRetry)
        }
        is ComplaintBackendActionPanel.Delete -> {
            assertEquals(original.subject, panel.viewModel.state.value.preview.subject)
            assertEquals(original.fields.body, panel.viewModel.state.value.preview.body)
            assertFalse(panel.viewModel.state.value.canRetry)
        }
    }
}
