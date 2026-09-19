package me.manga.kira.presentation.settings.feedback.reply

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.complaint.BackendNoticeKey
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintNotice
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Selection-shape tests only; the composed host separately proves real history/detail decoding. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintNoticeReplyTargetTest {
    @BeforeTest
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun knownNoticeAndOwnedNoticeReplyCaptureTheSelectedIdNotTheAncestor() {
        for (identity in BackendNoticeKey.entries) {
            val notice = assertNotNull(ComplaintReplyTarget.capture(noticeDetail(identity.key)))
            assertEquals(PARENT_ID, notice.parentId)
            val child = assertNotNull(ComplaintReplyTarget.capture(noticeReplyDetail(identity.key)))
            assertEquals(PARENT_ID, child.parentId)
            assertNotEquals(REPLY_ID, child.parentId)
            assertEquals("ComplaintReplyTarget(redacted)", notice.toString())
            assertEquals(notice.toString(), child.toString())
        }
    }

    @Test
    fun unknownKeysAndUnrecognizedNoticeRepliesKeepTheReplyVmInert() =
        runTest {
            val unknown = listOf("complaints.notice.future-policy", "${BackendNoticeKey.CONTENT_POLICY.key} ")
            for (key in unknown) {
                assertInert(noticeDetail(key))
                assertInert(noticeReplyDetail(key))
            }
            for (identity in BackendNoticeKey.entries) {
                assertInert(noticeReplyDetail(identity.key, ComplaintHistoryStatus.Unrecognized))
            }
        }

    private fun TestScope.assertInert(detail: ComplaintDetail) {
        val target = ComplaintReplyTarget.capture(detail)
        assertNull(target)
        val fixture = ComplaintReplyPanelFixture()
        val vm = fixture.model(target)
        try {
            vm.submit(ComplaintReplyIntent.ChangeBody(REPLY_BODY))
            vm.submit(ComplaintReplyIntent.Submit)
            vm.submit(ComplaintReplyIntent.Retry)
            vm.submit(ComplaintReplyIntent.RefreshRecovery)
            runCurrent()
            assertFalse(vm.state.value.context.targetAvailable)
            assertFalse(vm.state.value.canSubmit || vm.state.value.canRetry)
            assertTrue(fixture.calls.isEmpty())
            fixture.assertNoRecoveryMutation()
        } finally {
            fixture.clear(vm)
            runCurrent()
        }
    }
}

private fun noticeDetail(key: String): ComplaintDetail.Notice =
    ComplaintDetail.Notice(ComplaintNotice(PARENT_ID, key, REPLY_TIME, REPLY_TIME, 1))

private fun noticeReplyDetail(
    key: String,
    status: ComplaintHistoryStatus = ComplaintHistoryStatus.Known(ComplaintStatus.OPEN),
): ComplaintDetail.Owned = ComplaintDetail.Owned(ComplaintOwnerRow.NoticeReply(replyFields(status), key, REPLY_ID))
