package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintHistoryReceiveGuardTest {
    @Test
    fun historyBurstStopsBeforeTheViolatingNSDataAndCannotCompleteAsTruncatedSuccess() =
        withIosHistoryGuard { fixture ->
            val task = fixture.task(iosHistoryTestRequest())
            assertTrue(fixture.admitHistory(task))
            repeat(ComplaintHistoryReceiveBudget.MAX_BYTES / CALLBACK_BYTES) { fixture.receive(task, CALLBACK_BYTES) }
            assertEquals(ComplaintHistoryReceiveBudget.MAX_BYTES.toULong(), fixture.callbacks.forwardedBytes)
            fixture.receive(task, 1)
            fixture.complete(task)
            assertFalse(fixture.admitHistory(task))
            fixture.receive(task, 1)
            assertEquals(ComplaintHistoryReceiveBudget.MAX_BYTES.toULong(), fixture.callbacks.forwardedBytes)
            assertNotNull(fixture.callbacks.errors.single())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun aDeclaredExactTwoMibibyte200CompletesButEveryOtherStatusHasTheSmallerBudget() {
        withIosHistoryGuard { fixture ->
            val task = fixture.task(iosHistoryTestRequest())
            assertTrue(
                fixture.admitHistory(
                    task,
                    mapOf("Content-Length" to ComplaintHistoryReceiveBudget.MAX_BYTES.toString()),
                ),
            )
            fixture.receive(task, ComplaintHistoryReceiveBudget.MAX_BYTES)
            fixture.complete(task)
            assertNull(fixture.callbacks.errors.single())
            assertEquals(ComplaintHistoryReceiveBudget.MAX_BYTES.toULong(), fixture.callbacks.forwardedBytes)
            assertEquals(0, fixture.guard.activeTaskCount)
        }
        listOf(UNAUTHORIZED, SERVER_ERROR, PARTIAL_CONTENT).forEach { status ->
            withIosHistoryGuard { fixture ->
                val task = fixture.task(iosHistoryTestRequest())
                assertTrue(fixture.admitHistory(task, status = status))
                fixture.receive(task, ComplaintSessionReceiveBudget.MAX_BYTES)
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(ComplaintSessionReceiveBudget.MAX_BYTES.toULong(), fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun nativeHeaderRefusalDoesNotForwardAPrefixAndIncompleteLengthFailsCompletion() {
        listOf(
            SUCCESS_STATUS to mapOf("Content-Length" to (ComplaintHistoryReceiveBudget.MAX_BYTES + 1).toString()),
            UNAUTHORIZED to mapOf("Content-Length" to (ComplaintSessionReceiveBudget.MAX_BYTES + 1).toString()),
            SUCCESS_STATUS to mapOf("Content-Encoding" to "gzip"),
            SUCCESS_STATUS to mapOf("Content-Length" to "1, 1"),
            SUCCESS_STATUS to mapOf("Content-Length" to "1", "Transfer-Encoding" to "chunked"),
        ).forEach { (status, headers) ->
            withIosHistoryGuard { fixture ->
                val task = fixture.task(iosHistoryTestRequest())
                assertFalse(fixture.admitHistory(task, headers, status = status))
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
        withIosHistoryGuard { fixture ->
            val task = fixture.task(iosHistoryTestRequest())
            assertTrue(fixture.admitHistory(task, mapOf("Content-Length" to INCOMPLETE_LENGTH.toString())))
            fixture.receive(task, INCOMPLETE_LENGTH - 1)
            fixture.complete(task)
            assertNotNull(fixture.callbacks.errors.single())
            assertEquals(0, fixture.guard.activeTaskCount)
        }
    }

    @Test
    fun neitherAnOversizedSingleChunkNorZeroLengthCallbackFloodCanFillTheForwardQueue() =
        withIosHistoryGuard { fixture ->
            val task = fixture.task(iosHistoryTestRequest())
            assertTrue(fixture.admitHistory(task))
            repeat(ZERO_CALLBACKS) { fixture.receive(task, 0) }
            assertTrue(fixture.callbacks.forwarded.isEmpty())
            fixture.receive(task, ComplaintHistoryReceiveBudget.MAX_BYTES + 1)
            fixture.complete(task)
            assertTrue(fixture.callbacks.forwarded.isEmpty())
            assertNotNull(fixture.callbacks.errors.single())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    private companion object {
        const val CALLBACK_BYTES = 8_192
        const val INCOMPLETE_LENGTH = 20_000
        const val ZERO_CALLBACKS = 64
        const val SUCCESS_STATUS = 200
        const val PARTIAL_CONTENT = 206
        const val UNAUTHORIZED = 401
        const val SERVER_ERROR = 500
    }
}
