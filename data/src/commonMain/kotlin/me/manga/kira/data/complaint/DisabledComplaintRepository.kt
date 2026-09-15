package me.manga.kira.data.complaint

import me.manga.kira.platform.firebase.FirebaseServicesUnavailableException
import me.manga.kira.presentation.features.complaint.model.Complaint
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository

/** Resolves without Firebase/HTTP; legacy Result adapters preserve this typed failure for every operation. */
object DisabledComplaintRepository : ComplaintRepository {
    override suspend fun sendComplaint(complaint: Complaint): Nothing = throw FirebaseServicesUnavailableException()
    override suspend fun getAllComplaints(): Nothing = throw FirebaseServicesUnavailableException()
    override suspend fun getComplaintsByUser(userId: String): Nothing = throw FirebaseServicesUnavailableException()
    override suspend fun updateComplaint(complaint: Complaint): Nothing = throw FirebaseServicesUnavailableException()
    override suspend fun deleteComplaint(complaintId: String): Nothing = throw FirebaseServicesUnavailableException()
}
