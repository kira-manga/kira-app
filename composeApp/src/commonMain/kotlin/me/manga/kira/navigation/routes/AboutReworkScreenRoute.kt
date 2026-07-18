@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.platform.intent.IntentLauncher
import me.manga.kira.platform.review.InAppReviewClient
import me.manga.kira.presentation.about.AboutEffect
import me.manga.kira.presentation.about.AboutViewModel
import me.manga.kira.ui.about.AboutScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the architecture-rework About screen (Phase 7.x.about +
 * Phase 7.x.about.whatsnewrow).
 *
 * Adapter between the NavHost (Nav 2.9.2 type-safe `composable<Screen.AboutRework>`) and the
 * `:ui/.../about/AboutScreen` composable. Owns the rework [AboutViewModel] via Koin and bridges
 * [AboutEffect] emissions to the legacy [IntentLauncher] facade + the parent [NavController].
 *
 * **One in-app nav callback (added Phase 7.x.about.whatsnewrow)**: the rework About screen has
 * one in-app outbound edge — the "What's new" row navigates to
 * [me.manga.kira.navigation.Screen.WhatsNewRework] (the rework WhatsNew route added by
 * Phase 7.x.whatsnew, commit `e5d91b0`). The route adapter holds the parent [NavController]
 * reference and the `Screen` ADT (both `:composeApp`-layer types); the VM stays free of those
 * layer-specific concerns. [backStackEntry] is accepted for parity with the sibling
 * route-adapter signatures but unused (the VM is `koinViewModel()`-scoped via Koin's
 * ViewModelStoreOwner integration).
 *
 * **Back-press posture** (Phase 7.x.about.backbutton): the rework AboutScreen now exposes an
 * `onBack: () -> Unit` parameter rendered as a "Back" [androidx.compose.material3.TextButton]
 * in its [androidx.compose.material3.TopAppBar.navigationIcon] slot — same observable affordance
 * as the legacy `composeApp/.../features/about/screen/AboutScreen.kt`'s
 * `Icons.AutoMirrored.Filled.ArrowBack`. The callback delegates to
 * [me.manga.kira.navigation.safePopBackStack] — same defensive fallback as the legacy
 * [AboutScreenRoute] which still passes `onBack = { navController.safePopBackStack() }`.
 * Without this slice the rework AboutScreen had no visible back affordance — users
 * navigating in via Settings (and post-Phase 7.x.about.swap, via the legacy
 * `Screen.AboutScreen` route) had to rely on system back (Android hardware back / iOS
 * swipe / Desktop ESC). This slice restores the visible chrome to match the legacy.
 *
 * **Effect bridging**: the rework AboutScreen exposes a single `onEffect: (AboutEffect) -> Unit`
 * callback (NOT per-callback parameters like the Reader's `onOpenInWebView` /
 * `onNavigateToReader` etc.). The route adapter switches on the effect's `when` block and
 * dispatches to the platform facade + the nav controller:
 *  - [AboutEffect.OpenPlayStorePage] → [IntentLauncher.openPlayStorePage] with the carried
 *    `packageName`. On Android tries `market://` first, falls back to `play.google.com`. On
 *    iOS / Desktop opens the web URL directly (no native Play Store).
 *  - [AboutEffect.OpenUrl] → [IntentLauncher.openUrl] with the carried `url` verbatim. On
 *    Android issues `ACTION_VIEW`; on iOS routes through `UIApplication.openURL`; on Desktop
 *    uses AWT `Desktop.browse`.
 *  - [AboutEffect.NavigateToWhatsNew] → `navController.navigate(Screen.WhatsNewRework)`.
 *    The target route + composable were registered by Phase 7.x.whatsnew's `App.kt` block; this
 *    is the first in-app surface that lights up the rework WhatsNew route (end-user discovery
 *    still depends on Phase 9.x route-swap, which retires the legacy About + WhatsNew routes —
 *    until then, both pairs of routes coexist and the rework About → rework WhatsNew flow is
 *    exercised via developer trigger / debug nav entry).
 *
 * Both [IntentLauncher] methods are fire-and-forget (failures are caught + logged via Kermit, no
 * exceptions propagate) — same posture as every other call site of the legacy launcher (the
 * legacy `AboutScreen.kt` invokes them the same way). No `try/catch` here; no error effect path.
 *
 * **Which launcher is in play — the `:platform` SPI**: Phase 5.3 relocated the launcher into
 * `:platform` as an interface (`:platform/.../intent/IntentLauncher.kt` with three impls —
 * `AndroidIntentLauncher` / `IosIntentLauncher` / `DesktopIntentLauncher`). That SPI IS bound
 * to Koin per target by `PlatformModule.{android,ios,desktop}.kt`
 * (`single<IntentLauncher> { AndroidIntentLauncher(androidContext()) }` etc.) and is exactly
 * what this adapter imports and `koinInject()`s — the "Phase 8.z.platform-rewire" deferral
 * once documented here has effectively happened for this type.
 *
 * **Scope**: this is the entry point that proves the About slice end-to-end —
 *  1. **Koin DI** ([me.manga.kira.di.aboutReworkModule]) resolves the [AboutViewModel]
 *     constructor's one use case
 *     ([me.manga.kira.domain.usecase.about.GetAppMetadataUseCase]) and the `single`-scoped
 *     [me.manga.kira.domain.repository.AboutRepository] it depends on (which strangler-fig
 *     delegates to the legacy `:shared` `AppVersionProvider`'s `versionName` + `packageName`
 *     reads). The legacy [IntentLauncher] is also resolved here via `koinInject()` — the route
 *     adapter is the bridge between the VM's effect stream and the platform actuals.
 *  2. **`:presentation` MVI** plumbing emits
 *     [me.manga.kira.presentation.about.AboutState] via `StateFlow` and
 *     [AboutEffect] via `Channel<AboutEffect>.receiveAsFlow()`. The screen's `EffectBridge`
 *     internal composable collects the flow and forwards each emission to the [onEffect]
 *     callback this adapter passes — Channel-backed Flow guarantees at-most-once delivery per
 *     emission (no replay on recomposition, no skipped emissions on screen recompositions).
 *  3. **`:ui` Compose** renders the version row + three action rows in a Material 3 Card with
 *     the rework's `LocalSpacing` tokens. No icons — matches the icon-free posture of the other
 *     rework `:ui` screens (`HistoryScreen` / `StatisticsScreen` / `UpdatesScreen` /
 *     `SourcesScreen` / `ThemeScreen`).
 *
 * **Why this lives in `:composeApp` and not in `:ui`**: same posture as
 * [ThemeReworkScreenRoute] / [SourcesReworkScreenRoute] / [StatisticsReworkScreenRoute] /
 * [LibraryReworkScreenRoute] / [UpdatesReworkScreenRoute] — `:ui` deliberately depends on
 * `:presentation` (which knows the VM) but NOT on `androidx.navigation` (which is
 * `:composeApp`-level wiring) and NOT on `:shared` / `:platform` (which owns the platform
 * facades). The About screen has no outbound in-app nav but DOES have an outbound platform
 * dependency (the launcher); housing the bridge at this layer keeps `:ui` cleanly free of
 * platform-facade types. Every screen has a route adapter at this layer regardless of whether
 * it consumes nav callbacks or platform facades or neither — the uniform shape is the win.
 *
 * @param navController parent nav controller — invoked for [AboutEffect.NavigateToWhatsNew]
 *                      (Phase 7.x.about.whatsnewrow). All other effects route through the
 *                      [IntentLauncher] facade.
 * @param backStackEntry passed through for parity with sibling route adapters (unused — the VM
 *                      is `koinViewModel()`-scoped via Koin's ViewModelStoreOwner integration,
 *                      so we don't consult `backStackEntry` for VM scoping here).
 *
 * **Audit-trail postscript** (Phase 9.x.updates.staleKdocSweep.cascade,
 * Task #456, 2026-05-28): two stale citations into the §354-retired legacy
 * `composeApp/.../features/about/screen/AboutScreen.kt` appear above:
 *  - Lines 35-36 (back-press posture paragraph): "same observable
 *    affordance as the legacy
 *    `composeApp/.../features/about/screen/AboutScreen.kt`'s
 *    `Icons.AutoMirrored.Filled.ArrowBack`".
 *  - Line 63 (IntentLauncher fire-and-forget paragraph): "the legacy
 *    `AboutScreen.kt` invokes them the same way".
 * The legacy `composeApp/.../features/about/screen/AboutScreen.kt` was
 * retired in Phase 9.x.settings_about.legacyui.retire (§354, multi-commit
 * chain `5cc42d2` "(1/4): drop unreachable legacy SettingsScreen +
 * AboutScreen + SettingsNavigationItem" + `b0387cb` + `171050c` + `d8404a1`);
 * verified by a filesystem check returning zero hits for that path. The
 * back-button affordance and fire-and-forget-IntentLauncher rationales
 * stand on their own merits — the rework adapter's `safePopBackStack` and
 * the `:platform` IntentLauncher SPI's eat-failures-and-log posture are
 * documented inline above and independent of which legacy file originally
 * implemented the parity precedent. Phase 8.z.platform-rewire remains the
 * canonical opportunity to retire the legacy `:shared` IntentLauncher in
 * favour of the `:platform` actuals. Original §253-era prose preserved
 * verbatim per the audit-trail-preservation convention — the citations
 * are historical record of the design lineage; the route adapter continues
 * to bridge effects correctly through the legacy retire.
 */
@Composable
fun AboutReworkScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: AboutViewModel = koinViewModel()
    val launcher: IntentLauncher = koinInject()
    val reviewClient: InAppReviewClient = koinInject()
    val coroutineScope = rememberCoroutineScope()
    AboutScreen(
        viewModel = viewModel,
        onEffect = { effect ->
            when (effect) {
                is AboutEffect.OpenPlayStorePage -> launcher.openPlayStorePage(effect.packageName)
                AboutEffect.RequestReview -> coroutineScope.launch { reviewClient.requestReview() }
                is AboutEffect.OpenUrl -> launcher.openUrl(effect.url)
                AboutEffect.NavigateToWhatsNew -> navController.safeNavigate(Screen.WhatsNewRework)
            }
        },
        onBack = { navController.safePopBackStack() },
    )
}
