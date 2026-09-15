package me.manga.kira.data.repository

import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.auth.UserIdProvider
import me.manga.kira.domain.device.DeviceInfoProvider
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.FeedbackRepository
import me.manga.kira.platform.version.AppVersionProvider
import me.manga.kira.presentation.features.complaint.model.Complaint
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository
import me.manga.kira.presentation.features.complaint.usecase.SendComplaintUseCase
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedbackRepositoryImplTest {
    @Test
    fun everyComplaintTypeIncludesTheRunningVersionWithoutChangingDeviceMetadata() = runTest {
        val store = RecordingComplaintRepository()
        val metadata = mutableMapOf<String, Any>(
            "manufacturer" to "Test manufacturer",
            "model" to "Test model",
            "osVersion" to 35,
            "osRelease" to "15",
            "appVersion" to "stale-device-value",
        )
        val originalMetadata = metadata.toMap()
        val repository = repository(store, version = "2.3.4-beta+17", metadata = metadata)

        ComplaintType.entries.forEach { type ->
            repository.submit(type, subject = "Test subject", body = "Test complaint body").getOrThrow()
        }

        assertEquals(ComplaintType.entries.map { it.name }, store.sent.map { it.type.name })
        store.sent.forEach { complaint ->
            assertEquals(originalMetadata + ("appVersion" to "2.3.4-beta+17"), complaint.metadata)
            assertEquals("test-user", complaint.userId)
        }
        assertEquals(originalMetadata, metadata, "The device provider's map must not be mutated")
    }

    @Test
    fun languageRequestWrapperIncludesTheVersion() = runTest {
        val store = RecordingComplaintRepository()

        repository(store, version = "4.5.6").sendLanguageRequest("Please add a language").getOrThrow()

        val complaint = store.sent.single()
        assertEquals(ComplaintType.LANGUAGES.name, complaint.type.name)
        assertEquals(FeedbackRepository.LANGUAGE_REQUEST_SUBJECT, complaint.subject)
        assertEquals("4.5.6", complaint.metadata?.get("appVersion"))
    }

    @Test
    fun missingVersionUsesTheExplicitUnknownFallback() = runTest {
        listOf("", " \t\n", "unknown").forEach { version ->
            val store = RecordingComplaintRepository()

            repository(store, version).submit(
                type = ComplaintType.TECHNICAL,
                subject = "Test subject",
                body = "Test complaint body",
            ).getOrThrow()

            assertEquals(mapOf("appVersion" to "unknown"), store.sent.single().metadata)
        }
    }

    private fun repository(
        store: ComplaintRepository,
        version: String,
        metadata: Map<String, Any> = emptyMap(),
    ) = FeedbackRepositoryImpl(
        sendComplaint = SendComplaintUseCase(store),
        userIdProvider = object : UserIdProvider {
            override fun getUserId(): String = "test-user"
        },
        deviceInfoProvider = object : DeviceInfoProvider {
            override fun getDeviceMetadata(): Map<String, Any> = metadata
        },
        appVersionProvider = object : AppVersionProvider {
            override val versionName: String = version
            override val packageName: String = "not-collected"
        },
    )

    private class RecordingComplaintRepository : ComplaintRepository {
        val sent = mutableListOf<Complaint>()

        override suspend fun sendComplaint(complaint: Complaint): String {
            sent += complaint
            return "test-document"
        }

        override suspend fun getAllComplaints(): List<Complaint> = error("Unexpected read")

        override suspend fun getComplaintsByUser(userId: String): List<Complaint> = error("Unexpected read")

        override suspend fun updateComplaint(complaint: Complaint): Unit = error("Unexpected update")

        override suspend fun deleteComplaint(complaintId: String): Unit = error("Unexpected delete")
    }
}
