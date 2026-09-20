package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintEditHttpLifetimeTest {
    @Test
    fun callerCancellationAndTimeoutNeverReplayEditOrDeleteExchanges() =
        runTest {
            for (exchange in OwnerLifetimeExchange.entries) {
                val cancelled = CancellationException("Synthetic edit cancellation")
                val f = ComplaintEditHttpFixture(this) { throw cancelled }
                try {
                    val actual = assertFailsWith<CancellationException> { f.failure(exchange) }
                    assertCancellationIdentity(cancelled, actual)
                    assertEquals(1, f.requests.size)
                } finally {
                    f.close()
                }
                val timeout = ComplaintEditHttpFixture(this) { awaitCancellation() }
                try {
                    assertEquals(ComplaintMutationFailure.TIMEOUT, timeout.failure(exchange))
                    assertEquals(1, timeout.requests.size)
                } finally {
                    timeout.close()
                }
            }
        }

    @Test
    fun cancellationDuringEditOrDeleteBodyWaitClosesTheChannelWithoutASecondExchange() =
        runTest {
            for (exchange in OwnerLifetimeExchange.entries) {
                val entered = CompletableDeferred<Unit>()
                val channel = HistoryTrackedChannel(ByteChannel(autoFlush = true))
                val f = ComplaintEditHttpFixture(this) {
                    entered.complete(Unit)
                    val deletion = exchange == OwnerLifetimeExchange.DELETE
                    val code = if (deletion) HttpStatusCode.NoContent else HttpStatusCode.OK
                    respond(channel, code, exchange.headers())
                }
                try {
                    val pending = async { f.failure(exchange) }
                    entered.await()
                    runCurrent()
                    assertFalse(pending.isCompleted)
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

private suspend fun ComplaintEditHttpFixture.failure(exchange: OwnerLifetimeExchange): ComplaintMutationFailure =
    when (exchange) {
        OwnerLifetimeExchange.EDIT -> assertIs<ComplaintEditHttpResult.Failed>(http.edit(request, session)).reason
        OwnerLifetimeExchange.EDIT_STATUS ->
            assertIs<ComplaintEditStatusHttpResult.Failed>(http.editStatus(status, session)).reason
        OwnerLifetimeExchange.DELETE ->
            assertIs<ComplaintOwnerDeleteHttpResult.Failed>(http.ownerDelete(mobileOwnerDeleteHttpRequest(), session)).reason
        OwnerLifetimeExchange.DELETE_STATUS -> {
            val result = http.ownerDeleteStatus(mobileOwnerDeleteStatusRequest(), session)
            assertIs<ComplaintOwnerDeleteStatusHttpResult.Failed>(result).reason
        }
    }

private fun OwnerLifetimeExchange.headers(): Headers =
    if (this == OwnerLifetimeExchange.DELETE) {
        mobileOwnerDeleteHeaders()
    } else {
        mobileEditHeaders(direct = this == OwnerLifetimeExchange.EDIT)
    }

private enum class OwnerLifetimeExchange {
    EDIT,
    EDIT_STATUS,
    DELETE,
    DELETE_STATUS,
}
