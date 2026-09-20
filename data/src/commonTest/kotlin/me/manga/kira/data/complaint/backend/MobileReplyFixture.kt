package me.manga.kira.data.complaint.backend

import me.manga.kira.domain.model.feedback.ComplaintLiveReply
import me.manga.kira.domain.model.feedback.ComplaintReplyDraft
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.repository.ComplaintReplyRepository
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

internal const val MOBILE_REPLY_PARENT = "33333333-3333-5333-8333-333333333333"

internal fun mobileReplyRequest(
    parentId: String = MOBILE_REPLY_PARENT,
    body: String = "Reply",
    key: String = Fixtures.KEY,
    id: String = Fixtures.OTHER_ID,
): ComplaintReplyRequest =
    assertIs<ComplaintReplyRequestResult.Accepted>(
        ComplaintReplyRequest.normalize(
            assertNotNull(ComplaintReportIdentity.checked(id, key, Fixtures.SCOPE)),
            parentId,
            body,
            ComplaintReportMetadataInput(null, "", "", ""),
        ),
    ).request

internal suspend fun ComplaintReplyRepository.preparedConsumerReply(
    draft: ComplaintReplyDraft = ComplaintReplyDraft(MOBILE_REPLY_PARENT, "Reply"),
): ComplaintLiveReply = assertIs<ComplaintReplyPreparation.Ready>(prepare(draft).reportSuccess()).reply
