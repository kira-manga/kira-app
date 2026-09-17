package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class InstallationDeletionHttpTest {
    @Test
    fun failedEofCannotMasqueradeAsAnEmptyTerminalOrACompleteProblem() =
        runTest {
            for (status in listOf(HttpStatusCode.NoContent, HttpStatusCode.Gone)) {
                val bytes = if (status == HttpStatusCode.NoContent) byteArrayOf() else {
                    mutationProblem(status, "INSTALLATION_DELETED").encodeToByteArray()
                }
                for (afterPrefix in listOf(false, true)) {
                    val channel = ComplaintFailedEofChannel(bytes, afterPrefix)
                    val headers = deletionHeaders(status) { append(HttpHeaders.ContentLength, bytes.size.toString()) }
                    assertIs<AppError.Network.NoConnectivity>(deletionResponseRetains(status, channel, headers))
                    assertTrue(channel.cancelled)
                }
            }
        }

    @Test
    fun timeoutAndTransportFailureRetainPendingAndHaveNoHiddenRetryOrSessionFallback() =
        runTest {
            for (timeout in listOf(false, true)) {
                val fixture = InstallationDeletionFixture(this, InstallationCoordinatorFixture(deletingRecord()), deletionHandler = {
                    if (timeout) awaitCancellation() else error("Synthetic transport failure")
                })
                try {
                    val error = assertDeletionPending(fixture.repository.continueDeletion()).error
                    if (timeout) assertIs<AppError.Network.Timeout>(error) else assertIs<AppError.Network.NoConnectivity>(error)
                    fixture.assertRetained(deletingRecord())
                    fixture.assertNoNewIdentity()
                    assertEquals(1, fixture.requests.size)
                    assertTrue(fixture.storage.faults.mutations.isEmpty())
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun callerCancellationPropagatesAndNeverRunsDestructiveFinally() =
        runTest {
            val cancellation = CancellationException("Synthetic deletion cancellation")
            val fixture = InstallationDeletionFixture(this, InstallationCoordinatorFixture(deletingRecord()), deletionHandler = {
                throw cancellation
            })
            try {
                val caught = assertFailsWith<CancellationException> { fixture.repository.continueDeletion() }
                assertCancellationIdentity(cancellation, caught)
                fixture.assertRetained(deletingRecord())
                fixture.assertNoNewIdentity()
                assertTrue(fixture.storage.faults.mutations.isEmpty())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun closedBorrowingClientCannotDispatchOrDeleteEvenWithExactDurableIntent() =
        runTest {
            val fixture = InstallationDeletionFixture(this, InstallationCoordinatorFixture(deletingRecord()))
            try {
                fixture.http.close()
                assertIs<AppError.Platform.FeatureUnavailable>(assertDeletionPending(fixture.repository.continueDeletion()).error)
                fixture.assertRetained(deletingRecord())
                fixture.assertNoNewIdentity()
                assertTrue(fixture.requests.isEmpty() && fixture.storage.faults.mutations.isEmpty())
            } finally {
                fixture.close()
            }
        }
}

/** Every malformed/nonterminal response must leave both the tuple and opaque pending inventory unchanged. */
internal suspend fun TestScope.deletionResponseRetains(
    status: HttpStatusCode,
    channel: ByteReadChannel,
    headers: Headers = deletionHeaders(status),
): AppError {
    val storage = InstallationCoordinatorFixture(deletingRecord())
    val slot = Fixtures.slot(1)
    storage.pending.slots += slot
    val fixture = InstallationDeletionFixture(this, storage, deletionHandler = { respond(channel, status, headers) })
    return try {
        val error = assertNotNull(assertDeletionPending(fixture.repository.continueDeletion()).error)
        fixture.assertRetained(deletingRecord(), listOf(slot))
        fixture.assertNoNewIdentity()
        assertEquals(1, fixture.requests.size)
        assertTrue(storage.faults.mutations.isEmpty())
        error
    } finally {
        fixture.close()
    }
}
