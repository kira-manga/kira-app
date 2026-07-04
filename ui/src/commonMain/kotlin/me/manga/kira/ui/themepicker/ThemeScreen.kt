package me.manga.kira.ui.themepicker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.kira.domain.model.theme.AppTheme
import me.manga.kira.presentation.theme.ThemeIntent
import me.manga.kira.presentation.theme.ThemeState
import me.manga.kira.presentation.theme.ThemeViewModel
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.choose_your_theme
import me.manga.kira.ui.generated.resources.continue_string
import me.manga.kira.ui.generated.resources.enable_notifications
import me.manga.kira.ui.generated.resources.grant_permission
import me.manga.kira.ui.generated.resources.notification_permission
import me.manga.kira.ui.generated.resources.pure_black_mode_title
import me.manga.kira.ui.generated.resources.theme_dark
import me.manga.kira.ui.generated.resources.theme_light
import me.manga.kira.ui.generated.resources.theme_screen_title
import me.manga.kira.ui.generated.resources.theme_system
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Theme picker — Compose entry point for the Theme MVI slice.
 *
 * Phase 7.x.theme rework. Renders [ThemeState] (the currently-selected [AppTheme]) and
 * dispatches [ThemeIntent.OnSelectTheme] when the user taps a tab. No effect collection — the
 * slice's [me.manga.kira.presentation.theme.ThemeEffect] is an empty sealed interface (see
 * its KDoc); the screen has no outbound navigation links.
 *
 * **Visual parity vs the legacy `composeApp/.../onboarding/theme_selection/
 * ThemeSelectionScreen.kt` + `ThemeSelector.kt`** + the legacy `SettingsScreen` PureBlack row:
 *  - **Picker shape preserved**: a Material 3 `TabRow` with three tabs (Light / Dark / System).
 *    Text-only labels — no Material icons, matching the established rework `:ui` posture
 *    (`HistoryScreen` / `StatisticsScreen` also omit `material-icons-extended`, which is not on
 *    the `:ui` module's classpath). The legacy onboarding picker shows icons; this surface
 *    drops them for classpath consistency, accepting a minor visual divergence as part of the
 *    rework's design-token discipline.
 *  - **PureBlack/OLED switch added** (Phase 7.x.theme.pureblack): a Material 3 `Switch` in a
 *    `Row` under the TabRow with the label "Pure black dark mode" (the actual
 *    `pure_black_mode_title` resource value, matching native). Orthogonal to the
 *    theme tri-state — toggling the switch flips `KEY_PURE_BLACK` on the legacy
 *    `SharedPreferences` and the legacy `SettingsScreen`'s PureBlack toggle reflects the same
 *    value (single source of truth). When the active theme resolves to Light, the toggle has
 *    no visual effect on the rendered scheme but the value persists for the next dark
 *    resolution — matches the legacy `SettingsScreen` behaviour. The switch is always
 *    interactive (no enabled-state gate on the theme tri-state); a user can pre-set their
 *    PureBlack preference while in Light mode.
 *  - **Onboarding-flavour UI partially restored** (Phase 7.x.theme.onboardingcontinue +
 *    Phase 7.x.theme.onboardingpermission gap-lifts): three optional parameters expose
 *    onboarding-flavour affordances without forcing them on every caller:
 *     - `onContinue: (() -> Unit)?` — when non-null, renders a "Continue" button at the
 *       bottom of the picker column. (Phase 7.x.theme.onboardingcontinue.)
 *     - `hasNotificationPermission: Boolean = true` — current notification permission state
 *       input. Default `true` means "no permission gating".
 *     - `onRequestNotificationPermission: (() -> Unit)?` — when non-null, renders an
 *       "Enable Notifications" grant row (header + descriptive copy + Grant Permission
 *       button) inside the picker card, below the TabRow. Native parity (P2 picker fix):
 *       the section is shown UNCONDITIONALLY whenever the onboarding caller is active,
 *       regardless of whether permission is already granted — matching native's
 *       `ThemeSelector`, which always renders the notification section. Tapping the grant
 *       button dispatches the callback (used by the future Phase 7.x.theme.swap to re-launch
 *       the platform permission requester). When this callback is non-null, the Continue
 *       button is also gated on `enabled = hasNotificationPermission` so the user can't
 *       advance through the onboarding wizard without granting notifications.
 *       (Phase 7.x.theme.onboardingpermission.)
 *    All three parameters default to nothing/`true`/null, so existing callers
 *    (`Screen.ThemeRework`) get the standalone-picker behaviour bit-for-bit unchanged —
 *    no Continue button, no grant row, no gating. Future Phase 7.x.theme.swap (Task #291)
 *    is the first caller to opt into all three. Mirrors the established §122 sources.
 *    onboardingfinish nullable-callback-IS-the-gate pattern. The `AnimatedBackground`
 *    gradient overlay + the auto-request lifecycle (`LaunchedEffect`-fired permission
 *    request + toast-on-denial) from the legacy `ThemeSelectionScreen` are still deferred
 *    — the route adapter (not this screen) is the right owner for both of those (the
 *    overlay is purely cosmetic, the auto-request is a side-effect of mounting the
 *    onboarding step, not a property of the picker UI).
 *  - **Bottom-bar visible**: same posture as Sources / History / Updates / Statistics — the
 *    Scaffold has no special bottom-bar suppression.
 *  - Labels are inline literal strings (no `Res.string.*` lookups). Phase 10 i18n lift swaps
 *    both legacy and rework consumers in one pass.
 *  - Design tokens use [LocalSpacing] + Material 3 directly; legacy used ad-hoc `.dp` literals.
 *
 * **TabRow semantics**:
 *  - `selectedTabIndex` derived from the current [AppTheme] via [AppTheme.indexInPicker]. Order
 *    is Light(0) → Dark(1) → System(2) — keeps the legacy's left-to-right ordering for
 *    user-familiarity. Tabs use `Tab` (NOT `LeadingIconTab`) with text-only composables.
 *  - `onClick` dispatches `ThemeIntent.OnSelectTheme(theme)`; the VM forwards to the use case
 *    and the upstream flow round-trips to reflect the new state.
 *
 * **Loading state**: a centered `CircularProgressIndicator` while `isLoading == true` — covers
 * the gap between subscription and first emission from the upstream pref flow. Same posture
 * as Sources / History / Updates loading branches.
 *
 * Constructor takes the [ThemeViewModel] directly (not the route's `NavController`) — the
 * route adapter in `:composeApp` is responsible for VM resolution, keeping the screen
 * nav-host-agnostic. Same posture as
 * [me.manga.kira.ui.sources.SourcesScreen] /
 * [me.manga.kira.ui.statistics.StatisticsScreen] (also terminal screens with no nav
 * callbacks).
 *
 * **SRP (contract §6)**: owns rendering + intent dispatch + nothing else. Theme state derivation
 * lives on [ThemeState]; selection propagation lives on [ThemeViewModel]; the icon/label
 * lookup for each [AppTheme] entry is a `when` co-located with the picker (a `:ui` concern,
 * not domain — the [AppTheme] ADT in `:domain` stays Compose-free).
 *
 * **Stateless inner [ThemeScreenContent]** for preview / test substitution — same convention
 * the other rework screens follow.
 *
 * **Package note** — lives in `me.manga.kira.ui.themepicker` (NOT `ui.theme`) to avoid
 * colliding with the existing `ui.theme` design-token package (`KiraColors`, `KiraShapes`,
 * `Spacing`, `KiraTheme`). The slice is named `theme` after its `:domain` concern, but the
 * `:ui` package surfaces it as `themepicker` to make the "picker UI" vs "theming tokens"
 * distinction obvious at the import site.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster3.staleKdocSweep.cascade,
 * Task #458, 2026-05-28): a stale citation into the §307-retired legacy
 * onboarding theme-picker sources appears above:
 *  - Lines 40-41 (Visual parity preamble): "Visual parity vs the legacy
 *    `composeApp/.../onboarding/theme_selection/ThemeSelectionScreen.kt`
 *    + `ThemeSelector.kt`".
 * Both the legacy
 * `presentation/features/onboarding/theme_selection/ThemeSelectionScreen.kt`
 * and `presentation/features/onboarding/theme_selection/ThemeSelector.kt`
 * were retired in Phase 9.x.onboarding.legacy_retire (§307 sweep, commit
 * `6c83364` "delete 5 unreachable legacy onboarding files"); verified by
 * a filesystem check returning zero hits for both paths. The visual-parity
 * bullets (TabRow picker shape with text-only labels, PureBlack/OLED Switch
 * addition, onboarding-flavour Continue + notification-permission grant row,
 * bottom-bar visibility, inline literal strings, design-token migration)
 * all stand on their own merits — the rework `:ui` design language's
 * material-icons-extended dep avoidance, the §122 sources.onboardingfinish
 * nullable-callback-IS-the-gate pattern, the Phase 10 i18n lift strategy,
 * and the LocalSpacing + Material 3 design-token discipline are documented
 * inline above and independent of which legacy files originally implemented
 * the parity precedent. Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citation is historical record
 * of the design lineage; the rework ThemeScreen continues to render the
 * theme-picker correctly through the legacy retire.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster9.staleKdocSweep.cascade,
 * Task #465, 2026-05-28): three stale citations into the §354-retired
 * legacy `:composeApp/.../features/settings/ui/screens/SettingsScreen.kt`
 * appear above:
 *  - Line 41 (Visual parity preamble): "Visual parity vs the legacy
 *    `composeApp/.../onboarding/theme_selection/ThemeSelectionScreen.kt`
 *    + `ThemeSelector.kt`** + the legacy `SettingsScreen` PureBlack row".
 *  - Line 51 (PureBlack switch behaviour rationale): "toggling the switch
 *    flips `KEY_PURE_BLACK` on the legacy `SharedPreferences` and the
 *    legacy `SettingsScreen`'s PureBlack toggle reflects the same value
 *    (single source of truth)".
 *  - Line 54 (Light-mode persistence behaviour): "but the value persists
 *    for the next dark resolution — matches the legacy `SettingsScreen`
 *    behaviour".
 * The legacy `:composeApp/.../features/settings/ui/screens/
 * SettingsScreen.kt` was retired in Phase 9.x.settings_about.legacyui.retire
 * (§354 sweep, commit `5cc42d2` "delete 11-file legacy Settings+About
 * orphan chain"); verified by a filesystem check returning zero hits for
 * that path. The PureBlack/OLED-switch single-source-of-truth rationale
 * (KEY_PURE_BLACK on legacy SharedPreferences) + the Light-mode-deferred-
 * persistence behaviour both stand on their own merits — the rework
 * Settings hub (§253 + §301) is now the sole consumer of the same
 * `KEY_PURE_BLACK` preference key via the rework Settings VM's theme-
 * section observation, and the PureBlack toggle on the rework Settings
 * hub continues to reflect the same value as this ThemeScreen surface
 * (single-source-of-truth invariant preserved post-§354 retire). Original
 * §253-era prose preserved verbatim per the audit-trail-preservation
 * convention — the citations are historical record of the design lineage;
 * the rework ThemeScreen continues to flip + observe `KEY_PURE_BLACK`
 * correctly past the §354 retire.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster38.staleKdocSweep.cascade,
 * Task #494, 2026-05-28): four stale citations beyond those enumerated
 * in the cluster3 (§458) + cluster9 (§465) postscripts above appear in
 * this file, spanning the class-level KDoc and three helper KDocs, all
 * referencing the §307-retired legacy onboarding theme-picker chain
 * (`AnimatedBackground.kt`, `ThemeSelectionScreen.kt`, `ThemeSelector.kt`)
 * and the §354-retired legacy `:composeApp/.../features/settings/ui/
 * screens/SettingsScreen.kt`:
 *  - Lines 76-78 (class-level KDoc, deferred-feature paragraph): "The
 *    `AnimatedBackground` gradient overlay + the auto-request lifecycle
 *    (`LaunchedEffect`-fired permission request + toast-on-denial) from
 *    the legacy `ThemeSelectionScreen` are still deferred — the route
 *    adapter (not this screen) is the right owner for both of those".
 *  - Lines 301-303 (PureBlackRow helper KDoc): "same row shape as the
 *    legacy `SettingsScreen`'s PureBlack toggle, though without the
 *    legacy's icon (rework `:ui` posture)".
 *  - Lines 338-340 (NotificationPermissionRow helper KDoc): "The copy
 *    mirrors the legacy `composeApp/.../onboarding/theme_selection/
 *    ThemeSelector.kt:103-110`'s `Res.string.notification_permission`
 *    resource literal verbatim".
 *  - Lines 382-383 (indexInPicker helper KDoc): "Order: Light(0),
 *    Dark(1), System(2) — matches the legacy `ThemeSelector`'s
 *    `AppTheme.entries` natural order".
 *  All four classified as STALE-SYMBOL-REFERENCE — Phase 9.x.onboarding.
 *  legacy_retire (§307, commit `6c83364` "delete 5 unreachable legacy
 *  onboarding files") DELETED the legacy `AnimatedBackground.kt`,
 *  `ThemeSelectionScreen.kt`, and `ThemeSelector.kt` as a cascade-
 *  orphan-retire chain (re-verified by prior §458 + §465 + cluster36
 *  sweeps — a recursive Glob across all three filenames returns NO
 *  MATCHES); Phase 9.x.settings_about.legacyui.retire (§354, commit
 *  `5cc42d2` "delete 11-file legacy Settings+About orphan chain")
 *  DELETED the legacy `:composeApp/.../features/settings/ui/screens/
 *  SettingsScreen.kt`. The L338-340 `ThemeSelector.kt:103-110` line-
 *  range cite is doubly stale (file deleted + line range no longer
 *  addressable); the L382-383 `ThemeSelector` natural-order reference
 *  is stale (file deleted); the L301-303 PureBlackRow cite of legacy
 *  `SettingsScreen` is stale (file deleted under §354); the L76-78
 *  deferred-feature paragraph's cite-targets are gone (the bare
 *  `AnimatedBackground` + `ThemeSelectionScreen` symbols survive only
 *  as documentation prose in sibling theme / welcome / sources /
 *  library KDocs + project documentation Markdown). HOWEVER — the
 *  architectural rationale of all four citations STANDS on its own
 *  merits past the §307 + §354 fulfilled landings as LIVE design-
 *  lineage records: (a) the L76-78 deferred-feature forecast describes
 *  a future cosmetic-port intent for both the gradient overlay AND
 *  the auto-request lifecycle that remains LIVE — the rework
 *  `Brush.linearGradient`-sweep substitution pattern (per §142
 *  migration log L795) is the canonical reference shape if/when ported,
 *  and the auto-request lifecycle remains an onboarding-route-adapter
 *  concern that the future Phase 7.x.theme.swap caller will own (the
 *  cite-target file is gone but the design forecast is LIVE); (b) the
 *  L301-303 PureBlackRow row-shape parity ("Material 3 `Switch` in a
 *  `Row` with the label on the left and the switch on the right")
 *  STANDS as the LIVE realization of the Material 3 row pattern, with
 *  the rework Settings hub (§253 + §301) now the sole non-ThemeScreen
 *  consumer of the same `KEY_PURE_BLACK` preference key — single-
 *  source-of-truth invariant preserved post-§354 retire; (c) the
 *  L338-340 notification-permission copy resource-literal cite is a
 *  HISTORICAL design-lineage record — the
 *  `Res.string.notification_permission` resource key itself is
 *  forecast LIVE for the Phase 10 i18n lift (the rework's inline
 *  literal will route through it post-lift, unifying legacy + rework
 *  consumers in one pass); (d) the L382-383 tab-order natural-order
 *  cite ("Light(0), Dark(1), System(2)") STANDS as the LIVE
 *  realization on the `:domain` `AppTheme` enum (`AppTheme.entries`
 *  natural order is preserved — Light/Dark/System), with the
 *  `indexInPicker` extension property defined here in `:ui` rather
 *  than on the `:domain` enum (UI-only concern, as the helper KDoc
 *  itself explains — a future picker with a different layout could
 *  ignore this extension and the `:domain` ADT stays uncluttered).
 *  Original §253-era prose preserved verbatim per the audit-trail-
 *  preservation convention — the citations are historical record of
 *  the design lineage including all four parity rationales that were
 *  subsequently fulfilled (legacy onboarding theme-picker chain
 *  retired under §307 + legacy Settings UI retired under §354).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(
    viewModel: ThemeViewModel,
    modifier: Modifier = Modifier,
    onContinue: (() -> Unit)? = null,
    hasNotificationPermission: Boolean = true,
    onRequestNotificationPermission: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    ThemeScreenContent(
        state = state,
        onIntent = viewModel::submit,
        modifier = modifier,
        onContinue = onContinue,
        hasNotificationPermission = hasNotificationPermission,
        onRequestNotificationPermission = onRequestNotificationPermission,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeScreenContent(
    state: ThemeState,
    onIntent: (ThemeIntent) -> Unit,
    modifier: Modifier = Modifier,
    onContinue: (() -> Unit)? = null,
    hasNotificationPermission: Boolean = true,
    onRequestNotificationPermission: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    // Native parity: the onboarding entry (the `ThemeSelectionScreen` wizard step) has NO
    // TopAppBar at all — it shows only the in-body "Choose Your Theme" headline. The standalone
    // Settings-reached screen keeps the Material 3 TopAppBar (and then suppresses the duplicate
    // body headline so only one title shows). `onContinue != null` is the onboarding marker:
    // the onboarding route is the sole caller that supplies a Continue callback.
    val isOnboarding = onContinue != null
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (!isOnboarding) {
                TopAppBar(
                    title = { Text(stringResource(Res.string.theme_screen_title)) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(Res.string.back),
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        if (state.isLoading) {
            LoadingBox(innerPadding)
        } else {
            ThemePickerColumn(
                selected = state.theme,
                pureBlack = state.pureBlack,
                onSelect = { onIntent(ThemeIntent.OnSelectTheme(it)) },
                onTogglePureBlack = { onIntent(ThemeIntent.OnTogglePureBlack(it)) },
                contentPadding = innerPadding,
                isOnboarding = isOnboarding,
                paddingHorizontal = spacing.lg,
                paddingVertical = spacing.md,
                onContinue = onContinue,
                hasNotificationPermission = hasNotificationPermission,
                onRequestNotificationPermission = onRequestNotificationPermission,
            )
        }
    }
}

@Composable
private fun LoadingBox(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ThemePickerColumn(
    selected: AppTheme,
    pureBlack: Boolean,
    onSelect: (AppTheme) -> Unit,
    onTogglePureBlack: (Boolean) -> Unit,
    contentPadding: PaddingValues,
    isOnboarding: Boolean,
    paddingHorizontal: androidx.compose.ui.unit.Dp,
    paddingVertical: androidx.compose.ui.unit.Dp,
    onContinue: (() -> Unit)?,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: (() -> Unit)?,
) {
    // Native parity — the onboarding screen uses a 24dp outer padding with
    // `verticalArrangement = SpaceBetween` (headline pinned top, Continue CTA pinned bottom) and
    // a fixed 16dp Spacer between the headline and the selector card. The standalone
    // Settings-reached screen keeps the rework's uniform LocalSpacing gaps (top-aligned, no CTA).
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .then(
                if (isOnboarding) {
                    Modifier.padding(24.dp)
                } else {
                    Modifier.padding(horizontal = paddingHorizontal, vertical = paddingVertical)
                },
            ),
        verticalArrangement = if (isOnboarding) {
            Arrangement.SpaceBetween
        } else {
            Arrangement.spacedBy(paddingVertical)
        },
        horizontalAlignment = if (isOnboarding) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        // The onboarding flow shows the in-body headline as its only title (no TopAppBar).
        // The standalone screen already shows "Theme" in the TopAppBar, so the duplicate body
        // headline is suppressed there — only one title renders per native.
        if (isOnboarding) {
            // GAP-THM-04 — native `choose_your_theme` is headlineMedium.copy(fontSize = 24.sp),
            // primary. kiraTypography() does not override headlineMedium (falls back to the M3
            // default ~28sp), so the explicit 24.sp override is required for parity.
            Text(
                text = stringResource(Res.string.choose_your_theme),
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp),
                color = MaterialTheme.colorScheme.primary,
            )
            // Native: fixed 16dp Spacer between the headline and the selector card.
            Spacer(modifier = Modifier.height(16.dp))
        }
        // GAP-THM (picker card parity) — native ThemeSelector wraps the picker in an ItemsGroup
        // card: surfaceContainerHigh @ alpha 0.4f, RoundedCornerShape(16dp), outer
        // padding(horizontal=16dp, vertical=8dp) + inner padding(16dp). Mirrored inline here
        // (the :ui module has no ItemsGroup component on its classpath and importing one is
        // out of this slice's scope).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(16.dp),
        ) {
            SecondaryTabRow(
                selectedTabIndex = selected.indexInPicker,
                modifier = Modifier.fillMaxWidth(),
                // Native uses surfaceVariant @ alpha 0f (fully transparent) container + a primary
                // indicator at the selected tab. SecondaryTabRow's default indicator is already a
                // primary, secondary-style indicator offset to selectedTabIndex — visually identical
                // to the old explicit TabRowDefaults.Indicator + tabIndicatorOffset, so no custom
                // indicator is needed (and the deprecated Indicator / tabIndicatorOffset APIs are gone).
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                AppTheme.entries.forEach { theme ->
                    // GAP-THM-04 — Light/Dark/System tab icons (native LightMode/DarkMode/
                    // SettingsBrightness) + primary label.
                    Tab(
                        selected = selected == theme,
                        onClick = { onSelect(theme) },
                        icon = {
                            Icon(
                                imageVector = themeIcon(theme),
                                contentDescription = null,
                            )
                        },
                        text = {
                            // Native uses AutoSubtitleText (auto-shrink, maxLines = 1) with
                            // bodyMedium @ 14sp / primary. The :ui module has no auto-sizing text
                            // component on its classpath; mirror the salient behaviour with
                            // bodyMedium @ 14sp / primary + maxLines = 1 + softWrap = false so the
                            // three labels stay single-line in the TabRow.
                            Text(
                                text = themeLabel(theme),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            // Notification section — native renders this unconditionally inside the selector
            // card whenever the onboarding caller is active (onRequestNotificationPermission
            // non-null), regardless of whether permission is already granted, separated from
            // the TabRow by a 24dp spacer.
            if (onRequestNotificationPermission != null) {
                Spacer(modifier = Modifier.height(24.dp))
                NotificationPermissionRow(onRequest = onRequestNotificationPermission)
            }
        }
        // PureBlack/OLED toggle — native exposes this ONLY on the Settings-reached screen, NOT in
        // the onboarding theme step (native onboarding `ThemeSelector` has no PureBlack control).
        // Gate it off the onboarding entry to match.
        if (!isOnboarding) {
            PureBlackRow(
                checked = pureBlack,
                onCheckedChange = onTogglePureBlack,
            )
        }
        if (onContinue != null) {
            // Native Continue button: fillMaxWidth + height(50dp) + clip(RoundedCornerShape(26dp))
            // + shape = shapes.medium + containerColor = primary, label labelLarge @ 16sp /
            // onPrimary. Enabled-gated on the notification permission state in the onboarding flow.
            Button(
                onClick = onContinue,
                enabled = onRequestNotificationPermission == null || hasNotificationPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(26.dp)),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = stringResource(Res.string.continue_string),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * Switch row for the PureBlack/OLED variant flag. Material 3 `Switch` in a `Row` with the
 * label on the left and the switch on the right — same row shape as the legacy
 * `SettingsScreen`'s PureBlack toggle, though without the legacy's icon (rework `:ui` posture).
 *
 * The switch is always interactive — see [ThemeScreen]'s KDoc for the always-interactive
 * rationale.
 */
@Composable
private fun PureBlackRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.pure_black_mode_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * Notification permission grant row (Phase 7.x.theme.onboardingpermission gap-lift).
 *
 * Renders whenever the host [ThemeScreen] caller supplied `onRequestNotificationPermission`
 * (the onboarding entry), unconditionally — i.e. even when permission is already granted —
 * to match the native `ThemeSelector`, which always shows the notification section inside
 * the picker card (see [ThemePickerColumn]). The row carries:
 *  - **Title** "Enable Notifications" — `bodyMedium` @ 14sp on `onBackground` with a 4dp
 *    bottom padding, matching native's header typography.
 *  - **Body** descriptive copy explaining why notifications are needed — `bodySmall` @ 12sp
 *    on `onBackground`, capped at `maxLines = 3` with an 8dp bottom padding, matching native.
 *    The copy mirrors native `ThemeSelector`'s `Res.string.notification_permission` resource
 *    literal verbatim. Phase 10 i18n lift will route both consumers through the existing
 *    `notification_permission` resource key.
 *  - **Grant button** right-aligned in a trailing `Row` — Material 3 `Button` with
 *    `contentPadding(horizontal = 16dp, vertical = 8dp)` matching native; primary container
 *    coloring via the design-system defaults (the rework lets the design system carry the
 *    styling rather than overriding with `containerColor = primary`).
 *
 * The grant button stays tappable while the row is visible; tapping it dispatches the
 * caller's [onRequest] callback, which (in the future Phase 7.x.theme.swap caller) re-launches
 * the underlying platform permission requester. Re-tapping while permission is already granted
 * is a no-op at the requester layer.
 */
@Composable
private fun NotificationPermissionRow(
    onRequest: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header — native: bodyMedium @ 14sp, onBackground, bottom padding 4dp.
        Text(
            text = stringResource(Res.string.enable_notifications),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        // Body — native: bodySmall @ 12sp, onBackground, maxLines 3, bottom padding 8dp.
        Text(
            text = stringResource(Res.string.notification_permission),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Native: Button with contentPadding(horizontal=16dp, vertical=8dp).
            Button(
                onClick = onRequest,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(Res.string.grant_permission))
            }
        }
    }
}

/**
 * Tab index for the current [AppTheme]. Order: Light(0), Dark(1), System(2) — matches the
 * legacy `ThemeSelector`'s `AppTheme.entries` natural order.
 *
 * Defined here (in `:ui`) and not on the `:domain` [AppTheme] enum because tab order is a
 * UI-only concern (a future picker with a different layout — radio list, segmented control —
 * could ignore this and the `:domain` ADT stays uncluttered).
 */
private val AppTheme.indexInPicker: Int
    get() = when (this) {
        AppTheme.Light -> 0
        AppTheme.Dark -> 1
        AppTheme.System -> 2
    }

/**
 * Localized human-readable label for each [AppTheme] entry (Phase 11.ui.UP-3c). Routes through
 * `Res.string.theme_light` / `theme_dark` / `theme_system` (reused legacy keys, en + ar). A
 * `@Composable` function rather than an extension `val` because `stringResource` is composable;
 * the en values ("Light Mode" / "Dark Mode" / "System") match the old app's picker labels.
 */
@Composable
private fun themeLabel(theme: AppTheme): String = when (theme) {
    AppTheme.Light -> stringResource(Res.string.theme_light)
    AppTheme.Dark -> stringResource(Res.string.theme_dark)
    AppTheme.System -> stringResource(Res.string.theme_system)
}

/**
 * Tab icon for each [AppTheme] entry (GAP-THM-04) — Light → [Icons.Default.LightMode],
 * Dark → [Icons.Default.DarkMode], System → [Icons.Default.SettingsBrightness], matching the
 * native onboarding theme-picker tab icons. Defined here (in `:ui`) and not on the `:domain`
 * [AppTheme] enum because the icon is a UI-only concern, same posture as [themeLabel] /
 * [indexInPicker].
 */
private fun themeIcon(theme: AppTheme): ImageVector = when (theme) {
    AppTheme.Light -> Icons.Default.LightMode
    AppTheme.Dark -> Icons.Default.DarkMode
    AppTheme.System -> Icons.Default.SettingsBrightness
}
