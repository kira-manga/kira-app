package me.manga.yamiapk.presentation.features.complaint.usecase

import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.complaint.repository.ComplaintRepository
import javax.inject.Inject

class UpdateComplaintUseCase @Inject constructor(
    private val repo: ComplaintRepository
) {
    suspend operator fun invoke(complaint: Complaint) {
        return repo.updateComplaint(complaint)
    }
}
