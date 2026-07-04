package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
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
 * back-stack returns to the owning [ownerEntry] (the WebView popped), it fires [onRetry] exactly
 * once so the fetch re-runs with the freshly-minted session cookies. The legacy code delayed ~1s
 * before retrying; the rework relies on the cookies being persisted synchronously by the
 * WebView's cookie store before the pop, so no artificial delay is needed.
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
    onRetry: () -> Unit,
): (url: String, api: String) -> Unit {
    // Pending-retry latch: raised when we navigate away to the WebView, lowered after the retry
    // fires on return. rememberSaveable (not plain remember) because the owning destination
    // leaves composition while the WebView is on top — only saveable state survives via the
    // NavBackStackEntry's SaveableStateHolder, and the latch must still be armed on pop-back.
    var pendingRetry by rememberSaveable { mutableStateOf(false) }

    // When the back-stack top returns to the owning entry while a retry is pending, the WebView
    // was popped → re-run the fetch once. Observing currentBackStackEntryFlow avoids depending on
    // lifecycle-compose APIs that `:ui` doesn't ship; `:composeApp` already has nav-compose.
    LaunchedEffect(ownerEntry) {
        navController.currentBackStackEntryFlow
            .filter { it == ownerEntry }
            .collect {
                if (pendingRetry) {
                    pendingRetry = false
                    onRetry()
                }
            }
    }

    return { url, api ->
        // No embedded WebView on this platform (Desktop-macOS): don't strand the user on a dead
        // placeholder screen. The VM already set its error state, so the error pane is shown.
        if (isEmbeddedWebViewAvailable()) {
            pendingRetry = true
            navController.safeNavigate(Screen.WebView(url = url, api = api))
        }
    }
}
