package me.manga.yamiapk.presentation.features.complaint.utils

import me.manga.yamiapk.presentation.features.complaint.model.ComplaintStatus

fun String.toComplaintStatus(): ComplaintStatus =
    try {
        ComplaintStatus.valueOf(this)
    } catch (e: IllegalArgumentException) {
        ComplaintStatus.UNKNOWN
    }