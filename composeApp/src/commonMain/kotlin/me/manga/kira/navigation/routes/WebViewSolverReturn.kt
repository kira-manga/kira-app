package me.manga.kira.navigation.routes

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry

/** Private, failure-only handshake between two concrete back-stack entries, not destination types. */
internal class WebViewSolverReturn(
    private val owner: NavBackStackEntry,
) {
    private val latch = WebViewSolverRetryLatch(owner.savedStateHandle)

    val recoveryRequestId: String? get() = latch.recoveryRequestId

    fun clear() = latch.clear()

    fun arm(
        browser: NavBackStackEntry,
        requestId: String,
    ) {
        browser.savedStateHandle[SOLVER_OWNER] = owner.id
        latch.arm(browser.id, requestId)
    }

    fun consumeRetry(): Boolean = latch.consumeRetry()

    fun fail(browser: NavBackStackEntry): WebViewFailureMarker? {
        if (!owns(browser)) return null
        owner.savedStateHandle[FAILED_BROWSER] = browser.id
        return WebViewFailureMarker(owner, browser.id)
    }

    fun clearFailure(browser: NavBackStackEntry) {
        if (owns(browser)) WebViewFailureMarker(owner, browser.id).rollback()
    }

    private fun owns(browser: NavBackStackEntry): Boolean =
        browser.savedStateHandle.get<String>(SOLVER_OWNER) == owner.id &&
            owner.savedStateHandle.get<String>(PENDING_BROWSER) == browser.id
}

internal class WebViewFailureMarker(
    private val owner: NavBackStackEntry,
    private val browserId: String,
) {
    fun rollback() {
        if (owner.savedStateHandle.get<String>(FAILED_BROWSER) == browserId) {
            owner.savedStateHandle.remove<String>(FAILED_BROWSER)
        }
    }
}

/** Saved, one-shot retry data; testable without Compose, a browser or a navigation host. */
internal class WebViewSolverRetryLatch(
    private val state: SavedStateHandle,
) {
    val recoveryRequestId: String? get() = state[RECOVERY_REQUEST]

    fun clear() {
        state.remove<String>(PENDING_BROWSER)
        state.remove<String>(FAILED_BROWSER)
        state.remove<String>(RECOVERY_REQUEST)
    }

    fun arm(
        browserId: String,
        requestId: String,
    ) {
        clear()
        state[PENDING_BROWSER] = browserId
        state[RECOVERY_REQUEST] = requestId
    }

    fun consumeRetry(): Boolean {
        val pending = state.get<String>(PENDING_BROWSER)
        val failed = state.get<String>(FAILED_BROWSER)
        // Clear before application code runs, including reentrant navigation to a fresh solver.
        clear()
        return pending != null && failed != pending
    }
}

private const val SOLVER_OWNER = "webview.solver.owner"
private const val PENDING_BROWSER = "webview.solver.pending_browser"
private const val FAILED_BROWSER = "webview.solver.failed_browser"
private const val RECOVERY_REQUEST = "webview.solver.recovery_request"
