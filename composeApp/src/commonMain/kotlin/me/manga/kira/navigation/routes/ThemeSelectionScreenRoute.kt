package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.composeapp.generated.resources.Res
import me.manga.kira.composeapp.generated.resources.you_need_to_enable_notifications
import me.manga.kira.platform.toast.ToastShower
import me.manga.kira.core.platform.rememberNotificationPermissionRequester
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.presentation.theme.ThemeViewModel
import me.manga.kira.ui.themepicker.ThemeScreen
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route adapter for [Screen.Theme] — the onboarding step 2 (Welcome → **Theme** → Sources →
 * RepoSettings → Library).
 *
 * **Post-Phase 7.x.theme.swap (Task #291)**: routes [Screen.Theme] to the architecture-rework
 * [me.manga.kira.ui.themepicker.ThemeScreen] backed by the rework
 * [me.manga.kira.presentation.theme.ThemeViewModel] (Koin-bound via
 * [me.manga.kira.di.themeReworkModule]). Pre-swap, this file routed to the legacy
 * `me.manga.kira.presentation.features.onboarding.theme_selection.ThemeSelectionScreen`
 * backed by `OnboardingViewModel`.
 *
 * The swap consumes the rework `:ui` screen in its full-onboarding-flavour mode — all three
 * optional onboarding parameters wired (see §136/§137):
 *
 *  1. `onContinue` → `navController.navigate(Screen.Sources)` — advances the onboarding wizard
 *     to step 3 (Sources). Sources then auto-seeds default-language sources based on locale,
 *     then RepoSettings (with `isFirstOpen = true`) flips the `first_launch` flag to false and
 *     clears the back stack down to Library on Finish (see [RepoSettingsScreenRoute]).
 *  2. `hasNotificationPermission` ← `rememberNotificationPermissionRequester().hasPermission` —
 *     reactive Compose state from the platform requester facade. The rework ThemeScreen
 *     gates the Continue button on this value when the permission callback is non-null
 *     (see §137).
 *  3. `onRequestNotificationPermission` → `permissionRequester.request()` — re-launches the
 *     platform permission request when the user taps "Grant Permission". The grant row is
 *     rendered only while permission is denied (see §137).
 *
 * Theme state itself (selected `AppTheme`, `pureBlack` toggle) flows through the rework
 * `ThemeViewModel` → `:domain` `ObserveAppThemeUseCase` / `SetAppThemeUseCase` →
 * `:data` `ThemeRepositoryImpl` (strangler-fig) → legacy `:shared` `SettingsRepository`'s
 * `darkModeFlow` + `followSystemFlow` + `pureBlackFlow` + `setDarkMode` + `setFollowSystem` +
 * `setPureBlack`. Same `SharedPreferences` keys as the legacy `OnboardingViewModel` consumed —
 * the swap is transparent to persistence (a user mid-onboarding before the swap, restarted
 * after the swap, sees their theme + pureBlack preference preserved).
 *
 * **Auto-request lifecycle** (preserved verbatim from the legacy `ThemeSelectionScreen.kt:84-90`):
 * a `LaunchedEffect(Unit)` fires the permission request on first composition, gated by an
 * in-composition `autoRequested` `mutableStateOf(false)` flag so re-composes don't re-fire
 * within the same composition. The flag is `remember`, NOT `rememberSaveable` — matches the
 * legacy's posture (a config change re-fires the request, which is harmless since the system
 * surfaces the dialog only when permission is undecided; subsequent calls when already granted/
 * permanently-denied are no-ops at the platform layer).
 *
 * **Toast-on-denial** (native parity, `native-app ThemeSelectionScreen.kt:68-78`): a long toast
 * (via `ToastShower`) is surfaced ONLY when the permission request resolves with an actual user
 * denial — driven off the `request(onResult)` result callback, NOT inferred from `hasPermission`
 * state. Inferring from state was a bug: `hasPermission` is `false` both while the system dialog
 * is in flight and after a denial, so a state-keyed toast fired the moment the dialog opened (even
 * for a user who then granted). The rework `ThemeScreen` itself doesn't surface this — `:ui` has no
 * `ToastShower` access — so the route adapter owns it. Re-firing (via the Grant Permission button)
 * that gets re-denied surfaces the toast again from that request's result callback.
 *
 * **AnimatedBackground gradient overlay** (legacy `ThemeSelectionScreen.kt:92-108`): NOT
 * ported. The legacy wrapped the entire picker in an animated gradient sweep plus a
 * vertical-gradient overlay above it. The rework intentionally drops this — purely cosmetic,
 * no semantic value. Matches the §122 sources.onboardingfinish precedent which also skipped
 * the equivalent legacy decorative background. The rework's flat Scaffold with neutral
 * Material 3 surfaces reads as cleaner and more consistent with the rest of the app.
 *
 * **Visual deltas vs the legacy onboarding ThemeSelectionScreen (intentional, documented in
 * §138 / SOLID_AUDIT.md `# Phase 7.x.theme.swap` block):**
 *  - **TopAppBar with "Theme" title** (rework, unconditional) vs **no top bar + a 24sp
 *    "Choose your theme" headline inside the body** (legacy). Both convey the step name to
 *    the user; the TopAppBar is the rework `:ui` Theme screen's canonical posture (matches
 *    every other rework screen with a TopAppBar). No navigationIcon — onboarding can't go
 *    back to the previous step, same as the legacy.
 *  - **Material 3 TabRow with three text tabs (Light / Dark / System)** (rework) vs a
 *    **vertical list of theme items with custom Box backgrounds** (legacy `ThemeSelector`).
 *    The tab layout is more compact and matches the rework Settings-hub's terminal-screen
 *    posture. Same three options + same persisted state.
 *  - **PureBlackRow with Switch toggle** (rework) is **visible in the onboarding flow** —
 *    the legacy onboarding does NOT expose the PureBlack/OLED preference at this step (it's
 *    only in the legacy Settings screen). The rework treats the PureBlack toggle as part of
 *    the canonical theme picker surface, so it appears in BOTH onboarding AND Settings-hub
 *    entry. Acceptable enrichment of the onboarding flow — the user gains earlier access to a
 *    useful preference without being required to set it (default `false` matches the legacy
 *    initial state; un-toggled, behaviour is identical to pre-swap).
 *  - **Material 3 default Button** with primary-container coloring + inline literal
 *    "Continue" (rework) vs **custom-styled Button** with `50.dp` height + `RoundedCornerShape(26.dp)`
 *    + `shapes.medium` + explicit primary `containerColor` + `onPrimary` label (legacy). Same
 *    semantic CTA. Phase 10 i18n lift will route both consumers through the existing
 *    `continue_string` resource key.
 *
 * `OnboardingViewModel` is no longer injected here — the rework `ThemeViewModel` is the sole
 * source of truth for theme state, and it consumes the same underlying `:shared`
 * `SettingsRepository` flows so the persistence backplane is unchanged. The legacy
 * `OnboardingViewModel` itself was retired in §143 once the rework Welcome / Sources /
 * RepoSettings flow had landed (Phase 7.x.welcome / 7.x.sources.swap / 7.x.reposettings.swap)
 * and the VM was no longer reachable.
 *
 * @param navController parent nav controller — used to navigate forward to [Screen.Sources]
 *                      on Continue.
 * @param backStackEntry passed through for parity with sibling route-adapter signatures
 *                      (unused — the rework [ThemeViewModel] is `koinViewModel()`-scoped via
 *                      Koin's ViewModelStoreOwner integration, so we don't consult
 *                      `backStackEntry` for VM scoping here).
 *
 * **Audit-trail postscript** (Phase 9.x.welcome.staleKdocSweep.cascade,
 * Task #457, 2026-05-28): three stale citations into the §307-retired legacy
 * `composeApp/.../presentation/features/onboarding/theme_selection/ThemeSelectionScreen.kt`
 * appear above:
 *  - Line 56 (auto-request-lifecycle paragraph): "preserved verbatim from
 *    the legacy `ThemeSelectionScreen.kt:84-90`".
 *  - Line 64 (toast-on-denial paragraph): "preserved from the legacy
 *    `ThemeSelectionScreen.kt:78-82`".
 *  - Line 73 (AnimatedBackground gradient overlay paragraph): "legacy
 *    `ThemeSelectionScreen.kt:92-108`".
 * The legacy `presentation/features/onboarding/theme_selection/ThemeSelectionScreen.kt`
 * was retired in Phase 9.x.onboarding.legacy_retire (§307 sweep, commit
 * `6c83364` "delete 5 unreachable legacy onboarding files"); verified by a
 * filesystem check returning zero hits for that path. The auto-request
 * lifecycle, toast-on-denial behaviour, and the intentional drop of the
 * decorative AnimatedBackground gradient all stand on their own merits —
 * the rework adapter's `LaunchedEffect(Unit)` + `autoRequested` flag, the
 * `LaunchedEffect(hasPermission, autoRequested.value)` toast surfacer, and
 * the rework `:ui` design language's flat-Scaffold preference are documented
 * inline above and independent of which legacy file originally implemented
 * the parity precedent. Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citations are historical record
 * of the design lineage; the route adapter continues to bridge onboarding
 * step 2 correctly through the legacy retire.
 */
@Composable
fun ThemeSelectionScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: ThemeViewModel = koinViewModel()
    val toastShower: ToastShower = koinInject()
    val permissionRequester = rememberNotificationPermissionRequester()
    val hasPermission by permissionRequester.hasPermission.collectAsState()
    val autoRequested = remember { mutableStateOf(false) }

    val deniedMessage = stringResource(Res.string.you_need_to_enable_notifications)
    // Show the "enable notifications" toast ONLY on an ACTUAL denial reported by the request
    // result — never inferred from `hasPermission` state, which can't tell "request in flight"
    // (false) from "denied" (also false) and would fire the toast the moment the system dialog
    // opens, even for a user who then grants.
    val onPermissionResult: (Boolean) -> Unit = { granted ->
        if (!granted) toastShower.showLong(deniedMessage)
    }

    LaunchedEffect(Unit) {
        if (!autoRequested.value && !hasPermission) {
            autoRequested.value = true
            permissionRequester.request(onPermissionResult)
        }
    }

    ThemeScreen(
        viewModel = viewModel,
        onContinue = { navController.safeNavigate(Screen.Sources) },
        hasNotificationPermission = hasPermission,
        onRequestNotificationPermission = { permissionRequester.request(onPermissionResult) },
    )
}
