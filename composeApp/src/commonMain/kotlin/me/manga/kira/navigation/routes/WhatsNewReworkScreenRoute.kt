package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.platform.intent.IntentLauncher
import me.manga.kira.presentation.whatsnew.WhatsNewEffect
import me.manga.kira.presentation.whatsnew.WhatsNewViewModel
import me.manga.kira.ui.whatsnew.WhatsNewScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the architecture-rework What's New screen (Phase 7.x.whatsnew).
 *
 * Adapter between the NavHost (Nav 2.9.2 type-safe `composable<Screen.WhatsNewRework>`) and the
 * `:ui/.../whatsnew/WhatsNewScreen` composable. Owns the rework [WhatsNewViewModel] via Koin.
 *
 * **Effect bridging**: the `:ui` [WhatsNewScreen] takes a single
 * `onEffect: (WhatsNewEffect) -> Unit` callback (same shape as [AboutReworkScreenRoute]'s
 * effect handler). The adapter resolves the platform [IntentLauncher] via Koin and dispatches
 * [WhatsNewEffect.OpenVideo] to `launcher.openUrl` (GAP-WN-01 — DEVIATION(platform) substitute
 * for the native inline VideoView).
 *
 * **Get-Started dismiss**: the last-page Get-Started button marks the screen seen (inside the
 * screen) then asks the host to dismiss — the adapter pops back to the previous screen via
 * `navController.safePopBackStack()` (GAP-WN-03 / GAP-WN-05; the About screen is the only
 * inbound nav edge today).
 *
 * **Why this lives in `:composeApp` and not in `:ui`**: same posture as [AboutReworkScreenRoute]
 * / [StatisticsReworkScreenRoute] — `:ui` deliberately depends on `:presentation` (which knows
 * the VM) but NOT on `androidx.navigation` (which is `:composeApp`-level wiring). Housing the
 * nav + platform-launch bridges at this layer keeps `:ui` cleanly free of navigation types and
 * preserves the uniform "every screen has a route adapter at this layer" shape.
 *
 * @param navController parent nav controller — consumed by the Get-Started dismiss pop.
 * @param backStackEntry passed through for parity with sibling route adapters (unused — the VM
 *                      is `koinViewModel()`-scoped via Koin's ViewModelStoreOwner integration,
 *                      so we don't consult `backStackEntry` for VM scoping here).
 */
@Composable
fun WhatsNewReworkScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: WhatsNewViewModel = koinViewModel()
    val launcher: IntentLauncher = koinInject()
    WhatsNewScreen(
        viewModel = viewModel,
        onEffect = { effect ->
            when (effect) {
                // GAP-WN-01: feature video posters open the video URL externally
                // (DEVIATION(platform) substitute for the native inline VideoView).
                is WhatsNewEffect.OpenVideo -> launcher.openUrl(effect.url)
            }
        },
        // GAP-WN-03 / GAP-WN-05: the last-page Get-Started button marks the screen seen
        // (inside the screen) then asks the host to dismiss — pop back to the previous screen
        // (the About screen, the only inbound nav edge today).
        onGetStarted = { navController.safePopBackStack() },
    )
}

/**
 * **Audit-trail postscript** (review-campaign fix-05, 2026-06-12): the prior
 * Task #613 postscript recorded a verification of the Phase 7.x.whatsnew
 * *foundation* shape ("terminal display surface — no outbound nav, no
 * external-launch effects; onEffect is a dormant no-op for an empty sealed
 * WhatsNewEffect; no IntentLauncher injected") that no longer held against
 * the code it sat in: the adapter injects [IntentLauncher], dispatches
 * [WhatsNewEffect.OpenVideo] to `launcher.openUrl`, and consumes
 * [navController] for the Get-Started dismiss pop. The contradicted
 * postscript was dropped and the file KDoc above rewritten to describe the
 * current surface (code-ac-15).
 */
