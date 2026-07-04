package me.manga.yamiapk.presentation.features.complaint.usecase

import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.complaint.repository.ComplaintRepository
import javax.inject.Inject


class SendComplaintUseCase @Inject constructor(
    private val repo: ComplaintRepository
) {
    suspend operator fun invoke(complaint: Complaint): String {
        // e.g. validate length, profanity check, etc.
        require(complaint.subject.isNotBlank()) { "Subject must not be empty" }
        require(complaint.body.length >= 8) { "Body too short" }
        return repo.sendComplaint(complaint)
    }
}