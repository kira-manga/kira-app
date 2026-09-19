package me.manga.kira.navigation.routes

import androidx.lifecycle.viewModelScope
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.di.GRAPH_BASE
import me.manga.kira.di.GRAPH_EDIT_ID
import me.manga.kira.di.GRAPH_REPLY_ID
import me.manga.kira.di.GRAPH_REPLY_PARENT
import me.manga.kira.domain.model.complaint.BackendNoticeKey
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.navigation.routes.ComplaintBackendAction.DELETE
import me.manga.kira.navigation.routes.ComplaintBackendAction.EDIT
import me.manga.kira.navigation.routes.ComplaintBackendAction.REPLY
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackIntent
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyActivity
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyIntent
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Decoded candidate reads precede every positive action; no injected detail state grants a slot. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // Five selectors share small wire, freshness and lifetime assertion steps.
class ComplaintBackendNoticeReplyHostTest {
    @BeforeTest
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun knownSystemNoticesUseFreshNoTagDetailAndOnlyExplicitReplySubmit() =
        runTest {
            for (identity in BackendNoticeKey.entries) {
                withNoticeReplyHost(systemNoticeRow(identity.key)) {
                    val history = assertIs<ComplaintHistory.Backend>(detail.history.state.value.history)
                    val listed = history.notices.single()
                    assertTrue(history.items.isEmpty() && detailRequests.isEmpty())
                    assertNull(detail.detail.state.value.selectedId)
                    assertNoWriteOrSetup()
                    val selected = assertIs<ComplaintDetail.Notice>(select())
                    assertEquals(listed.id, selected.item.id)
                    assertEquals(identity.key, selected.item.noticeKey)
                    assertNotSame(listed, selected.item)
                    assertNull(row["actionTag"])
                    val path = "$GRAPH_BASE/api/v1/complaints/$GRAPH_REPLY_PARENT"
                    assertEquals(path, detailRequests.single().url.toString())
                    assertNoticeReplyOnly(selected)
                    submitExplicitReply(open(selected))
                }
            }
        }

    @Test
    fun knownOwnedNoticeReplyUsesItsSelectedChildIdRatherThanTheNoticeAncestor() =
        runTest {
            withNoticeReplyHost(ownedNoticeReplyRow()) {
                val selected = assertIs<ComplaintDetail.Owned>(select())
                val child = assertIs<ComplaintOwnerRow.NoticeReply>(selected.item)
                assertEquals(GRAPH_REPLY_PARENT, child.id)
                assertEquals(GRAPH_EDIT_ID, child.replyToId)
                assertEquals("REPLY", row.getValue("kind").jsonPrimitive.content)
                assertEquals(JsonNull, row["subject"])
                assertTrue(ComplaintBackendAction.entries.all { owner.canOpen(it, selected) })
                submitExplicitReply(open(selected))
            }
        }

    @Test
    fun absentLoadingUnknownAndRemovedHistoryNoticesRejectSavedReadCallbacks() =
        runTest {
            withNoticeReplyHost {
                val listed = assertIs<ComplaintHistory.Backend>(detail.history.state.value.history).notices.single()
                val callback = { detail.select(listed.id) }
                assertSelectionIgnored { detail.select(GRAPH_EDIT_ID) }
                val gate = CompletableDeferred<Unit>()
                historyGate = gate
                refreshHistory()
                assertTrue(detail.history.state.value.isLoading)
                assertSelectionIgnored(callback)
                historyRow = systemNoticeRow(UNKNOWN_NOTICE_KEY)
                gate.complete(Unit)
                idle()
                assertHistoryKey(UNKNOWN_NOTICE_KEY)
                assertSelectionIgnored(callback)
                historyGate = null
                historyRow = null
                refreshHistory()
                assertHistoryKey(null)
                assertSelectionIgnored(callback)
                historyRow = systemNoticeRow()
                refreshHistory()
                assertHistoryKey(BackendNoticeKey.CONTENT_POLICY.key)
                detail.close()
                assertSelectionIgnored(callback)
                assertNoWriteOrSetup()
            }
        }

    @Test
    fun freshNoticeSnapshotIsRequiredAfterLoadingFailureUnknownKeyAndNotFound() =
        runTest {
            withNoticeReplyHost {
                val old = assertIs<ComplaintDetail.Notice>(select())
                assertRejected(ComplaintDetail.Notice(old.item))
                assertLoadingAndFailedReadFence(old)
                row = systemNoticeRow(UNKNOWN_NOTICE_KEY)
                refreshDetail()
                val unknown = assertIs<ComplaintDetail.Notice>(detail.detail.state.value.detail)
                assertSame(unknown, detail.detail.state.value.actionTarget())
                assertRejected(old)
                assertRejected(unknown)
                row = systemNoticeRow(BackendNoticeKey.SOURCE_REQUIREMENTS.key)
                refreshDetail()
                val fresh = assertIs<ComplaintDetail.Notice>(detail.detail.state.value.detail)
                assertNotSame(old, fresh)
                assertTrue(owner.canOpen(REPLY, fresh))
                assertRejected(old)
                detailStatus = HttpStatusCode.NotFound
                refreshDetail()
                assertSame(ComplaintDetail.Unavailable, detail.detail.state.value.detail)
                assertNull(detail.detail.state.value.actionTarget())
                assertRejected(fresh)
                assertNoWriteOrSetup()
            }
        }

    @Test
    fun noticeReplyKeepsItsSlotThroughChildDrainRecoveryAndColdReopen() =
        runTest {
            withNoticeReplyHost {
                val opening = open()
                val recovery = drainToRecovery(opening)
                assertRecoveryAndColdReopen(opening, recovery)
                assertNoWriteOrSetup()
            }
        }

    private fun ComplaintBackendNoticeReplyFixture.assertNoticeReplyOnly(target: ComplaintDetail.Notice) {
        val reads = credentials.reads
        assertTrue(owner.canOpen(REPLY, target))
        for (action in listOf(EDIT, DELETE)) {
            assertFalse(owner.canOpen(action, target))
            owner.open(action, target)
            assertFailsWith<IllegalStateException> { ComplaintBackendActionOpening(app.koin, action, target) }
        }
        idle()
        assertNull(owner.state.value)
        assertEquals(reads, credentials.reads)
        assertNoWriteOrSetup()
    }

    private fun ComplaintBackendNoticeReplyFixture.submitExplicitReply(opening: ComplaintBackendActionOpening) {
        changeBody(opening)
        assertNoWriteOrSetup()
        val reads = backend.historyCalls
        opening.replyModel.submit(ComplaintReplyIntent.Submit)
        idle()
        assertExactReply()
        val result = assertIs<ComplaintReplyResult.Attempt>(opening.replyModel.state.value.result)
        val completed = assertIs<ComplaintReportAttempt.Completed>(result.attempt)
        val applied = assertIs<ComplaintReportApplication.Applied>(completed.application)
        assertEquals(GRAPH_REPLY_ID, applied.id)
        assertEquals(1L, applied.version)
        assertEquals(ComplaintReplyActivity.TERMINAL, opening.replyModel.state.value.activity)
        assertFalse(opening.replyModel.state.value.canRetry)
        assertEquals(reads, backend.historyCalls)
    }

    private fun ComplaintBackendNoticeReplyFixture.assertSelectionIgnored(callback: () -> Unit) {
        val reads = detailRequests.size
        callback()
        idle()
        assertEquals(reads, detailRequests.size)
        assertNull(detail.detail.state.value.selectedId)
    }

    private fun ComplaintBackendNoticeReplyFixture.assertHistoryKey(key: String?) {
        val state = detail.history.state.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        val notices = assertIs<ComplaintHistory.Backend>(state.history).notices
        assertEquals(listOfNotNull(key), notices.map { it.noticeKey })
    }

    private fun ComplaintBackendNoticeReplyFixture.assertRejected(target: ComplaintDetail) {
        val reads = credentials.reads
        for (action in ComplaintBackendAction.entries) {
            assertFalse(owner.canOpen(action, target))
            owner.open(action, target)
        }
        idle()
        assertNull(owner.state.value)
        assertEquals(reads, credentials.reads)
    }

    private fun ComplaintBackendNoticeReplyFixture.assertLoadingAndFailedReadFence(target: ComplaintDetail) {
        val gate = CompletableDeferred<Unit>()
        detailGate = gate
        refreshDetail()
        assertTrue(detail.detail.state.value.isLoading)
        assertRejected(target)
        detailStatus = HttpStatusCode.ServiceUnavailable
        gate.complete(Unit)
        idle()
        assertTrue(detail.detail.state.value.isStale)
        assertNull(detail.detail.state.value.actionTarget())
        assertRejected(target)
        detailGate = null
        detailStatus = HttpStatusCode.OK
    }

    private suspend fun ComplaintBackendNoticeReplyFixture.drainToRecovery(
        opening: ComplaintBackendActionOpening,
    ): ComplaintBackendRequestOpening {
        val hold = holdReply(opening)
        val delivery = collectFinish(opening)
        val reads = credentials.reads
        requestClose(opening, recovery = true)
        val work = hold.entered.await()
        assertTrue(hold.closing.isCompleted && work.isCancelled && !work.isCompleted)
        assertTrue(opening.isUiClosed())
        assertSame(opening, assertIs<ComplaintBackendActionSlot.Action>(owner.state.value).opening)
        assertFalse(owner.canSelect || delivery.isCompleted)
        assertEquals(reads, credentials.reads)
        hold.release.complete(Unit)
        idle()
        assertTrue(work.isCompleted && delivery.isCompleted)
        assertFalse(opening.replyModel.viewModelScope.isActive)
        assertTrue(credentials.reads > reads)
        checkComplaintBackendActionBindings(app.koin)
        return assertIs<ComplaintBackendActionSlot.Recovery>(owner.state.value).opening
    }

    private fun ComplaintBackendNoticeReplyFixture.assertRecoveryAndColdReopen(
        old: ComplaintBackendActionOpening,
        recovery: ComplaintBackendRequestOpening,
    ) {
        assertSame(SettingsFeedbackEntry.General, recovery.viewModel.state.value.entry)
        assertEquals("", recovery.viewModel.state.value.draft.body)
        assertFalse(recovery.viewModel.state.value.canRetry)
        val closed = collectFinish(recovery)
        recovery.viewModel.submit(SettingsFeedbackIntent.Close)
        idle()
        assertTrue(closed.isCompleted)
        assertNull(detail.detail.state.value.selectedId)
        val replacement = open()
        assertNotSame(old.replyModel, replacement.replyModel)
        assertEquals("", replacement.replyModel.state.value.body)
        assertFalse(replacement.replyModel.state.value.canRetry)
        val reads = credentials.reads
        owner.actionFinished(old, openRecovery = true)
        owner.recoveryFinished(recovery)
        idle()
        assertSame(replacement, assertIs<ComplaintBackendActionSlot.Action>(owner.state.value).opening)
        assertEquals(reads, credentials.reads)
        assertDisposalFences(replacement)
    }

    private fun ComplaintBackendNoticeReplyFixture.assertDisposalFences(opening: ComplaintBackendActionOpening) {
        owner.onAbandoned()
        idle()
        owner.actionFinished(opening, openRecovery = true)
        idle()
        assertNull(owner.state.value)
        assertFalse(owner.canSelect || opening.replyModel.viewModelScope.isActive)
        assertTrue(detail.history.viewModelScope.isActive && detail.detail.viewModelScope.isActive)
        assertTrue(backend.owners.values.none { it.closed })
    }
}

private const val UNKNOWN_NOTICE_KEY = "complaints.notice.future-policy"
