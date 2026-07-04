package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.locale.LocalAppLocale
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.platform.intent.IntentLauncher
import me.manga.kira.presentation.language.LanguageViewModel
import me.manga.kira.ui.language.LanguageScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the architecture-rework Language picker screen (Phase 7.x.language).
 *
 * Adapter between the NavHost (Nav 2.9.2 type-safe `composable<Screen.LanguageRework>`) and the
 * `:ui/.../language/LanguageScreen` composable. Owns the rework [LanguageViewModel] via Koin.
 *
 * **One nav callback (back)**: the rework language picker is a terminal display-and-select
 * screen — 11 language rows with a tap-to-select toggle and no outbound links. The only nav
 * wiring is the top-bar back affordance (GAP-LANG-01): [navController] backs the screen's
 * `onBack` via `safePopBackStack`; [backStackEntry] is accepted for parity with sibling
 * route-adapter signatures but not consulted.
 *
 * **Coexists with legacy [LanguageScreenRoute]**: both routes consume the SAME upstream
 * DataStore-backed wire (`SettingsRepository.languageFlow` + `setLanguage(code)` via the
 * `:data` `LanguageRepositoryImpl` strangler-fig). Selecting a language in EITHER route writes
 * the same IETF tag and triggers the same `core.locale.applyApplicationLocale(tag)` side effect;
 * the OTHER screen reflects the change reactively via the upstream flow re-emit. The two routes
 * are visually independent (rework uses `:ui` design tokens + inline endonyms from the `:data`
 * impl's `SUPPORTED_LANGUAGES` list — the canonical language list; the legacy
 * `Res.array.supported_languages` array and `LANGUAGE_DISPLAY_NAMES` map are retired) but
 * functionally interchangeable. Phase 9.x route-swap collapses to
 * the rework path; until then both stay reachable.
 *
 * **Request-Language entry omitted**: the legacy screen also hosts a FeedbackDialog-driven
 * "Request a Language" surface (the `ComplaintViewModel` + `LocalSnackbarHostState` cross-cutting
 * dependency). The rework slice defers that to a follow-on `Phase 7.x.language.request`
 * sub-slice — see [me.manga.kira.presentation.language.LanguageEffect] KDoc and
 * `PLAN_language.md` §"Deferrals". This route adapter is consequently the thinnest possible:
 * VM resolution + screen call, no Snackbar host injection, no ComplaintViewModel resolution, no
 * dialog state hoisting.
 *
 * **Scope**: this is the entry point that proves the Language slice end-to-end —
 *  1. **Koin DI** ([me.manga.kira.di.languageReworkModule]) resolves the [LanguageViewModel]
 *     constructor's three use cases
 *     ([me.manga.kira.domain.usecase.language.GetSupportedLanguagesUseCase],
 *     [me.manga.kira.domain.usecase.language.ObserveSelectedLanguageUseCase],
 *     [me.manga.kira.domain.usecase.language.SetLanguageUseCase]) and the `single`-scoped
 *     [me.manga.kira.domain.repository.LanguageRepository] they depend on (which
 *     strangler-fig delegates to the legacy `:shared`
 *     [me.manga.kira.presentation.features.settings.domain.SettingsRepository]'s
 *     `languageFlow` + `setLanguage` surface).
 *  2. **`:presentation` MVI** plumbing emits
 *     [me.manga.kira.presentation.language.LanguageState] via `StateFlow`. No `Channel`
 *     emissions because [me.manga.kira.presentation.language.LanguageEffect] is an empty
 *     sealed interface today.
 *  3. **`:ui` Compose** renders a Material 3 `LazyColumn` of 11 language rows with a trailing
 *     "✓" Text glyph on the selected row (no Material `Icon` — the `:ui` module has no
 *     `material-icons-*` artifact; same Text-glyph posture as `ReaderScreen`'s "⋯" overflow).
 *
 * **Why this lives in `:composeApp` and not in `:ui`**: same posture as
 * [ThemeReworkScreenRoute] / [SourcesReworkScreenRoute] / [StatisticsReworkScreenRoute] /
 * [LibraryReworkScreenRoute] — `:ui` deliberately depends on `:presentation` (which knows the
 * VM) but NOT on `androidx.navigation` (which is `:composeApp`-level wiring). The picker has no
 * outbound links so this adapter is the thinnest possible — but it stays in `:composeApp` to
 * keep the layer boundary uniform: every screen has a route adapter at this layer.
 *
 * @param navController parent nav controller — backs the screen's `onBack` via
 *                      [me.manga.kira.navigation.safePopBackStack] (no other outbound nav).
 * @param backStackEntry passed through for parity with sibling route adapters (unused — the VM
 *                      is `koinViewModel()`-scoped via Koin's ViewModelStoreOwner integration,
 *                      so we don't consult `backStackEntry` for VM scoping here).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster18.staleKdocSweep.cascade,
 * Task #474, 2026-05-28): two categories of fulfilled-prediction +
 * inversion citations appear above:
 *  - Lines 23-31 ("Coexists with legacy [LanguageScreenRoute] ... Phase
 *    9.x route-swap collapses to the rework path; until then both stay
 *    reachable"). FULFILLED-PREDICTION — Phase 7.x.language.swap (§292)
 *    re-pointed `Screen.LanguageScreen`'s rendering adapter to the
 *    rework `LanguageScreen` already. The `LanguageScreenRoute.kt`
 *    adapter file STILL EXISTS but now renders the rework VM + screen
 *    (verified at LanguageScreenRoute.kt:11 — its own KDoc declares
 *    `Phase 7.x.language.swap`); both `Screen.LanguageScreen` and
 *    `Screen.LanguageRework` now converge on the same rework path
 *    through `NavBackStackEntry`-scoped Koin instances. The "Phase 9.x
 *    route-swap" forecast happened earlier as a §292 7.x-prefixed swap;
 *    the predicted collapse is materialised — both stay reachable AS
 *    PREDICTED, both rendering the same UI AS PREDICTED. Functional
 *    interchangeability with shared upstream wire stands as written.
 *  - Lines 33-39 ("Request-Language entry omitted ... defers that to a
 *    follow-on `Phase 7.x.language.request` sub-slice"). FACTUALLY
 *    INVERTED — Phase 7.x.language.request (§250, Task #250) shipped
 *    the Request-a-Language surface as a Material 3 `AlertDialog` with
 *    a multiline `OutlinedTextField` (8-char minimum + error helper text
 *    below threshold + Send/Cancel buttons + submit dispatching
 *    `LanguageIntent.OnSubmitRequest` → use case → Firestore write
 *    through the same legacy `:shared` complaint pipeline). The route
 *    adapter is no longer "the thinnest possible" — but the omitted-
 *    Snackbar-host-injection + omitted-ComplaintViewModel-resolution +
 *    omitted-dialog-state-hoisting framing still holds because the
 *    rework `LanguageScreen` internalises all three (own
 *    `SnackbarHostState` + own dialog state + use-case-routed VM call
 *    instead of cross-cutting `ComplaintViewModel` resolution). Mirror
 *    of §445 + §470 + §471 + §472 + §473 fulfilled-deferral-inversion
 *    precedent.
 * The Koin DI graph + MVI plumbing + Text-glyph-vs-Material-Icon posture
 * + `:composeApp`-vs-`:ui`-boundary + shared upstream DataStore +
 * applyApplicationLocale side-effect all stand on their own merits past
 * the §250 + §292 fulfilled landings. The `LanguageReworkScreenRoute`
 * adapter remains LIVE as the canonical renderer for `Screen.LanguageRework`
 * (joined by the §292-swapped `LanguageScreenRoute` for
 * `Screen.LanguageScreen`, both converging on the rework path through
 * `NavBackStackEntry`-scoped Koin instances). Original §253-era prose
 * preserved verbatim per the audit-trail-preservation convention — the
 * citations are historical record of the design lineage including the
 * deferred-Request-Language forecast that was subsequently fulfilled
 * across §250.
 */
@Composable
fun LanguageReworkScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: LanguageViewModel = koinViewModel()
    val launcher: IntentLauncher = koinInject()
    // GAP-LANG-01 — wire the top-bar back arrow (native TopAppBarCom navigationIcon).
    LanguageScreen(
        viewModel = viewModel,
        onBack = { navController.safePopBackStack() },
        // iOS can't re-resolve resources in-session → show the "restart to apply" hint there;
        // Android/Desktop switch live so the hint stays hidden.
        restartHintVisible = !LocalAppLocale.isLiveLocaleSwitchSupported,
        // Request-language dialog social-media row forwards each brand URL to the platform
        // IntentLauncher (fire-and-forget; same posture as SettingsReworkScreenRoute's onOpenUrl).
        onOpenUrl = { url -> launcher.openUrl(url) },
    )
}
