package me.manga.yamiapk.presentation.features.complaint.repository

import me.manga.yamiapk.presentation.features.complaint.model.Complaint


interface ComplaintRepository {
    /**
     * Creates a new complaint and returns its generated ID.
     */
    suspend fun sendComplaint(complaint: Complaint): String
    suspend fun getAllComplaints(): List<Complaint>
    suspend fun getComplaintsByUser(userId: String): List<Complaint>
    suspend fun updateComplaint(complaint: Complaint)
    suspend fun deleteComplaint(complaintId: String)

}