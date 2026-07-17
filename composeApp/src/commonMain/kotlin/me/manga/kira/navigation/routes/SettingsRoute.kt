package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.admin.Admin
import me.manga.kira.core.platform.backupPlatformName
import me.manga.kira.domain.model.sources.SourceAccessState
import me.manga.kira.domain.usecase.sourceaccess.ObserveSourceAccessUseCase
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.platform.intent.IntentLauncher
import me.manga.kira.presentation.settings.SettingsDestination
import me.manga.kira.presentation.settings.SettingsViewModel
import me.manga.kira.ui.settings.SettingsScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the Settings hub.
 *
 * **Phase 7.x.settings.swap** — this file previously hosted the legacy
 * `:composeApp/.../features/settings/ui/screens/SettingsScreen` backed by the legacy
 * `:shared/.../settings/SettingsViewModel` + `ChaptersViewModel` + `ComplaintViewModel`
 * (commit history pre-swap — see `SettingsRoute.kt` at HEAD `1c5c6c6`'s 22-line trivial
 * pass-through `SettingsScreen(navController)`). The swap rewires the legacy
 * [Screen.Setting] entry to render the architecture-rework
 * `:ui/.../settings/SettingsScreen` backed by the rework
 * `:presentation/.../settings/SettingsViewModel` (Koin-bound via `settingsReworkModule`).
 *
 * Mirrors the established swap shape from Phase 7.x.history.swap (commit `eab80c0`),
 * Phase 7.x.updates.swap (commit `b3dbeac`), and Phase 7.x.about.swap (commit `e9b6a0b`):
 * single-file route adapter rewrite, no `Screen.kt` / `App.kt` / nav-graph changes (the
 * route entry's identity stays [Screen.Setting]).
 *
 * Both the legacy [Screen.Setting] entry (reached from the Home screen's overflow menu) AND
 * the parallel [Screen.SettingsRework] debug route now converge on the same rework screen,
 * each scoped to its own [NavBackStackEntry]-owned `SettingsViewModel`. The single-scoped
 * `SettingsRepositoryImpl` (a strangler-fig over the legacy
 * `:shared/.../settings/SettingsRepository` facade) means the 5 boolean prefs + cache-size
 * walk are the same source of truth across both routes — toggling a switch on EITHER screen
 * flips the same `SharedPreferences` / `DataStore` key; clearing cache on EITHER screen runs
 * the same `clearFilesLargerThan1MB()`.
 *
 * Affordance parity vs the pre-swap legacy adapter:
 *  - **5 General toggles** — `downloaded_only`, `incognito`, `follow_system_theme`,
 *    `dark_mode` (gated on `!follow_system_theme`), `pure_black`. Same fields, same gate,
 *    same upstream pref flow (per Phase 7.x.settings.foundation §96 + Phase 7.x.settings.
 *    themegating §97).
 *  - **Clear-cache row** — same `clearFilesLargerThan1MB()` semantics + cache-size string.
 *    Defence-in-depth on double-tap via `state.isClearingCache` (per §96).
 *  - **Feedback dialog** — same category dropdown + body field + submit-to-Firestore via
 *    `ComplaintRepository`; in-flight gating on `isSubmittingFeedback`; snackbar
 *    success/error feedback (per Phase 7.x.settings.feedback §98).
 *  - **Reading-mode dialog** — same picker structure; single-tap-commits instead of
 *    legacy's two-step Apply/Revert (per established rework Theme picker posture, see
 *    Phase 7.x.settings.readingmode §99). Both routes write the same upstream
 *    `ObservableSettings.putString` key — the in-line toggles on the rework Settings
 *    screen + the Reader's per-chapter reading-mode override all consume the same
 *    `ObserveReadingModeUseCase` flow.
 *  - **Statistics nav row** → [Screen.StatisticsRework] (per Phase 7.x.statistics.swap §124).
 *  - **Language nav row** → [Screen.LanguageRework] (per Phase 7.x.language.swap §127).
 *  - **About nav row** → [Screen.AboutRework] (per Phase 7.x.about.swap §131).
 *  - **Feedback Manager (Complaint) nav row** → [Screen.ComplaintAdminRework] for admins
 *    via [Admin.isAdmin], [Screen.ComplaintRework] otherwise. Same admin gate as the
 *    legacy `SettingsScreen.kt:272-278`'s `if (Admin.isAdmin) ComplaintAdmin else
 *    Complaint` branch.
 *  - **Downloads nav row** → [Screen.DownloadsRework] (per Phase 7.x.downloads.actions
 *    swap, commit `94987b5`).
 *  - **Request feature / bug row** → opens the rework Feedback dialog inline (same flow
 *    as the legacy's `request_feature_bug` row that opens [FeedbackDialog]).
 *  - **Help row** — inert placeholder with `null` onClick; mirrors legacy
 *    `SettingsScreen.kt:350-353` `SettingsNavigationItem(title = Res.string.help)` with no
 *    `onClick` (per Phase 7.x.settings.help, commit `9d5bebd`).
 *
 * Additive affordances (in rework, NOT in pre-swap legacy):
 *  - **Theme nav row** → [Screen.ThemeRework] (rework Theme picker — tri-state +
 *    PureBlack toggle). The legacy reaches theme settings only through the in-line
 *    SwitchItem toggles; the rework keeps those toggles AND adds a dedicated picker entry
 *    point. Pure addition — no legacy affordance lost.
 *  - **What's new nav row** → [Screen.WhatsNewRework]. The legacy reaches What's-new
 *    through the About screen (`AboutScreen.kt`'s "Recent updates" row); the rework keeps
 *    that path (per Phase 7.x.about.whatsnewrow, commit history) AND adds a direct entry
 *    from the Settings hub. Pure addition — no legacy affordance lost.
 *
 * Visual delta vs the pre-swap legacy screen:
 *  - **No app-icon header image** — the legacy renders a 250.dp
 *    `painterResource(Res.drawable.ic_launcher_foreground)` Image at the top of the
 *    LazyColumn (legacy `SettingsScreen.kt:183-190`). The rework omits it; the screen
 *    surface is reserved for sectioned content rows. Purely decorative loss with zero
 *    behavioural consequence — documented as "still deferred — purely decorative" in the
 *    rework SettingsScreen KDoc (§96 lines 84-85). No nav, no tap, no info loss; the
 *    rework's TopAppBar already carries the "Settings" title.
 *  - **TextButton-style rows instead of icon-prefixed SwitchItem / SettingsNavigationItem**
 *    — same icon-free posture as the rework Library / History / Updates / Statistics /
 *    Details / Reader screens (the rework `:ui` module deliberately omits
 *    `compose.materialIconsExtended`). No affordance loss; the row labels carry the same
 *    information without the leading icon.
 *  - **Sectioned layout with section-title headers** — General / Theme / Reading /
 *    Navigation / Storage / Feedback sections rendered as labelled [SectionCard]s. The
 *    legacy uses unlabelled `ItemsGroup` boxes. Same conceptual grouping; the rework adds
 *    visible section headers for clarity.
 *
 * @param navController parent nav controller for forwarding to the 7 rework destinations
 *                      and the (unmodelled) future destinations a new [SettingsDestination]
 *                      variant would add (the exhaustive `when` ensures a future variant is
 *                      a compile-time error in this adapter).
 * @param backStackEntry passed through for parity with sibling route adapters; unused — the
 *                      `SettingsViewModel` is `koinViewModel()`-scoped via Koin's
 *                      `ViewModelStoreOwner` integration.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster5.staleKdocSweep.cascade,
 * Task #460, 2026-05-28): three stale citations into the §354-retired
 * legacy `composeApp/.../features/settings/ui/screens/SettingsScreen.kt`
 * appear above:
 *  - Line 60 (Feedback Manager admin-gate bullet): "Same admin gate as
 *    the legacy `SettingsScreen.kt:272-278`'s `if (Admin.isAdmin)
 *    ComplaintAdmin else Complaint` branch".
 *  - Line 67 (Help row bullet): "mirrors legacy `SettingsScreen.kt:350-
 *    353` `SettingsNavigationItem(title = Res.string.help)` with no
 *    `onClick`".
 *  - Line 83 (Visual delta no-app-icon-header bullet): "the legacy
 *    renders a 250.dp `painterResource(Res.drawable.ic_launcher_
 *    foreground)` Image at the top of the LazyColumn (legacy
 *    `SettingsScreen.kt:183-190`)".
 * The legacy
 * `composeApp/.../features/settings/ui/screens/SettingsScreen.kt` was
 * retired in Phase 9.x.settings.legacy_retire (§354 sweep, commit
 * `5cc42d2` "(1/2): delete 5 orphan settings UI files"); verified by a
 * filesystem check returning zero hits for that path. The three
 * affordance-parity rationales (admin gate, inert Help row, omitted
 * app-icon header) all stand on their own merits — the rework Settings
 * screen's admin branch, Help row posture, and sectioned-layout choice
 * are documented inline in the prose above and via the rework
 * SettingsScreen KDoc (§96), independent of which legacy file
 * originally implemented them. The three line anchors are historical
 * record of the pre-retire affordance survey that drove the rework's
 * parity decisions. Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citations are historical
 * record of the design lineage; the rework SettingsScreen continues to
 * surface the documented affordances through the legacy retire.
 */
@Composable
fun SettingsRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: SettingsViewModel = koinViewModel()
    val launcher: IntentLauncher = koinInject()
    val observeSourceAccess: ObserveSourceAccessUseCase = koinInject()
    val sourceAccessState by observeSourceAccess().collectAsState()
    SettingsScreen(
        viewModel = viewModel,
        sourceAccessActivated = sourceAccessState == SourceAccessState.ACTIVATED,
        // The "compress during Low Power Mode" toggle is an iOS-only concern (iOS Low Power Mode + the
        // iOS background finalize engine); show it only there. `backupPlatformName()` is the app's
        // canonical per-target platform-name seam (also used by BackupReworkModule).
        lowPowerCompressionToggleVisible = backupPlatformName() == "ios",
        onNavigate = { destination ->
            val target: Screen = when (destination) {
                SettingsDestination.SOURCE_MANAGEMENT ->
                    sourceManagementDestination(sourceAccessState)
                SettingsDestination.THEME -> Screen.ThemeRework
                SettingsDestination.STATISTICS -> Screen.StatisticsRework
                SettingsDestination.LANGUAGE -> Screen.LanguageRework
                SettingsDestination.ABOUT -> Screen.AboutRework
                SettingsDestination.COMPLAINT ->
                    if (Admin.isAdmin) Screen.ComplaintAdminRework else Screen.ComplaintRework
                SettingsDestination.WHATSNEW -> Screen.WhatsNewRework
                SettingsDestination.DOWNLOADS -> Screen.DownloadsRework
                // Full-library mode: empty scopeJson (the scoped variants navigate from the
                // Details / Library export actions, not from here).
                SettingsDestination.BACKUP -> Screen.BackupRework()
            }
            navController.safeNavigate(target)
        },
        // GAP-SET-12 parity (#5) — wire the platform IntentLauncher so the Feedback dialog's social
        // links open externally. (The admin "Testing Mode" toggle was removed per owner request.)
        onOpenUrl = { url -> launcher.openUrl(url) },
    )
}

internal fun sourceManagementDestination(sourceAccessState: SourceAccessState): Screen =
    if (sourceAccessState == SourceAccessState.ACTIVATED) {
        Screen.RepoSettings(false)
    } else {
        Screen.StartReading(onboarding = false)
    }
