package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.core.result.AppResult
import me.manga.kira.di.ComplaintBackendEntrypoint
import me.manga.kira.di.ComplaintBackendHostOwner
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.platform.intent.IntentLauncher
import me.manga.kira.presentation.sources.SourcesViewModel
import me.manga.kira.ui.complaint.ComplaintUnavailableDialog
import me.manga.kira.ui.sources.SourcesScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the [Screen.RepoSettings] nav entry — **Phase 7.x.reposettings.swap**.
 *
 * **What changed in this slice**: this adapter no longer renders the legacy
 * `:composeApp/.../features/repo_settings/ui/screens/RepoSettingsScreen.kt` composable backed by
 * the legacy `RepoSettingsViewModel`. It now renders the architecture-rework
 * [me.manga.kira.ui.sources.SourcesScreen] backed by
 * [me.manga.kira.presentation.sources.SourcesViewModel] (Koin-bound via
 * `sourcesReworkModule` — see [me.manga.kira.di.sourcesReworkModule]). The `Screen.RepoSettings`
 * route entry still carries the legacy `isFirstOpen` flag (`data class RepoSettings(val isFirstOpen:
 * Boolean = false)`), but the only live caller now reaches it as the in-settings entry:
 *  - `HomeReworkScreenRoute` (in-settings entry from Home) — `safeNavigate(Screen.RepoSettings(false))`.
 *    The rework Sources screen renders with a top-bar back arrow ([onBack]) and no onboarding Finish
 *    button.
 *
 * Onboarding no longer routes through this adapter: the 4→3-step native-parity change moved the
 * wizard's Finish step into `SourcesScreenRoute`, which flips `first_launch` and navigates straight
 * to [Screen.Library]. The former `isFirstOpen = true` arm here therefore had no producer and was
 * removed (along with the `SharedPrefsHelper` injection it required).
 *
 * **Pre-conditions met by Phase 7.x.sources.*** slices:
 *  - **§120 (sources.complaint)** — Request-adding-source dialog now lives on the rework Sources
 *    screen.
 *  - **§121 (sources.complaint.infocard)** — "Upcoming Languages" info card now lives on the
 *    rework Sources screen.
 *  - **§122 (sources.onboardingfinish)** — onboarding Finish button now lives on the rework
 *    Sources screen, surfaced via SourcesScreen's `onFinish` callback parameter (wired by
 *    `SourcesScreenRoute`, not this in-settings adapter).
 *
 * All three legacy `RepoSettingsScreen.kt` affordances are now ported. The legacy composable file
 * itself (`composeApp/.../features/repo_settings/ui/screens/RepoSettingsScreen.kt`) is no longer
 * user-reachable through this adapter, but stays on disk until **Phase 9.x route-swap** retires
 * the legacy composable plus its supporting files (`RepoToggleItem`, `LanguageToggle`,
 * `RepoSettingsViewModel`, etc.) in a coordinated deletion sweep across all retired legacy screens.
 *
 * **Back-press**: the in-settings entry passes `onBack = { navController.safePopBackStack() }` so the
 * rework [me.manga.kira.ui.sources.SourcesScreen] renders a top-bar back arrow returning the user
 * to Home. [me.manga.kira.navigation.safePopBackStack] pops the previous entry, falling back to
 * Library if the back stack is unexpectedly empty.
 *
 * **Why this lives in `:composeApp` and not in `:ui`**: same posture as
 * [SourcesReworkScreenRoute] / [LibraryReworkScreenRoute] / [SettingsReworkScreenRoute] —
 * `:ui` deliberately depends on `:presentation` (which knows the VM) but NOT on
 * `androidx.navigation` (which is `:composeApp`-level wiring). The nav decisions belong here,
 * not in the screen.
 *
 * **Koin lifecycles** — note that the [SourcesViewModel] resolved here via [koinViewModel] and
 * the one resolved by [SourcesReworkScreenRoute] are scoped to their respective NavBackStackEntry
 * (the `ViewModelStoreOwner` integration provided by `androidx.lifecycle.viewmodel.compose`). So
 * even though both routes ultimately render the same screen and rely on the same Koin binding,
 * each route's VM instance is independent. The underlying repository ([SourcesRepository] —
 * `single`-scoped via [me.manga.kira.di.sourcesReworkModule]) is shared across both, so the
 * persisted state (which sources are enabled, which languages are toggled on) is identical and
 * any change made on either route surfaces on the other through the upstream `allSources` flow
 * re-emit.
 *
 * @param navController parent nav controller — used by [onBack] to pop back to Home.
 * @param backStackEntry passed through for parity with sibling route-adapter signatures (unused —
 *                      the in-settings entry no longer reads any route argument).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster5.staleKdocSweep.cascade,
 * Task #460, 2026-05-28): two stale citations into the §353-retired
 * legacy `composeApp/.../features/repo_settings/ui/screens/
 * RepoSettingsScreen.kt` appear above:
 *  - Line 18 ("What changed in this slice" opener): "this adapter no
 *    longer renders the legacy
 *    `:composeApp/.../features/repo_settings/ui/screens/
 *    RepoSettingsScreen.kt` composable backed by the legacy
 *    `RepoSettingsViewModel`".
 *  - Line 42 (legacy file disposition forecast): "The legacy composable
 *    file itself
 *    (`composeApp/.../features/repo_settings/ui/screens/
 *    RepoSettingsScreen.kt`) is no longer user-reachable through this
 *    adapter, but stays on disk until **Phase 9.x route-swap** retires
 *    the legacy composable plus its supporting files (`RepoToggleItem`,
 *    `LanguageToggle`, `RepoSettingsViewModel`, etc.) in a coordinated
 *    deletion sweep across all retired legacy screens".
 * The legacy
 * `composeApp/.../features/repo_settings/ui/screens/RepoSettingsScreen.kt`
 * was retired in Phase 9.x.reposettings.legacyui.retire (§353 sweep,
 * commit `37f21da`); verified by a filesystem check returning zero hits
 * for that path. The line 42 forecast was a fulfilled prediction — the
 * "Phase 9.x route-swap" coordinated deletion materialised as
 * anticipated (and the named supporting files
 * `RepoToggleItem`/`LanguageToggle` retired alongside via §356 sweep —
 * see [SourcesScreenRoute] postscript for the parallel onboarding-
 * sources retire trail). The swap-shape-preserved + back-press-delta
 * rationales all stand on their own merits — the rework Sources screen
 * serves both onboarding-step-4 and in-settings entries via this
 * adapter's `onFinish` gate, documented inline above and via the
 * rework SourcesScreen KDoc, independent of which legacy file
 * originally implemented the equivalent flows. Original §253-era prose
 * preserved verbatim per the audit-trail-preservation convention — the
 * citations are historical record of the design lineage; the rework
 * RepoSettingsScreenRoute continues to surface the documented
 * affordances through the legacy retire.
 */
@Composable
fun RepoSettingsScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
    complaintHost: ComplaintBackendHostOwner = koinInject(),
) {
    val viewModel: SourcesViewModel = koinViewModel()
    val launcher: IntentLauncher = koinInject()
    val selection by complaintHost.selection.collectAsState()
    var unavailable by remember(complaintHost) { mutableStateOf(false) }
    val refuse: (String) -> Unit = remember(complaintHost) { { unavailable = true } }
    when (val candidate = complaintHost.candidate(ComplaintBackendEntrypoint.REPOSITORY_SETTINGS, selection)) {
        is AppResult.Success -> ComplaintBackendSourcesRequestRoute(
            candidate = candidate.value,
            viewModel = viewModel,
            onImportFromStorage = { navController.safeNavigate(Screen.BackupRework()) },
            onBack = { navController.safePopBackStack() },
            onOpenUrl = { launcher.openUrl(it) },
        )
        is AppResult.Failure -> SourcesScreen(
            viewModel = viewModel,
            onImportFromStorage = { navController.safeNavigate(Screen.BackupRework()) },
            onBack = { navController.safePopBackStack() },
            onOpenUrl = { launcher.openUrl(it) },
            onRequestSource = refuse,
        )
    }
    if (unavailable) ComplaintUnavailableDialog(onBack = { unavailable = false })
}
