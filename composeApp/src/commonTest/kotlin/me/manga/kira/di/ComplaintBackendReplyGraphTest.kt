package me.manga.kira.di

import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.repository.ReadOnlyComplaintActionRepository
import me.manga.kira.domain.model.feedback.ComplaintReplyDraft
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.repository.ComplaintActionRepository
import me.manga.kira.domain.repository.ComplaintInstallationRecoveryRepository
import me.manga.kira.domain.repository.ComplaintReplyRepository
import me.manga.kira.domain.repository.ComplaintReportRepository
import me.manga.kira.domain.usecase.feedback.ComplaintReplyActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportRecoveryActions
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Actual isolated owner/use-case/HTTP chain; this is not native execution or shipping activation. */
class ComplaintBackendReplyGraphTest {
    @Test
    fun isolatedKoinReplyUsesSameReportRecoveryOwnerAndDurableHttpLane() =
        runTest {
            val resources = ComplaintBackendGraphFixture(this)
            val reply = MobileReplyGraphFixture(this, resources)
            val graph = createComplaintBackendGraph({ GRAPH_BASE }, reply.resources()).replyGraphSuccess()
            val app = koinApplication { modules(graph.module()) }
            try {
                assertSharedReplyGraph(app.koin, graph)
                assertIs<ReadOnlyComplaintActionRepository>(app.koin.get<ComplaintActionRepository>())
                exerciseTypedReplyGraph(app.koin, reply)
                assertEquals(1, resources.sessionCalls)
                assertEquals(1, resources.reportIdentifierGenerations)
                assertEquals(1, resources.reportMetadataReads)
                assertEquals(0, resources.historyCalls + resources.deletionCalls + resources.generations)
                assertEquals(0, resources.credentials.writes + resources.pending.writes)
                assertTrue(resources.events.none { it == "request:enrollment" })
            } finally {
                app.close()
                graph.close()
            }
            assertTrue(resources.owners.values.all { it.closed })
        }

    @Test
    fun typedReplyRegistrationCannotOpenTheShippingSelectionGate() {
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

private fun assertSharedReplyGraph(
    koin: Koin,
    graph: ComplaintBackendGraph,
) {
    val reports = koin.get<ComplaintReportRepository>()
    val replies = koin.get<ComplaintReplyRepository>()
    assertSame(graph.reports, reports)
    assertSame(graph.replies, replies)
    assertSame<Any?>(reports, replies)
    assertSame<Any?>(reports, koin.get<ComplaintInstallationRecoveryRepository>())
}

private suspend fun exerciseTypedReplyGraph(
    koin: Koin,
    fixture: MobileReplyGraphFixture,
) {
    val actions = koin.get<ComplaintReplyActions>()
    val draft = ComplaintReplyDraft(GRAPH_REPLY_PARENT, GRAPH_REPLY_BODY)
    val ready = assertIs<ComplaintReplyPreparation.Ready>(actions.prepare(draft).replyGraphSuccess())
    assertTrue(fixture.requests.isEmpty())
    assertTrue(fixture.pending.transitions.isEmpty())
    val submitted = actions.submit(ready.reply).replyGraphSuccess()
    val complete = assertIs<ComplaintReportAttempt.Completed>(submitted.attempt)
    val applied = assertIs<ComplaintReportApplication.Applied>(complete.application)
    assertEquals(GRAPH_REPLY_ID, applied.id)
    assertEquals(1L, applied.version)
    assertTrue(submitted.recovery.entries().isEmpty())
    assertIs<AppResult.Failure>(actions.retry(ready.reply))
    val recovery = koin.get<ComplaintReportRecoveryActions>().reconcile().replyGraphSuccess()
    assertTrue(recovery.entries().isEmpty())
    fixture.assertExactReply()
}

private fun <T> AppResult<T>.replyGraphSuccess(): T = assertIs<AppResult.Success<T>>(this).value
