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

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintSessionReceiveGuardTest {
    @Test
    fun burstStopsBeforeViolatingNSDataAndCannotBecomeTruncatedSuccess() =
        withIosSessionGuard { fixture ->
            val task = fixture.task()
            assertTrue(fixture.admit(task))
            repeat(ComplaintSessionReceiveBudget.MAX_BYTES / CALLBACK_BYTES) { fixture.receive(task, CALLBACK_BYTES) }
            assertEquals(ComplaintSessionReceiveBudget.MAX_BYTES.toULong(), fixture.callbacks.forwardedBytes)
            fixture.receive(task, 1)
            fixture.complete(task)
            assertFalse(fixture.admit(task))
            fixture.receive(task, 1)
            assertEquals(ComplaintSessionReceiveBudget.MAX_BYTES.toULong(), fixture.callbacks.forwardedBytes)
            assertNotNull(fixture.callbacks.errors.single())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun originalIdentityLengthSurvivesUntilCompletionAndNextTaskCanUseSlot() =
        withIosSessionGuard { fixture ->
            val task = fixture.task()
            val headers =
                mapOf(
                    "Content-Encoding" to "identity",
                    "Content-Length" to DECLARED_TEST_BYTES.toString(),
                )
            assertTrue(fixture.admit(task, headers))
            fixture.receive(task, DECLARED_TEST_BYTES)
            fixture.complete(task)
            assertNull(fixture.callbacks.errors.single())
            assertEquals(0, fixture.guard.activeTaskCount)
            val next = fixture.task()
            assertTrue(fixture.admit(next))
            fixture.receive(next, 1)
            fixture.complete(next)
            assertEquals((DECLARED_TEST_BYTES + 1).toULong(), fixture.callbacks.forwardedBytes)
            assertEquals(listOf<NSError?>(null, null), fixture.callbacks.errors)
        }

    @Test
    fun originalHeaderRejectionNeverForwardsADataPrefix() {
        val invalid =
            listOf(
                mapOf("Content-Encoding" to "gzip"),
                mapOf("Content-Length" to (ComplaintSessionReceiveBudget.MAX_BYTES + 1).toString()),
                mapOf("Content-Length" to "1, 1"),
                mapOf("Content-Length" to "1", "Transfer-Encoding" to "chunked"),
            )
        invalid.forEach { headers ->
            withIosSessionGuard { fixture ->
                val task = fixture.task()
                assertFalse(fixture.admit(task, headers))
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun falseLengthAndCompletionWithoutAdmittedHeadersFailClosed() =
        withIosSessionGuard { fixture ->
            val task = fixture.task()
            assertTrue(fixture.admit(task, mapOf("Content-Encoding" to "identity", "Content-Length" to "2")))
            fixture.receive(task, 1)
            fixture.complete(task)
            fixture.complete(fixture.task())
            assertEquals(2, fixture.callbacks.errors.size)
            assertTrue(fixture.callbacks.errors.all { it != null })
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun zeroLengthCallbacksCannotFillAByteBoundedQueue() =
        withIosSessionGuard { fixture ->
            val task = fixture.task()
            assertTrue(fixture.admit(task))
            repeat(ZERO_CALLBACKS) { fixture.receive(task, 0) }
            fixture.complete(task)
            assertTrue(fixture.callbacks.forwarded.isEmpty())
            assertNull(fixture.callbacks.errors.single())
        }

    @Test
    fun concurrentResponseDoesNotDisplaceTheSingleSessionSlot() =
        withIosSessionGuard { fixture ->
            val first = fixture.task()
            assertTrue(fixture.admit(first))
            assertFalse(fixture.admit(fixture.task()))
            assertEquals(1, fixture.guard.activeTaskCount)
            fixture.receive(first, 1)
            fixture.complete(first)
            assertEquals(2, fixture.callbacks.errors.size)
            assertNotNull(fixture.callbacks.errors.first())
            assertNull(fixture.callbacks.errors.last())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun ownerCloseClearsAccountingAndRefusesLateCallbacksAndNewRequests() =
        withIosSessionGuard { fixture ->
            val task = fixture.task()
            assertTrue(fixture.admit(task))
            fixture.receive(task, 1)
            fixture.guard.close(fixture.session)
            fixture.guard.close(fixture.session)
            fixture.receive(task, 1)
            fixture.complete(task)
            assertFalse(fixture.admit(task))
            assertFails { fixture.guard.prepare(iosSessionTestRequest()) }
            assertEquals(1uL, fixture.callbacks.forwardedBytes)
            assertNotNull(fixture.callbacks.errors.single())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun cancellationCompletionClearsAccountingWithoutReplacingNativeError() =
        withIosSessionGuard { fixture ->
            val task = fixture.task()
            val cancelled = NSError(NSURLErrorDomain, NSURLErrorCancelled, null)
            assertTrue(fixture.admit(task))
            fixture.complete(task, cancelled)
            assertSame(cancelled, fixture.callbacks.errors.single())
            assertEquals(0, fixture.guard.activeTaskCount)
            assertTrue(fixture.admit(fixture.task()))
        }

    private companion object {
        const val CALLBACK_BYTES = 1_024
        const val DECLARED_TEST_BYTES = 3
        const val ZERO_CALLBACKS = 64
    }
}
