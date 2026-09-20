package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Real tasks and driven callbacks, not resumed network tasks or a Foundation replay/drain measurement. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
@Suppress("MagicNumber") // Zero-budget204, existing problem cap and exact callback counts.
class IosComplaintMutationOwnerDeleteReceiveGuardTest {
    @Test
    fun emptyCallbacksAreSuppressedAndFirstNonemptyDataFailsStickyExactlyOnce() =
        withIosMutationGuard { fixture ->
            val task = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.OWNER_DELETE))
            assertTrue(fixture.admit(task, url = DELETE_URL, status = 204))
            repeat(3) { fixture.receive(task, 0) }
            assertTrue(fixture.callbacks.forwarded.isEmpty())
            assertTrue(fixture.callbacks.errors.isEmpty())
            assertEquals(1, fixture.guard.activeTaskCount)
            fixture.receive(task, 1)
            fixture.receive(task, 0)
            fixture.receive(task, 1)
            fixture.complete(task)
            fixture.complete(task)
            assertFalse(fixture.admit(task, url = DELETE_URL, status = 204))
            assertTrue(fixture.callbacks.forwarded.isEmpty())
            assertNotNull(fixture.callbacks.errors.single())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun direct204LengthIncludingZeroMediaTransferAndBadCodingRejectBeforeCallbacks() {
        listOf(
            mapOf("Content-Length" to "0"),
            mapOf("Content-Length" to "0, 0"),
            mapOf("Content-Type" to "application/json"),
            mapOf("Transfer-Encoding" to "chunked"),
            mapOf("Content-Encoding" to "gzip"),
            mapOf("Content-Encoding" to "identity, identity"),
        ).forEach { headers ->
            withIosMutationGuard { fixture ->
                val task = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.OWNER_DELETE))
                assertFalse(fixture.admit(task, headers, DELETE_URL, 204))
                fixture.receive(task, 0)
                fixture.receive(task, 1)
                fixture.complete(task)
                fixture.complete(task)
                assertTrue(fixture.callbacks.forwarded.isEmpty())
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun deliveredCancellationCannotBecomeEmptySuccessAndTheNextExplicitStatusOwnsTheFreedSlot() =
        withIosMutationGuard { fixture ->
            val task = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.OWNER_DELETE))
            assertTrue(fixture.admit(task, url = DELETE_URL, status = 204))
            val cancelled = NSError(NSURLErrorDomain, NSURLErrorCancelled, null)
            fixture.complete(task, cancelled)
            fixture.receive(task, 0)
            fixture.receive(task, 1)
            fixture.complete(task)
            assertSame(cancelled, fixture.callbacks.errors.single())
            assertTrue(fixture.callbacks.forwarded.isEmpty())
            assertEquals(0, fixture.guard.activeTaskCount)
            val status = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.STATUS))
            val headers = mapOf("Content-Type" to "application/json", "Content-Length" to "1")
            assertTrue(fixture.admit(status, headers, IOS_MUTATION_STATUS_URL))
            fixture.receive(status, 1)
            fixture.complete(status)
            assertEquals(1uL, fixture.callbacks.forwardedBytes)
            assertEquals(2, fixture.callbacks.errors.size)
            assertNull(fixture.callbacks.errors.last())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun occupiedDeleteSlotRefusesStatusAndOwnerCloseFencesEveryLateCallback() =
        withIosMutationGuard { fixture ->
            val deletion = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.OWNER_DELETE))
            assertTrue(fixture.admit(deletion, url = DELETE_URL, status = 204))
            val status = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.STATUS))
            assertFalse(fixture.admit(status, url = IOS_MUTATION_STATUS_URL))
            assertEquals(1, fixture.guard.activeTaskCount)
            fixture.guard.close(fixture.session)
            fixture.guard.close(fixture.session)
            listOf(deletion, status).forEach { task ->
                fixture.receive(task, 0)
                fixture.receive(task, 1)
                fixture.complete(task)
            }
            assertFalse(fixture.admit(deletion, url = DELETE_URL, status = 204))
            assertFails { fixture.guard.prepare(iosMutationTestRequest(ComplaintMutationRoute.OWNER_DELETE)) }
            assertEquals(2, fixture.callbacks.errors.size)
            fixture.callbacks.errors.forEach { assertNotNull(it) }
            assertTrue(fixture.callbacks.forwarded.isEmpty())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun deleteErrorKeepsTheExistingProblemCapAndRejectsTheFirstExcessByte() =
        withIosMutationGuard { fixture ->
            val task = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.OWNER_DELETE))
            val headers = mapOf("Content-Type" to "application/problem+json")
            assertTrue(fixture.admit(task, headers, DELETE_URL, 412))
            fixture.receive(task, Policy.MAX_STATUS_OR_PROBLEM_BYTES)
            fixture.receive(task, 1)
            fixture.complete(task)
            fixture.receive(task, 1)
            assertEquals(Policy.MAX_STATUS_OR_PROBLEM_BYTES.toULong(), fixture.callbacks.forwardedBytes)
            assertNotNull(fixture.callbacks.errors.single())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    private companion object {
        const val DELETE_URL = "$IOS_MUTATION_CREATE_URL/$MUTATION_TEST_PARENT"
    }
}
