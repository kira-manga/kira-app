package me.manga.kira.domain.usecase.complaint

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.repository.ComplaintDetailRepository

/** One explicit detail read; it neither infers parent existence nor copies parent fields into a reply. */
class LoadComplaintDetailUseCase(
    private val repository: ComplaintDetailRepository,
) {
    suspend operator fun invoke(id: String): AppResult<ComplaintDetail> = repository.loadComplaintDetail(id)
}
