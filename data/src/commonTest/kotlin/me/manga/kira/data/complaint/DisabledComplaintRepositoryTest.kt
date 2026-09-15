package me.manga.kira.data.complaint

import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.firebase.FirebaseServicesUnavailableException
import me.manga.kira.presentation.features.complaint.model.Complaint
import me.manga.kira.presentation.features.complaint.model.ComplaintType
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository
import me.manga.kira.presentation.features.complaint.usecase.SendComplaintUseCase
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DisabledComplaintRepositoryTest {
    @Test
    fun everyUnavailableOperationFailsRatherThanReturningEmptyDataOrFakeSuccess() = runTest {
        val repository: ComplaintRepository = DisabledComplaintRepository
        val complaint = Complaint(userId = "isolated", type = ComplaintType.TECHNICAL, subject = "Debug", body = "No remote write")
        assertFailsWith<FirebaseServicesUnavailableException> { SendComplaintUseCase(repository)(complaint) }
        assertFailsWith<FirebaseServicesUnavailableException> { repository.getAllComplaints() }
        assertFailsWith<FirebaseServicesUnavailableException> { repository.getComplaintsByUser("isolated") }
        assertFailsWith<FirebaseServicesUnavailableException> { repository.updateComplaint(complaint) }
        assertFailsWith<FirebaseServicesUnavailableException> { repository.deleteComplaint("isolated") }
    }
}
