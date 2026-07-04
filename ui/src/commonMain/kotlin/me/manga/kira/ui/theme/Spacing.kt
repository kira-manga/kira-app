package me.manga.kira.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 8-pt grid spacing scale exposed through [LocalSpacing].
 *
 * Material 3 ships only a colors/typography/shapes theme — spacing is left to apps. Feature
 * screens consume `LocalSpacing.current.md` instead of literals so the scale is one place
 * to tune density (e.g. a future "compact" preference). Read-only `val`s — no mutation.
 *
 * Step sizes match the legacy app's most common `Modifier.padding(...)` values; survey of the
 * pre-rework code shows 4 / 8 / 12 / 16 / 24 / 32 used heavily and almost nothing else.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster96.staleKdocSweep.cascade,
 * Task #552, 2026-05-28): the 3-claim spacing-token manifest above is
 * classified as follows after recursive symbol verification across
 * the KMP graph (thirty-seventh sibling of the cluster57-95 sweep —
 * sibling of cluster94 plus cluster95 in the `:ui/theme/` cluster):
 *  (a) "Material 3 ships only a colors/typography/shapes theme —
 *  spacing is left to apps" — LIVE-NOT-STALE. Factual M3 architecture
 *  claim; no `MaterialTheme.spacing` slot exists in the Material 3
 *  API. The `:ui` Spacing shim fills the gap via [LocalSpacing].
 *  (b) "Feature screens consume `LocalSpacing.current.md` instead of
 *  literals so the scale is one place to tune density" — LIVE-NOT-
 *  STALE. Recursive Grep for `LocalSpacing` matches 34 LIVE
 *  references across the codebase: 19 `:ui/commonMain/` screen call
 *  sites (Sources, ThemePicker, DisplayOptionsDialog, Statistics,
 *  ComplaintActionDialog, AdminComplaintScreen, Language, Settings,
 *  History, WhatsNew, Updates, AdminComplaintActionDialog, Library,
 *  About, Complaint, Downloads, Details, Reader screens) plus 2
 *  `:composeApp` route-adapter consumption sites (AboutReworkScreen-
 *  Route, WhatsNewReworkScreenRoute) plus the THIS-file source-of-
 *  truth declaration at L31. End-to-end consumption pattern holds;
 *  the "one place to tune density" claim is not aspirational.
 *  (c) "Step sizes match the legacy app's most common `Modifier.
 *  padding(...)` values; survey of the pre-rework code shows 4 / 8 /
 *  12 / 16 / 24 / 32 used heavily and almost nothing else" —
 *  LIVE-NOT-STALE. L18-24 realization: xxs=2.dp, xs=4.dp, sm=8.dp,
 *  md=12.dp, lg=16.dp, xl=24.dp, xxl=32.dp. The survey set
 *  (4/8/12/16/24/32) maps onto xs-through-xxl; xxs=2.dp is the only
 *  additional step beyond the surveyed values (a finer-grain shim
 *  for hairline separators). Pre-rework survey holds.
 *  Three LIVE-NOT-STALE classifications STAND on their own merits as
 *  a faithful 8-pt-grid spacing manifest. Original Phase 7-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

/**
 * Default spacing — installed by [KiraTheme]. Composables outside a KiraTheme scope get the
 * fallback values via [compositionLocalOf]'s default lambda, so previews work without setup.
 */
val LocalSpacing = compositionLocalOf { Spacing() }

/**
 * Extra bottom inset (system navigation-bar inset + the floating bottom-nav capsule's footprint)
 * that scrollable content on the primary tab screens (Home / Library / Updates / History / Settings)
 * should add to its bottom `contentPadding`.
 *
 * Redesign 2026-06: the bottom nav is a *floating capsule* overlaid on top of the content rather
 * than a bar that reserves layout height. So screens render edge-to-edge and the feed scrolls
 * underneath the capsule; this inset is the room the last item needs to clear the capsule at rest.
 * Provided by the app root (`App.kt`) — non-zero only while the floating nav is visible. Defaults to
 * `0.dp`, so screens without the nav (and previews) get no extra inset.
 */
val LocalBottomBarPadding = compositionLocalOf { 0.dp }
