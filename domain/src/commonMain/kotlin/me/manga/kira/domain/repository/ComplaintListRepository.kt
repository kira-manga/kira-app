package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistory

/** One source-tagged snapshot. Failure is not a genuine empty list and carries no transport cause. */
interface ComplaintListRepository {
    suspend fun loadUserComplaints(): AppResult<ComplaintHistory>
}
