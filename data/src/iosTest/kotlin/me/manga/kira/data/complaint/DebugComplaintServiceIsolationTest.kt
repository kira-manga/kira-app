package me.manga.kira.data.complaint

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.di.complaintRepositoryModule
import me.manga.kira.platform.firebase.FirebaseServicesUnavailableException
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DebugComplaintServiceIsolationTest {
    @Test
    fun debugHostResolvesWithoutFirebasePlistOrHttpClient() = runTest {
        val app = koinApplication { modules(complaintRepositoryModule()) }
        try {
            val repository = app.koin.get<ComplaintRepository>()
            assertSame(DisabledComplaintRepository, repository)
            assertFailsWith<FirebaseServicesUnavailableException> { repository.getAllComplaints() }
        } finally {
            app.close()
        }
    }
}
