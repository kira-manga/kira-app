package me.manga.kira.navigation.routes

import androidx.lifecycle.SavedStateHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebViewSolverRetryLatchTest {
    @Test
    fun consumptionClearsTheCapturedTicketBeforeAReentrantFreshSolve() {
        val latch = WebViewSolverRetryLatch(SavedStateHandle())
        latch.arm("browser-1", "operation-1")
        val captured = latch.recoveryRequestId
        assertTrue(latch.consumeRetry())
        assertNull(latch.recoveryRequestId)
        assertFalse(latch.consumeRetry())
        latch.arm("browser-2", "operation-2")
        assertEquals("operation-1", captured)
        assertEquals("operation-2", latch.recoveryRequestId)
        assertTrue(latch.consumeRetry())
    }

    @Test
    fun failedReturnConsumesItsTicketWithoutPoisoningTheNextAttempt() {
        val state = SavedStateHandle()
        val latch = WebViewSolverRetryLatch(state)
        latch.arm("failed-browser", "failed-operation")
        state["webview.solver.failed_browser"] = "failed-browser"
        assertFalse(latch.consumeRetry())
        assertNull(latch.recoveryRequestId)
        assertTrue(state.keys().isEmpty())
        latch.arm("healthy-browser", "healthy-operation")
        assertEquals("healthy-operation", latch.recoveryRequestId)
        assertTrue(latch.consumeRetry())
    }

    @Test
    fun replacementAndClearCannotLeakATicketBetweenOwners() {
        val first = WebViewSolverRetryLatch(SavedStateHandle())
        val second = WebViewSolverRetryLatch(SavedStateHandle())
        first.arm("old-browser", "old-operation")
        first.arm("replacement-browser", "replacement-operation")
        second.arm("other-browser", "other-operation")
        assertEquals("replacement-operation", first.recoveryRequestId)
        first.clear()
        assertFalse(first.consumeRetry())
        assertEquals("other-operation", second.recoveryRequestId)
        assertTrue(second.consumeRetry())
    }
}
