package me.manga.kira.domain.usecase.complaint

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.repository.ComplaintListRepository

/** One-shot history read; the data boundary owns source selection and typed failure mapping. */
class ObserveUserComplaintsUseCase(
    private val repository: ComplaintListRepository,
) {
    suspend operator fun invoke(): AppResult<ComplaintHistory> = repository.loadUserComplaints()
}
