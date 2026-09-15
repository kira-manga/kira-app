package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.coroutines.flow.filter
import me.manga.kira.core.webview.isEmbeddedWebViewAvailable
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate

/**
 * Builds the `onSolveCloudflareChallenge` callback shared by the Details and Reader route
 * adapters — the rework equivalent of the legacy `Handle403Error` + auto-retry-on-dismiss flow
 * (Details bug #2 / Reader parity item #6).
 *
 * On invocation it navigates to [Screen.WebView] for the source so the user can clear the
 * Cloudflare / anti-bot challenge (which primes the per-source cookie/header store the singleton
 * Coil `ImageLoader` + the source HTML fetch both read). It then arms a one-shot: when the nav
 * back-stack returns to the owning [ownerEntry] (the WebView popped), it fires [onRetry] with the
 * captured opaque recovery request exactly once after a healthy browser close. An initialization-
 * failed return consumes the one-shot
 * without retrying. Header persistence remains asynchronous; this choreography does not guarantee
 * that a write has committed before retry (the separate persistence-ordering issue remains open).
 *
 * **Capability gate.** When the platform has no working embedded WebView (Desktop-macOS, where
 * KCEF is hard-skipped — see [me.manga.kira.core.webview.isEmbeddedWebViewAvailable]), the
 * callback is a no-op: navigating to the WebView would only strand the user on a non-functional
 * placeholder screen (up to `MAX_CLOUDFLARE_ATTEMPTS` times). The VM has already set its error
 * state before emitting the solve effect, so the error pane (with its Open-in-WebView/browser
 * fallback) is what the user sees instead.
 *
 * SRP: this helper owns ONLY the 403→WebView→retry choreography; the navigation-target mapping
 * (`Screen.WebView`) stays in `:composeApp` per the campaign clean-architecture guardrail.
 */
@Composable
internal fun rememberCloudflareChallengeSolver(
    navController: NavController,
    ownerEntry: NavBackStackEntry,
    onRetry: (requestId: String) -> Unit,
    recoveryRequestId: (url: String, api: String) -> String?,
    isAvailable: () -> Boolean = ::isEmbeddedWebViewAvailable,
): (url: String, api: String) -> Unit {
    val currentRetry by rememberUpdatedState(onRetry)
    val currentRequestId by rememberUpdatedState(recoveryRequestId)
    // The entry's SavedStateHandle survives the owner leaving composition while the browser is up.
    LaunchedEffect(navController, ownerEntry) {
        navController.currentBackStackEntryFlow
            .filter { it === ownerEntry && navController.currentBackStackEntry === ownerEntry }
            .collect {
                val result = WebViewSolverReturn(ownerEntry)
                val requestId = result.recoveryRequestId
                if (result.consumeRetry() && requestId != null) currentRetry(requestId)
            }
    }
    return { url, api ->
        currentRequestId(url, api)?.let { requestId ->
            openCloudflareSolver(navController, ownerEntry, Screen.WebView(url, api), requestId, isAvailable)
        }
    }
}

private fun openCloudflareSolver(
    navController: NavController,
    owner: NavBackStackEntry,
    route: Screen.WebView,
    requestId: String,
    isAvailable: () -> Boolean,
) {
    if (navController.currentBackStackEntry !== owner) return
    val result = WebViewSolverReturn(owner)
    result.clear()
    if (!isAvailable()) return
    navController.safeNavigate(route)
    navController.currentBackStackEntry?.let { browser ->
        // safeNavigate may refuse a transition: only a concrete, newly pushed browser arms the owner.
        if (
            browser !== owner &&
            navController.previousBackStackEntry === owner &&
            browser.destination.hasRoute<Screen.WebView>()
        ) {
            result.arm(browser, requestId)
        }
    }
}
