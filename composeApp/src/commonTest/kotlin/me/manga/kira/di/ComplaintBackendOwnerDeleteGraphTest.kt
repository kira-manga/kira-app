package me.manga.kira.di

import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.repository.ReadOnlyComplaintActionRepository
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteDraft
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeletePreparation
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.repository.ComplaintActionRepository
import me.manga.kira.domain.repository.ComplaintEditRepository
import me.manga.kira.domain.repository.ComplaintInstallationRecoveryRepository
import me.manga.kira.domain.repository.ComplaintOwnerDeleteRepository
import me.manga.kira.domain.repository.ComplaintReplyRepository
import me.manga.kira.domain.repository.ComplaintReportRepository
import me.manga.kira.domain.usecase.feedback.ComplaintEditActions
import me.manga.kira.domain.usecase.feedback.ComplaintOwnerDeleteActions
import me.manga.kira.domain.usecase.feedback.ComplaintReplyActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportActions
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComplaintBackendOwnerDeleteGraphTest {
    @Test
    fun typedDeleteUsesOneReportReplyEditRecoveryConsumerAndOnlyTheExistingKeySupplier() =
        runTest {
            val fixture = ComplaintBackendGraphFixture(this)
            val deletion = MobileOwnerDeleteGraphFixture(this, fixture)
            val graph = createComplaintBackendGraph({ GRAPH_BASE }, deletion.resources()).ownerDeleteGraphSuccess()
            val app = koinApplication { modules(graph.module()) }
            val credentials = fixture.credentials.record
            try {
                assertSharedOwnerDeleteGraph(app.koin, graph)
                assertInertOwnerDeleteRegistration(fixture, deletion)
                assertGraphOwnerDelete(app.koin, deletion)
                assertOwnerDeleteOnlyGraphWork(fixture, deletion)
                assertSame(credentials, fixture.credentials.record)
            } finally {
                app.close()
                graph.close()
            }
            assertTrue(fixture.owners.values.all { it.closed })
        }

    @Test
    fun ownerDeleteRegistrationCannotOpenShippingSelectionOrAllocateAnyCandidateResource() {
        var allocations = 0
        val result =
            selectComplaintBackendCandidate {
                createComplaintBackendGraph(
                    {
                        allocations++
                        GRAPH_BASE
                    },
                    trapGraphResources { allocations++ },
                )
            }
        assertIs<AppResult.Failure>(result)
        assertEquals(0, allocations)
    }
}

private fun assertSharedOwnerDeleteGraph(koin: Koin, graph: ComplaintBackendGraph) {
    val reports = koin.get<ComplaintReportRepository>()
    val deletions = koin.get<ComplaintOwnerDeleteRepository>()
    assertSame(graph.ownerDeletes, deletions)
    assertSame<Any?>(reports, deletions)
    assertSame<Any?>(reports, koin.get<ComplaintReplyRepository>())
    assertSame<Any?>(reports, koin.get<ComplaintEditRepository>())
    assertSame<Any?>(reports, koin.get<ComplaintInstallationRecoveryRepository>())
    assertNotSame<Any?>(reports, graph.installationDeletion)
    assertIs<ReadOnlyComplaintActionRepository>(koin.get<ComplaintActionRepository>())
    koin.get<ComplaintReportActions>()
    koin.get<ComplaintReplyActions>()
    koin.get<ComplaintEditActions>()
    koin.get<ComplaintOwnerDeleteActions>()
}

private fun assertInertOwnerDeleteRegistration(
    fixture: ComplaintBackendGraphFixture,
    deletion: MobileOwnerDeleteGraphFixture,
) {
    assertTrue(fixture.events.none { it.startsWith("request:") })
    assertTrue(deletion.requests.isEmpty() && deletion.pending.transitions.isEmpty())
    assertEquals(0, deletion.keyGenerations)
    assertEquals(0, fixture.generations + fixture.reportIdentifierGenerations + fixture.reportMetadataReads)
    assertEquals(0, fixture.deletionKeyGenerations + fixture.credentials.writes + fixture.pending.writes)
}

private suspend fun assertGraphOwnerDelete(koin: Koin, deletion: MobileOwnerDeleteGraphFixture) {
    val actions = koin.get<ComplaintOwnerDeleteActions>()
    val draft = ComplaintOwnerDeleteDraft(graphEditTarget())
    val ready = assertIs<ComplaintOwnerDeletePreparation.Ready>(actions.prepare(draft).ownerDeleteGraphSuccess())
    assertTrue(deletion.requests.isEmpty() && deletion.pending.transitions.isEmpty())
    val attempt = actions.submit(ready.deletion).ownerDeleteGraphSuccess().attempt
    val application = assertIs<ComplaintReportAttempt.Completed>(attempt).application
    assertSame(
        ComplaintOwnerDeleteApplication.Applied,
        assertIs<ComplaintReportApplication.OwnerDelete>(application).application,
    )
    assertIs<AppResult.Failure>(actions.retry(ready.deletion))
    deletion.assertExactOwnerDelete()
    assertTrue(deletion.pending.slots.isEmpty())
    assertEquals(1, deletion.pending.deletes)
}

private fun assertOwnerDeleteOnlyGraphWork(
    fixture: ComplaintBackendGraphFixture,
    deletion: MobileOwnerDeleteGraphFixture,
) {
    assertEquals(1, deletion.keyGenerations)
    assertEquals(1, fixture.mutationCalls)
    assertEquals(1, fixture.sessionCalls)
    assertEquals(0, fixture.reportIdentifierGenerations + fixture.reportMetadataReads)
    assertEquals(0, fixture.deletionCalls + fixture.generations + fixture.deletionKeyGenerations)
    assertEquals(0, fixture.credentials.writes + fixture.pending.writes)
    assertTrue(fixture.events.none { it == "request:enrollment" })
}

private fun <T> AppResult<T>.ownerDeleteGraphSuccess(): T = assertIs<AppResult.Success<T>>(this).value
