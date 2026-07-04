package me.manga.kira.data.repository

import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.presentation.features.complaint.model.Complaint as LegacyComplaint
import me.manga.kira.presentation.features.complaint.model.ComplaintStatus as LegacyComplaintStatus
import me.manga.kira.presentation.features.complaint.model.ComplaintType as LegacyComplaintType
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository as LegacyComplaintRepository
import me.manga.kira.presentation.features.complaint.usecase.DeleteComplaintUseCase
import me.manga.kira.presentation.features.complaint.usecase.GetUserComplaintUseCase
import me.manga.kira.presentation.features.complaint.usecase.SendComplaintUseCase
import me.manga.kira.presentation.features.complaint.usecase.UpdateComplaintUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #9 — edit/reply must preserve the FULL legacy complaint metadata (device model/osRelease/
 * manufacturer + admin reason fields), not just the 5 carved ComplaintSummary fields. The fix
 * re-fetches the legacy doc and copies it; this asserts no metadata key is dropped.
 */
class ComplaintActionRepositoryMetadataTest {

    private val fullMetadata: Map<String, Any> = mapOf(
        "model" to "Pixel 8",
        "osRelease" to "14",
        "manufacturer" to "Google",
        "appVersion" to "1.2.3",
        "reasonAddedBy" to "admin@x",
        "reasonAddedAt" to "2026-06-01",
    )

    private val legacyDoc = LegacyComplaint(
        id = "c1",
        userId = "u1",
        type = LegacyComplaintType.TECHNICAL,
        subject = "original subject",
        body = "original body",
        createdAt = null,
        status = LegacyComplaintStatus.OPEN,
        metadata = fullMetadata,
    )

    private class FakeLegacyComplaintRepository(
        private val docs: List<LegacyComplaint>,
    ) : LegacyComplaintRepository {
        var sent: LegacyComplaint? = null
        var updated: LegacyComplaint? = null
        override suspend fun sendComplaint(complaint: LegacyComplaint): String { sent = complaint; return "newId" }
        override suspend fun getAllComplaints(): List<LegacyComplaint> = docs
        override suspend fun getComplaintsByUser(userId: String): List<LegacyComplaint> =
            docs.filter { it.userId == userId }
        override suspend fun updateComplaint(complaint: LegacyComplaint) { updated = complaint }
        override suspend fun deleteComplaint(complaintId: String) = Unit
    }

    private fun build(repo: FakeLegacyComplaintRepository) = ComplaintActionRepositoryImpl(
        send = SendComplaintUseCase(repo),
        update = UpdateComplaintUseCase(repo),
        delete = DeleteComplaintUseCase(repo),
        getUser = GetUserComplaintUseCase(repo),
    )

    private fun summary() = ComplaintSummary(
        id = "c1",
        userId = "u1",
        type = ComplaintType.TECHNICAL,
        subject = "original subject",
        body = "original body",
        createdAt = null,
        status = ComplaintStatus.OPEN,
    )

    @Test
    fun edit_preservesFullMetadata_andUpdatesSubjectBody() = runTest {
        val repo = FakeLegacyComplaintRepository(listOf(legacyDoc))
        build(repo).editComplaint(summary(), subject = "edited subject", body = "edited body")

        val updated = repo.updated!!
        assertEquals("edited subject", updated.subject)
        assertEquals("edited body", updated.body)
        assertEquals(fullMetadata, updated.metadata, "edit must keep every metadata key (model/osRelease/admin reason)")
    }

    @Test
    fun reply_inheritsParentMetadata_plusReplyTo() = runTest {
        val repo = FakeLegacyComplaintRepository(listOf(legacyDoc))
        build(repo).replyToComplaint(summary(), body = "a reply")

        val sent = repo.sent!!
        assertEquals("a reply", sent.body)
        fullMetadata.forEach { (k, v) ->
            assertEquals(v, sent.metadata?.get(k), "reply inherits parent metadata key $k")
        }
        assertEquals("c1", sent.metadata?.get("replyto"), "reply correlates to its parent via replyto")
        assertTrue(sent.id.isEmpty(), "a reply is a new complaint (no id)")
    }
}
