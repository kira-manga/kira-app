package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintDetailReadLaneTest {
    @Test
    fun listToDetailDetailToListAndDetailToOtherIdWaitForTheSameOldBodyCleanup() =
        runTest {
            for ((old, next) in listOf("list" to "detail", "detail" to "list", "detail" to "detail")) {
                assertReplacement(old, next)
            }
        }

    @Test
    fun invalidDetailIdCannotReplaceOrCancelTheCurrentBody() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val channel = HistoryTrackedChannel(ByteChannel(autoFlush = true))
            val fixture =
                ComplaintHistoryFixture(this, historyHandler = {
                    entered.complete(Unit)
                    respond(channel, HttpStatusCode.OK, detailHeaders())
                })
            try {
                val old = async { fixture.repository.loadComplaintDetail(historyId(100)) }
                entered.await()
                runCurrent()
                val result = fixture.repository.loadComplaintDetail("${historyId(100)}?limit=1")
                assertIs<AppError.Validation.Format>(assertIs<AppResult.Failure>(result).error)
                assertFalse(old.isCancelled)
                assertFalse(channel.cancelled)
                old.cancelAndJoin()
                assertTrue(channel.cancelled)
                assertEquals(1, fixture.historyRequests.size)
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun late401FromReplacedLeaseCannotEvictNewerSessionOrRefreshAgain() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                ComplaintHistoryFixture(this, historyHandler = {
                    entered.complete(Unit)
                    release.await()
                    respond(
                        historyProblem(HttpStatusCode.Unauthorized),
                        HttpStatusCode.Unauthorized,
                        sessionHeaders(HttpStatusCode.Unauthorized),
                    )
                })
            try {
                val old = assertIs<ComplaintHistorySessionResult.Ready>(fixture.sessions.historySession()).session
                val read = async { fixture.repository.loadComplaintDetail(historyId(100)) }
                entered.await()
                fixture.sessions.invalidateHistorySession(old)
                val newer = assertIs<ComplaintHistorySessionResult.Ready>(fixture.sessions.historySession()).session
                release.complete(Unit)
                assertIs<AppResult.Failure>(read.await())
                assertTrue(fixture.sessions.historySessionIsCurrent(newer))
                assertEquals(2, fixture.sessionRequests.size)
                assertEquals(1, fixture.historyRequests.size)
                fixture.assertPreserved()
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }

    private suspend fun TestScope.assertReplacement(
        oldKind: String,
        nextKind: String,
    ) {
        val entered = CompletableDeferred<Unit>()
        val channel = HistoryTrackedChannel(ByteChannel(autoFlush = true))
        var calls = 0
        val fixture =
            ComplaintHistoryFixture(this, historyHandler = { request ->
                if (++calls == 1) {
                    entered.complete(Unit)
                    val headers = if (oldKind == "list") sessionHeaders() else detailHeaders()
                    respond(channel, HttpStatusCode.OK, headers)
                } else {
                    assertTrue(channel.cancelled, "same reader replacement must wait for body finally")
                    if (!request.url.parameters.isEmpty()) {
                        respond(historyResponse(), HttpStatusCode.OK, sessionHeaders())
                    } else {
                        respond(historyItem(101).toString(), HttpStatusCode.OK, detailHeaders(historyId(101)))
                    }
                }
            })
        try {
            val old = async { fixture.readKind(oldKind, 100) }
            entered.await()
            runCurrent()
            val next = async { fixture.readKind(nextKind, 101) }
            assertIs<AppResult.Success<*>>(next.await())
            assertFailsWith<CancellationException> { old.await() }
            assertTrue(channel.cancelled)
            assertEquals(2, fixture.historyRequests.size)
            assertEquals(1, fixture.sessionRequests.size)
            fixture.assertPreserved()
        } finally {
            fixture.close()
        }
    }
}

private suspend fun ComplaintHistoryFixture.readKind(
    kind: String,
    id: Int,
): AppResult<Any> =
    if (kind == "list") repository.loadUserComplaints() else repository.loadComplaintDetail(historyId(id))
