package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.platform.intent.IntentLauncher
import me.manga.kira.presentation.whatsnew.WhatsNewEffect
import me.manga.kira.presentation.whatsnew.WhatsNewIntent
import me.manga.kira.presentation.whatsnew.WhatsNewViewModel
import me.manga.kira.ui.whatsnew.WhatsNewScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the [Screen.WhatsNewScreen] nav entry — **Phase 7.x.whatsnew.swap**.
 *
 * **What changed in this slice**: this adapter no longer renders the legacy
 * `:composeApp/.../features/whatsnew/ui/WhatsNewScreen.kt` composable backed by the legacy
 * `me.manga.kira.presentation.features.whatsnew.viewmodel.WhatsNewViewModel`. It now renders
 * the architecture-rework [me.manga.kira.ui.whatsnew.WhatsNewScreen] backed by
 * [me.manga.kira.presentation.whatsnew.WhatsNewViewModel] (Koin-bound via
 * `whatsNewReworkModule`). The `Screen.WhatsNewScreen(val isFirstOpen: Boolean)` route entry
 * stays the same shape, so the two existing callers stay working without modification:
 *  - `LibraryScreenRoute.kt:100` — auto-popup on first launch with `isFirstOpen = true`. The
 *    `markWhatsNewAsSeen()` side-effect (which gates whether the next launch auto-popups
 *    again) is preserved by dispatching [WhatsNewIntent.OnMarkSeen] in a [LaunchedEffect]
 *    when [Screen.WhatsNewScreen.isFirstOpen] is `true`. See **Mark-seen semantics** below.
 *  - `AboutScreenRoute.kt:32` — user-navigated entry from the legacy About row with
 *    `isFirstOpen = false`. No mark-seen needed (the auto-popup gate doesn't care; user
 *    explicitly opened it).
 *
 * Both this route and the parallel [Screen.WhatsNewRework] route now converge on the same
 * rework screen + rework VM through different `NavBackStackEntry`-scoped VM instances (see
 * **Koin lifecycles** below).
 *
 * **Pre-conditions met by Phase 7.x.whatsnew slices (Tasks #245 / #247 / #248)**: the rework
 * WhatsNew slice shipped the 4-state surface — Loading (centered spinner), Error (centered
 * error text + Retry button dispatching `WhatsNewIntent.OnRetry`), Empty (centered "No new
 * features in this version" placeholder), Loaded (`HorizontalPager` of feature cards + dot
 * indicator row beneath, swipe-driven page change mirrored back into state via
 * `WhatsNewIntent.OnPageChanged`). Same backing `WhatsNewRepository` (strangler-fig via the
 * `:data` impl over the legacy `:shared` `WhatsNewRemoteDataSource` + `SharedPrefsHelper` +
 * `AppVersionProvider` + `getDefaultFeatures()` — see `whatsNewReworkModule`).
 *
 * **Mark-seen semantics — preserved across mark-on-enter vs mark-on-dismiss**:
 *  - Legacy adapter calls `viewModel.markWhatsNewAsSeen()` inside the loaded-state's
 *    `onDismiss` lambda IFF `args.isFirstOpen` is `true`. The mark fires synchronously when
 *    the user dismisses the dialog (tap "Close" / system back).
 *  - Rework adapter dispatches [WhatsNewIntent.OnMarkSeen] in a [LaunchedEffect] keyed on
 *    `Unit` IFF `args.isFirstOpen` is `true`. The mark fires when the screen mounts (the
 *    user is reading it — equivalent for the auto-popup gate). Mark-on-enter and
 *    mark-on-dismiss produce the same observable outcome for the next-launch gate, with one
 *    edge-case delta: if the app crashes between mount-and-dismiss, the rework still marks
 *    while the legacy would not. The crash-before-dismiss case is degenerate (the user has
 *    seen the screen; gating against re-popup is the right outcome). Same posture as the
 *    rework `WhatsNewViewModel.init` block which loads features unconditionally on mount.
 *
 * **Affordance parity vs the legacy adapter**:
 *  - **Loading state** — legacy renders inline `LoadingState` composable (spinner + literal
 *    "Loading What's New..." label). Rework renders centered [CircularProgressIndicator] only
 *    (no label). Visual delta; equivalent behaviour. Phase 10 i18n will revisit label text
 *    if needed.
 *  - **Error state** — legacy renders inline `ErrorState` composable with Retry + Close
 *    buttons. Rework renders centered error message + Retry button (no Close). The Close
 *    affordance is redundant with system back at this depth — same justification as §123.5 /
 *    §124.5 (route reached from `safeNavigate(...)` push that leaves the parent on the
 *    stack). Today the error path is wired-but-dormant — the `:data` impl swallows remote
 *    failures and returns the empty default list, so the empty-state path is the de-facto
 *    failure surface.
 *  - **Empty state** — legacy renders inline `EmptyState` composable with literal text + a
 *    Close button. Rework renders centered "No new features in this version" placeholder
 *    (no Close). Same Close-button-vs-system-back rationale.
 *  - **Loaded state** — legacy renders `composeApp/.../WhatsNewScreen` with the legacy's
 *    pager (NavigationButtons + image / video / fullscreen rendering — see rework KDoc lines
 *    65-104 for the deferred sub-slices). Rework renders the `HorizontalPager` + dot row
 *    only; image / video / fullscreen / navigation-arrows are deferred to
 *    `Phase 7.x.whatsnew.images` / `.video` / `.fullscreen` / `.navbuttons` per the rework
 *    screen's KDoc. The deferred surfaces are NOT currently user-reachable today because
 *    the data flow ([me.manga.kira.domain.usecase.whatsnew.GetWhatsNewFeaturesUseCase])
 *    returns an empty list after the KMP migration (legacy `getDefaultFeatures()` repopulation
 *    is itself a Phase 10 task). When the data flow comes back online, the missing surfaces
 *    DO become user-reachable and the deferred sub-slices port them. Until then, the loaded
 *    state is provably-unreached today, so this swap is observably equivalent to the legacy
 *    on the currently-reachable surfaces (Loading / Error / Empty).
 *
 * **Visual delta vs the legacy**: the legacy screen renders feature cards with bundled
 * `Res.drawable.*` image lookups + Coil `AsyncImage` for remote URLs + platform-specific
 * `VideoPlayer` composables (Android `ExoPlayer` + iOS `AVPlayer` + Desktop `VLCJ`). The
 * rework substitutes a flat title + description + optional "NEW" chip — same icon-free posture
 * as the rework Library / Statistics / History rework screens. The deferral mirrors the
 * rework WhatsNewScreen KDoc lines 78-104 verbatim. No affordance loss FOR THE CURRENTLY
 * REACHABLE STATES (Loading / Error / Empty). When the loaded state's data flow comes back,
 * the missing media surfaces lift in dedicated sub-slices BEFORE Phase 9.x route retirement.
 *
 * **Layer-boundary preservation**: same posture as [RepoSettingsScreenRoute] (Phase
 * 7.x.reposettings.swap, §123) / [StatisticsScreenRoute] (Phase 7.x.statistics.swap, §124) /
 * [HistoryScreenRoute] (Phase 7.x.history.swap, §125) and the other rework-screen adapters.
 * `:ui` deliberately does NOT depend on `androidx.navigation` (which is `:composeApp`-level
 * wiring); the rework WhatsNewScreen exposes a generic `onEffect: (WhatsNewEffect) -> Unit`
 * callback so the screen stays nav-host-agnostic. The route adapter at this layer threads
 * the `args.isFirstOpen` → `submit(OnMarkSeen)` decision via [LaunchedEffect].
 *
 * **Back-press delta vs the legacy adapter**: the legacy adapter wired explicit Close
 * buttons in the Loading / Error / Empty states (calling `navController.safePopBackStack()`).
 * The rework screen has no Close buttons; it relies on system back. The
 * [me.manga.kira.navigation.safePopBackStack] defensive fallback (pop to Library if back
 * stack is empty) is dropped. Same posture and same justification as §123.5 / §124.5 / §125.5:
 * the route is only reached from Library's auto-popup or About's WhatsNew row, both of which
 * leave the parent on the stack — the defensive fallback is unreachable in normal use. On
 * Desktop where there is no edge-swipe gesture, users can use the host window's back
 * behavior (system-default); the established pattern across all rework screens.
 *
 * **MVI surface**: the rework's [WhatsNewViewModel] exposes a single
 * `StateFlow<WhatsNewState>` (4-tuple of `isLoading` + `errorMessage` + `features` +
 * `currentPage`) vs the legacy VM's 3 separate StateFlows (`features` / `isLoading` /
 * `loadError`). All mutations flow through `WhatsNewIntent` (`OnRetry` / `OnMarkSeen` /
 * `OnPageChanged`). One-shot effects ([WhatsNewEffect]) are an empty sealed interface today —
 * the screen's `onEffect` callback bridge is dormant. The route adapter dispatches
 * `OnMarkSeen` once on mount via `LaunchedEffect(Unit)`; this is the only adapter-level
 * intent dispatch (mirrors the legacy adapter's only side-effect call on dismiss).
 *
 * **Koin lifecycles** — note that the [WhatsNewViewModel] resolved here via [koinViewModel]
 * and the one resolved by [WhatsNewReworkScreenRoute] are scoped to their respective
 * NavBackStackEntry (the `ViewModelStoreOwner` integration provided by
 * `androidx.lifecycle.viewmodel.compose`). So even though both routes ultimately render the
 * same screen and rely on the same Koin binding, each route's VM instance is independent.
 * The underlying repository
 * ([me.manga.kira.domain.repository.WhatsNewRepository] — `single`-scoped via
 * `whatsNewReworkModule`) is shared across both, so the persisted state (whether the user
 * has seen the current version's WhatsNew, the cached feature list) is identical across the
 * two routes; any `markSeen` on either route surfaces on the other through the underlying
 * `SharedPrefsHelper` write.
 *
 * The legacy composable file itself
 * (`composeApp/.../features/whatsnew/ui/WhatsNewScreen.kt`) plus the legacy
 * `me.manga.kira.presentation.features.whatsnew.viewmodel.WhatsNewViewModel` are no longer
 * user-reachable through this adapter, but stay on disk until **Phase 9.x route-swap
 * retirement sweep** retires them in a coordinated deletion sweep across all retired legacy
 * screens.
 *
 * @param navController parent nav controller — passed through for parity with sibling route
 *                      adapters; unused (WhatsNew has no outbound in-app nav today).
 * @param backStackEntry source of the [Screen.WhatsNewScreen.isFirstOpen] argument (read via
 *                      [androidx.navigation.toRoute]).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster18.staleKdocSweep.cascade,
 * Task #474, 2026-05-28): two categories of stale + inverted citations
 * appear above:
 *  - Lines 18-22 ("this adapter no longer renders the legacy
 *    `:composeApp/.../features/whatsnew/ui/WhatsNewScreen.kt` composable
 *    backed by the legacy
 *    `me.manga.kira.presentation.features.whatsnew.viewmodel.WhatsNewViewModel`")
 *    + Lines 47-49 (legacy adapter mark-on-dismiss anchor) + Lines 60-94
 *    (legacy adapter parity bullets — `LoadingState` / `ErrorState` /
 *    `EmptyState` / `composeApp/.../WhatsNewScreen` + image / video /
 *    fullscreen / `Res.drawable.*` / `AsyncImage` / `ExoPlayer` /
 *    `AVPlayer` / `VLCJ`) + Lines 104-105 (legacy Close buttons): the
 *    legacy `:composeApp/.../features/whatsnew/ui/WhatsNewScreen.kt`
 *    composable was retired in Phase 9.x.whatsnew.legacyui.retire (§351
 *    sweep); verified by filesystem check returning zero hits. The
 *    legacy `WhatsNewViewModel` STILL EXISTS on disk and continues to
 *    power the first-launch redirect in `LibraryScreenRoute.kt:14` —
 *    the §351 retire scope was the UI composable + helpers, not the VM
 *    behind the first-launch gate. The legacy-renders-vs-rework-renders
 *    historical-record framing + parity-bullet rationale + mark-on-enter-
 *    vs-mark-on-dismiss semantic-equivalence + Close-button-vs-system-
 *    back-justification all stand on their own merits past the §351
 *    UI retire — the rework `:ui` `WhatsNewScreen` continues to render
 *    Loading + Error + Empty + Loaded states (deferred media surfaces
 *    documented in the rework KDoc).
 *  - Lines 135-140 ("The legacy composable file itself ... plus the
 *    legacy `WhatsNewViewModel` are no longer user-reachable through
 *    this adapter, but stay on disk until **Phase 9.x route-swap
 *    retirement sweep** retires them"). PARTIALLY-FULFILLED — Phase
 *    9.x.whatsnew.legacyui.retire (§351) executed the UI portion of the
 *    predicted retirement sweep; legacy `WhatsNewScreen.kt` is gone.
 *    The legacy `WhatsNewViewModel` is intentionally retained (LIVE in
 *    LibraryScreenRoute.kt's first-launch redirect orchestration), so
 *    the "stay on disk until retirement sweep" forecast is half-
 *    fulfilled — UI retired, VM retained as a deliberate strangler-fig
 *    seam. Future `Phase 7.x.library.firstlaunch.rework` slice would
 *    migrate the first-launch gate into the rework `LibraryViewModel` /
 *    a dedicated `:domain` `ObserveShouldShowWhatsNewUseCase`; after
 *    that, the legacy WhatsNewViewModel itself becomes orphan-retire-
 *    eligible. Mirror of §445 + §470 + §471 + §472 + §473 fulfilled-
 *    deferral-inversion precedent (with the half-fulfilled nuance).
 * The Koin lifecycle scoping + layer-boundary preservation + MVI-surface
 * comparison + mark-on-enter rationale + WhatsNewRepository strangler-
 * fig delegation all stand on their own merits past the §351 retire +
 * §290 swap. The `WhatsNewScreenRoute` adapter remains LIVE as the
 * §290-swapped renderer for `Screen.WhatsNewScreen` (now converging on
 * the rework path alongside `WhatsNewReworkScreenRoute` for
 * `Screen.WhatsNewRework`). Original §253-era prose preserved verbatim
 * per the audit-trail-preservation convention — the citations are
 * historical record of the swap lineage including the retirement-sweep
 * forecast that was subsequently half-fulfilled (UI retired, VM retained
 * as strangler-fig seam).
 */
@Composable
fun WhatsNewScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
) {
    val args = backStackEntry.toRoute<Screen.WhatsNewScreen>()
    val viewModel: WhatsNewViewModel = koinViewModel()
    val launcher: IntentLauncher = koinInject()

    if (args.isFirstOpen) {
        LaunchedEffect(Unit) {
            viewModel.submit(WhatsNewIntent.OnMarkSeen)
        }
    }

    WhatsNewScreen(
        viewModel = viewModel,
        onEffect = { effect ->
            when (effect) {
                // GAP-WN-01: feature video posters open the video URL externally (DEVIATION(platform)
                // substitute for the native inline VideoView — no cross-platform inline player in :ui).
                is WhatsNewEffect.OpenVideo -> launcher.openUrl(effect.url)
            }
        },
        // GAP-WN-03 / GAP-WN-05: Get-Started on the last page marks seen + dismisses (pop back).
        onGetStarted = { navController.safePopBackStack() },
    )
}
