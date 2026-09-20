package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail

/** Explicit existing-identity read. Failure is not an unavailable parent and cannot enroll or mutate. */
interface ComplaintDetailRepository {
    suspend fun loadComplaintDetail(id: String): AppResult<ComplaintDetail>
}
