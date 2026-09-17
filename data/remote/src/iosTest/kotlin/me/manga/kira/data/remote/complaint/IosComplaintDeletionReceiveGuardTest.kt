package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintDeletionReceiveGuardTest {
    @Test
    fun acceptedAndNoContentNeverForwardAnyBytesAndFailureSurvivesANilCompletion() {
        for (status in listOf(202, 204)) {
            for (overflow in listOf(false, true)) {
                withIosDeletionGuard { fixture ->
                    val task = fixture.task(iosDeletionTestRequest())
                    assertTrue(fixture.admit(task, emptyMap(), IOS_DELETION_URL, status))
                    repeat(3) { fixture.receive(task, 0) }
                    if (overflow) fixture.receive(task, 1)
                    fixture.complete(task)
                    assertEquals(0uL, fixture.callbacks.forwardedBytes)
                    assertTrue(fixture.callbacks.forwarded.isEmpty())
                    val error = fixture.callbacks.errors.single()
                    if (overflow) assertNotNull(error) else assertNull(error)
                    assertEquals(0, fixture.guard.activeTaskCount)
                }
            }
        }
    }

    @Test
    fun problemLimitPlusOneIsRejectedBeforeForwardingAndCannotBeRevivedByLateCallbacks() {
        for (overflow in listOf(false, true)) {
            withIosDeletionGuard { fixture ->
                val task = fixture.task(iosDeletionTestRequest())
                val headers = mapOf("Content-Type" to "application/problem+json", "Content-Length" to "${Policy.MAX_PROBLEM_BYTES}")
                assertTrue(fixture.admit(task, headers, IOS_DELETION_URL, 410))
                fixture.receive(task, Policy.MAX_PROBLEM_BYTES)
                if (overflow) fixture.receive(task, 1)
                fixture.complete(task)
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(Policy.MAX_PROBLEM_BYTES.toULong(), fixture.callbacks.forwardedBytes)
                val error = fixture.callbacks.errors.single()
                if (overflow) assertNotNull(error) else assertNull(error)
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun invalidEncodingLengthOrMediaAndMismatchedResponseTargetNeverReachTheQueue() {
        val cases = listOf(
            mapOf("Content-Length" to "1"),
            mapOf("Content-Length" to "0,0"),
            mapOf("Content-Type" to "application/problem+json"),
            mapOf("Content-Encoding" to "gzip"),
            mapOf("Content-Length" to "0", "Transfer-Encoding" to "chunked"),
        )
        for (headers in cases) {
            withIosDeletionGuard { fixture ->
                val task = fixture.task(iosDeletionTestRequest())
                assertFalse(fixture.admit(task, headers, IOS_DELETION_URL, 204))
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
            }
        }
        withIosDeletionGuard { fixture ->
            val task = fixture.task(iosDeletionTestRequest())
            assertFalse(fixture.admit(task, emptyMap(), IOS_SESSION_TEST_URL, 204))
            assertNotNull(fixture.callbacks.errors.single())
        }
    }

    @Test
    fun closeBeforeCompletionKeepsItsStickyErrorAndLateEmptySuccessCannotEscape() =
        withIosDeletionGuard { fixture ->
            val task = fixture.task(iosDeletionTestRequest())
            assertTrue(fixture.admit(task, emptyMap(), IOS_DELETION_URL, 204))
            fixture.guard.close(fixture.session)
            fixture.receive(task, 0)
            fixture.complete(task)
            assertTrue(fixture.callbacks.forwarded.isEmpty())
            assertNotNull(fixture.callbacks.errors.single())
            assertEquals(0, fixture.guard.activeTaskCount)
        }
}
