package me.manga.kira.navigation.routes

import androidx.navigation.NavBackStackEntry

/** Private, failure-only handshake between two concrete back-stack entries, not destination types. */
internal class WebViewSolverReturn(
    private val owner: NavBackStackEntry,
) {
    fun clear() {
        owner.savedStateHandle.remove<String>(PENDING_BROWSER)
        owner.savedStateHandle.remove<String>(FAILED_BROWSER)
    }

    fun arm(browser: NavBackStackEntry) {
        clear()
        browser.savedStateHandle[SOLVER_OWNER] = owner.id
        owner.savedStateHandle[PENDING_BROWSER] = browser.id
    }

    fun consumeRetry(): Boolean {
        val pending = owner.savedStateHandle.remove<String>(PENDING_BROWSER)
        val failed = owner.savedStateHandle.remove<String>(FAILED_BROWSER)
        // Remove both before invoking application retry code, including a reentrant fresh solve.
        return pending != null && failed != pending
    }

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

private const val SOLVER_OWNER = "webview.solver.owner"
private const val PENDING_BROWSER = "webview.solver.pending_browser"
private const val FAILED_BROWSER = "webview.solver.failed_browser"
