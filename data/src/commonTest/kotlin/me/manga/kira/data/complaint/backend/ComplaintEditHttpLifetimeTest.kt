package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintEditHttpLifetimeTest {
    @Test
    fun callerCancellationAndTimeoutNeverReplayEitherEditExchange() =
        runTest {
            for (direct in listOf(false, true)) {
                val cancelled = CancellationException("Synthetic edit cancellation")
                val f = ComplaintEditHttpFixture(this) { throw cancelled }
                try {
                    val actual =
                        assertFailsWith<CancellationException> {
                            if (direct) f.http.edit(f.request, f.session) else f.http.editStatus(f.status, f.session)
                        }
                    assertCancellationIdentity(cancelled, actual)
                    assertEquals(1, f.requests.size)
                } finally {
                    f.close()
                }
                val timeout = ComplaintEditHttpFixture(this) { awaitCancellation() }
                try {
                    assertEquals(ComplaintMutationFailure.TIMEOUT, timeout.failure(direct))
                    assertEquals(1, timeout.requests.size)
                } finally {
                    timeout.close()
                }
            }
        }

    @Test
    fun cancellationDuringBodyWaitClosesTheChannelWithoutASecondExchange() =
        runTest {
            for (direct in listOf(false, true)) {
                val entered = CompletableDeferred<Unit>()
                val channel = HistoryTrackedChannel(ByteChannel(autoFlush = true))
                val f = ComplaintEditHttpFixture(this) {
                    entered.complete(Unit)
                    respond(channel, HttpStatusCode.OK, mobileEditHeaders(direct = direct))
                }
                try {
                    val pending = async { f.failure(direct) }
                    entered.await()
                    runCurrent()
                    pending.cancel()
                    assertFailsWith<CancellationException> { pending.await() }
                    assertTrue(channel.cancelled)
                    assertEquals(1, f.requests.size)
                } finally {
                    f.close()
                }
            }
        }
}

private suspend fun ComplaintEditHttpFixture.failure(direct: Boolean): ComplaintMutationFailure =
    if (direct) {
        assertIs<ComplaintEditHttpResult.Failed>(http.edit(request, session)).reason
    } else {
        assertIs<ComplaintEditStatusHttpResult.Failed>(http.editStatus(status, session)).reason
    }
