package me.manga.yamiapk.presentation.features.complaint.data

import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintStatus
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintType
import java.util.Date

object SampleData {
    val complaints: List<Complaint> = listOf(
        Complaint(
            id = "c1",
            userId = "user_123",
            type = ComplaintType.TECHNICAL,
            subject = "App crashes on launch",
            body = "Every time I open the app on Android 11 it immediately crashes.",
            createdAt = Date(1690992000000L),
            status = ComplaintStatus.PLANNED,
            metadata = mapOf(
                "device" to "Pixel 4a",
                "osVersion" to "11",
                "closureReason" to "done - Feature already exists in settings menu. User was notified of the location."
            )
        ),
        Complaint(
            id = "c2",
            userId = "user_456",
            type = ComplaintType.LANGUAGES,
            subject = "Please add Portuguese",
            body = "I'd love to read in português!",
            createdAt = Date(1693570400000L),
            status = ComplaintStatus.IN_PROGRESS,
            metadata = null
        ),
        Complaint(
            id = "c3",
            userId = "user_789",
            type = ComplaintType.SITE_ERROR,
            subject = "Manga preview not loading",
            body = "On the MangaWorld source, previews show a blank page.",
            createdAt = Date(1696248800000L),
            status = ComplaintStatus.RESOLVED,
            metadata = mapOf("source" to "MangaWorld")
        ),
        Complaint(
            id = "c4",
            userId = "user_101",
            type = ComplaintType.FEATURES,
            subject = "Night mode toggle",
            body = "Would be great to have a simple toggle for dark mode in the toolbar.",
            createdAt = Date(),
            status = ComplaintStatus.CLOSED,
            metadata = mapOf(
                "appVersion" to "1.4.2",
                "closureReason" to "done - Feature already exists in settings menu. User was notified of the location."
            )
        ),
        Complaint(
            id = "c5",
            userId = "user_202",
            type = ComplaintType.TECHNICAL,
            subject = "Pages won't load",
            body = "Some manga pages are completely blank and won't load no matter what I try.",
            createdAt = Date(1695225600000L),
            status = ComplaintStatus.CLOSED,
            metadata = mapOf(
                "device" to "Samsung Galaxy S21",
                "osVersion" to "13",
                "closureReason" to "done and wait the update - Issue will be fixed in the next app release."
            )
        ),
        Complaint(
            id = "c6",
            userId = "user_303",
            type = ComplaintType.TECHNICAL,
            subject = "Slow loading times",
            body = "App takes forever to load manga chapters.",
            createdAt = Date(1694000000000L),
            status = ComplaintStatus.CLOSED,
            metadata = mapOf(
                "device" to "OnePlus 9",
                "osVersion" to "12",
                "closureReason" to "Issue was caused by server maintenance. Normal service restored."
            )
        )
    )
}