package me.manga.yamiapk.presentation.features.complaint.usecase

import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.complaint.repository.ComplaintRepository
import javax.inject.Inject

class GetAllComplaintUseCase @Inject constructor(
    private val repo: ComplaintRepository
) {
    suspend operator fun invoke(): List<Complaint> {
        return repo.getAllComplaints()
    }
}
