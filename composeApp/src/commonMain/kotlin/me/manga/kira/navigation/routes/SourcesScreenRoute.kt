package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.intl.Locale
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.core.storage.StorageKeys
import me.manga.kira.navigation.Screen
import me.manga.kira.platform.intent.IntentLauncher
import me.manga.kira.presentation.sources.SourcesViewModel
import me.manga.kira.ui.sources.SourcesScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the [Screen.Sources] nav entry — **Phase 7.x.sources.swap** (Task #305).
 *
 * **What changed in this slice**: this adapter no longer renders the legacy
 * `:composeApp/.../presentation/features/onboarding/sources/SourcesScreen.kt` composable backed
 * by the legacy [me.manga.kira.presentation.features.repo_settings.ui.viewmodel.
 * RepoSettingsViewModel]. It now renders the architecture-rework
 * [me.manga.kira.ui.sources.SourcesScreen] backed by
 * [me.manga.kira.presentation.sources.SourcesViewModel] (Koin-bound via
 * `sourcesReworkModule` — see [me.manga.kira.di.sourcesReworkModule]).
 *
 * **Pre-conditions met by the four prior `Phase 7.x.sources.*` gap-lifts**:
 *  - **§121 (sources.complaint)** — Request-Source dialog lives on the rework screen.
 *  - **§122 (sources.onboardingfinish)** — onboarding Finish button is surfaced via the
 *    [me.manga.kira.ui.sources.SourcesScreen.onFinish] callback (renders iff non-null).
 *  - **§123 (sources.infocard)** — "Upcoming Languages" info card is always rendered.
 *  - **§139 (sources.onboardingseed)** — default-language auto-seed is dispatched via the
 *    [me.manga.kira.ui.sources.SourcesScreen.onboardingLanguageTag] param: a
 *    `LaunchedEffect(onboardingLanguageTag)` fires
 *    [me.manga.kira.presentation.sources.SourcesIntent.OnSeedDefaultLanguage] on every
 *    non-null tag change, mirroring legacy `SourcesScreen.kt:124-127`'s
 *    `LaunchedEffect(userLanguageCode)` posture verbatim.
 *
 * All four legacy onboarding `SourcesScreen.kt` affordances are now ported, so this swap is
 * shape-preserving from the user's perspective: the same Request-Source dialog, the same
 * Languages-coming-soon info card, the same per-language + per-source toggles, the same
 * auto-seed semantics, and the same Finish-button → `Screen.RepoSettings(isFirstOpen = true)`
 * advance step.
 *
 * **Onboarding chain preserved verbatim**: Welcome → Theme (rework via §138) → **Sources
 * (rework via this slice)** → `Screen.RepoSettings(isFirstOpen = true)` (rework via §124,
 * with Finish → Library + `first_launch = false` flip + back-stack clear) → Library. The
 * 4-step structure is preserved deliberately — collapsing to 3 steps (advancing directly to
 * Library here) is a structural onboarding-chain change beyond the scope of a route-swap
 * slice. The step-3 / step-4 redundancy (both render the rework Sources screen with a
 * Finish button) is a pre-existing condition that §124's KDoc explicitly anticipated; a
 * future `Phase 9.x.onboarding.cleanup` slice can collapse if desired.
 *
 * **Counterpart to [RepoSettingsScreenRoute] (§124)**: the §124 swap rewrote the
 * `Screen.RepoSettings` adapter to render the rework Sources screen for both the in-settings
 * entry (`isFirstOpen = false`, no Finish button) and the onboarding-step-4 entry
 * (`isFirstOpen = true`, Finish → Library). This adapter (the `Screen.Sources` route, the
 * onboarding-step-3 entry) is the symmetric swap. The two routes share the same
 * `SourcesRepository` singleton (`single`-scoped via `sourcesReworkModule`), so any toggle
 * made on step 3 surfaces on step 4 via the upstream `allSources` flow re-emit.
 *
 * **Koin lifecycle**: the [SourcesViewModel] resolved here via [koinViewModel] is scoped to
 * this route's `NavBackStackEntry` (the `ViewModelStoreOwner` integration provided by
 * `androidx.lifecycle.viewmodel.compose`). The step-4 route ([RepoSettingsScreenRoute])
 * resolves a DIFFERENT VM instance scoped to its own back-stack entry — each route's VM is
 * independent. The underlying [me.manga.kira.domain.repository.SourcesRepository] (and
 * its `:data` impl over the legacy `:shared` `SourcesRepository`) is shared `single` across
 * both routes, so the persisted state is identical and changes flow through the upstream
 * re-emission.
 *
 * **`onboardingLanguageTag` source**: [DataStoreHelper.languageFlow] is the persisted
 * user-selected language code (set by the Welcome screen's language picker, defaulting to
 * `""` when never set). The rework Sources screen applies the `.ifBlank { "en" }` fallback
 * internally via the §139 use case, so the snapshot here is the raw flow value without an
 * extra map. `initial = ""` matches the flow's default emission, so the
 * `LaunchedEffect(onboardingLanguageTag)` fires immediately on first composition (matching
 * the legacy `LaunchedEffect(userLanguageCode)` first-fire timing).
 *
 * **`onFinish` semantics preserved verbatim**: same nav target as the pre-swap adapter
 * (`safeNavigate(Screen.RepoSettings(isFirstOpen = true))`). The legacy comment block on
 * the pre-swap adapter (legacy `SourcesScreenRoute.kt:28-32`) explained the KMP-port
 * decision to centralise the `first_launch = false` write inside [RepoSettingsScreenRoute.
 * onFinish] rather than here — that decision stays in place. The Finish button on this
 * screen advances to step 4; the Finish button on step 4 (via §124) flips the pref and
 * navigates to Library.
 *
 * **Why this lives in `:composeApp` and not in `:ui`**: same posture as
 * [RepoSettingsScreenRoute] / [SourcesReworkScreenRoute] / [LibraryReworkScreenRoute] /
 * [SettingsReworkScreenRoute] — `:ui` deliberately depends on `:presentation` (which knows
 * the VM) but NOT on `androidx.navigation` (which is `:composeApp`-level wiring). The
 * onboarding-chain nav target choice (advance to `Screen.RepoSettings(isFirstOpen = true)`)
 * belongs here, not in the screen.
 *
 * **Banned features**: No `!!`, `Any`, `lateinit`, `Thread` in this file. `collectAsState`
 * with a non-null initial is Compose-canonical. `koinInject` / `koinViewModel` are
 * Koin-Compose-canonical.
 *
 * **No load-bearing fix touched**: This file does NOT touch the Coil ImageLoader, the
 * Reader's per-request listener, the Reader's decoder hints, the OkHttp interceptor, or
 * any of the prior load-bearing image-quality posture (Sources has no images).
 *
 * **Legacy file disposition post-swap**:
 *  - `presentation/features/onboarding/sources/SourcesScreen.kt` (legacy composable) — no
 *    longer user-reachable through this adapter. Stays on disk until a future `Phase 9.x`
 *    cleanup sweep retires it alongside other retired legacy screens.
 *  - [me.manga.kira.presentation.features.repo_settings.ui.viewmodel.RepoSettingsViewModel]
 *    — still bound by `SharedModule`. The legacy onboarding adapter was the last user-
 *    reachable consumer of [me.manga.kira.presentation.features.repo_settings.ui.
 *    viewmodel.RepoSettingsViewModel.setLanguageEnabledDefault] specifically; other methods
 *    on the legacy VM are still referenced by other call sites. Cleanup is a Phase 9.x
 *    concern.
 *
 * @param navController parent nav controller — used to navigate to the next onboarding step
 *                      on Finish.
 * @param backStackEntry passed through for parity with sibling route-adapter signatures
 *                      (unused — the rework [SourcesViewModel] is `koinViewModel()`-scoped
 *                      via Koin's ViewModelStoreOwner integration, so we don't consult
 *                      `backStackEntry` for VM scoping here).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster5.staleKdocSweep.cascade,
 * Task #460, 2026-05-28): three stale citations into the §307-retired
 * legacy `composeApp/.../presentation/features/onboarding/sources/
 * SourcesScreen.kt` appear above:
 *  - Line 36 (§139 onboardingseed gap-lift bullet): "mirroring legacy
 *    `SourcesScreen.kt:124-127`'s `LaunchedEffect(userLanguageCode)`
 *    posture verbatim".
 *  - Line 39 (shape-preservation summary): "All four legacy onboarding
 *    `SourcesScreen.kt` affordances are now ported".
 *  - Line 103 ("Legacy file disposition" forecast):
 *    "`presentation/features/onboarding/sources/SourcesScreen.kt`
 *    (legacy composable) — no longer user-reachable through this
 *    adapter. Stays on disk until a future `Phase 9.x` cleanup sweep
 *    retires it alongside other retired legacy screens".
 * The legacy
 * `composeApp/.../presentation/features/onboarding/sources/SourcesScreen.kt`
 * was retired in Phase 9.x.onboarding.legacy_retire (§307 sweep, commit
 * `6c83364` "delete 5 unreachable legacy onboarding files"); verified
 * by a filesystem check returning zero hits for that path. The line 103
 * forecast was a fulfilled prediction — the "future Phase 9.x cleanup
 * sweep" that would retire the legacy onboarding SourcesScreen
 * materialised exactly as anticipated. The four affordance-port
 * rationales (Request-Source dialog, Languages info card, Finish
 * button, default-language auto-seed) all stand on their own merits —
 * the rework Sources screen's affordances are documented inline above
 * and via the §§120-123 + §139 gap-lift KDocs, independent of which
 * legacy file originally implemented the equivalents. The line 81
 * citation into `SourcesScreenRoute.kt:28-32` is SELF-historical (this
 * file's own pre-swap form, not a retired sibling file), preserved as
 * historical record of the KMP-port nav-target centralisation decision.
 * Original §253-era prose preserved verbatim per the audit-trail-
 * preservation convention — the citations are historical record of the
 * design lineage; the rework SourcesScreen continues to surface the
 * documented affordances through the legacy retire.
 */
@Composable
fun SourcesScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val ds: DataStoreHelper = koinInject()
    val prefs: SharedPrefsHelper = koinInject()
    val viewModel: SourcesViewModel = koinViewModel()
    val launcher: IntentLauncher = koinInject()
    val userLanguageCode by ds.languageFlow.collectAsState(initial = "")
    // NP onboarding parity (#r6-sf-1): native seeds the default-enabled sources from the DEVICE
    // locale (SourcesScreen.kt:71-78 `configuration.locales[0].language`), not the in-app language
    // pref. On a fresh install nothing has written SELECTED_LANGUAGE, so `userLanguageCode` is always
    // "" and the screen's `.ifBlank { "en" }` fallback would enable only English sources regardless of
    // device locale. Fall back to the platform locale's language code when the user has not explicitly
    // chosen an app language — same posture as LanguageScreen.kt:229 (`Locale.current.language`).
    val onboardingLanguageTag = userLanguageCode.ifBlank { Locale.current.language }

    SourcesScreen(
        viewModel = viewModel,
        onboardingLanguageTag = onboardingLanguageTag,
        // Request-Source dialog social-media row forwards each brand URL to the platform
        // IntentLauncher (fire-and-forget; same posture as SettingsReworkScreenRoute's onOpenUrl).
        onOpenUrl = { url -> launcher.openUrl(url) },
        // NP onboarding parity (4→3 steps): native onboarding is Welcome → Theme → Sources →
        // Library (native SourcesScreenRoute.onFinish flips `first_launch = false` then navigates
        // straight to Screen.Library, clearing the wizard back stack). KMP previously inserted a
        // duplicate fourth step here by advancing to Screen.RepoSettings(isFirstOpen = true) — a
        // second render of this same rework Sources screen with a Finish button. This Finish now
        // mirrors native exactly: flip the `first_launch` flag false (same pref key + helper as
        // App.kt's start-destination read and the in-settings RepoSettingsScreenRoute.onFinish) and
        // navigate directly to Library with popUpTo(start destination){inclusive} + launchSingleTop
        // so system-back from Library does not return to the wizard. Screen.RepoSettings stays the
        // in-settings entry only (HomeReworkScreenRoute → Screen.RepoSettings(false)).
        onFinish = {
            prefs.putBoolean(StorageKeys.FIRST_LAUNCH, false)
            navController.navigate(Screen.Library) {
                popUpTo(navController.graph.startDestinationId) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        },
    )
}
