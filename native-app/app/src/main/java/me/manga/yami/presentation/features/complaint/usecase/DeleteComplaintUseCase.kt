package me.manga.yamiapk.presentation.features.complaint.usecase

import me.manga.yamiapk.presentation.features.complaint.repository.ComplaintRepository
import javax.inject.Inject

class DeleteComplaintUseCase @Inject constructor(
    private val repo: ComplaintRepository
) {
    suspend operator fun invoke(complaintId: String) {
        repo.deleteComplaint(complaintId)
    }
}