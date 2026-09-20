package me.manga.kira.data.repository

import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.repository.ComplaintActionRepository

/** Candidate backend-history graph has no write implementation. Never delegate these calls to Firestore. */
class ReadOnlyComplaintActionRepository : ComplaintActionRepository {
    override suspend fun replyToComplaint(
        parent: ComplaintSummary,
        body: String,
    ): Result<Unit> = unavailable()

    override suspend fun editComplaint(
        original: ComplaintSummary,
        subject: String,
        body: String,
    ): Result<Unit> = unavailable()

    override suspend fun deleteComplaint(id: String): Result<Unit> = unavailable()

    private fun unavailable(): Result<Unit> =
        Result.failure(
            UnsupportedOperationException("Complaint history is read only"),
        )
}
