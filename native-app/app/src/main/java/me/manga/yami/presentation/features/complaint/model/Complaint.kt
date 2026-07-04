package me.manga.yamiapk.presentation.features.complaint.model

import java.util.Date


data class Complaint(
    val id: String = "",
    val userId: String,
    val type: ComplaintType,
    val subject: String,
    val body: String,
    val createdAt: Date? = null,
    val status: ComplaintStatus = ComplaintStatus.OPEN,
    val metadata: Map<String, Any>? = null
)