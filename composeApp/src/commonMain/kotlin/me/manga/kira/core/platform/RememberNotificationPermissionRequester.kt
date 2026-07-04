package me.manga.kira.core.platform

import androidx.compose.runtime.Composable

/**
 * Composable factory for [NotificationPermissionRequester].
 *
 * Lives in composeApp/commonMain (not :shared) because the :shared module deliberately has no
 * Compose dependency — see migration plan Phase 10. The interface declaration is in :shared so
 * non-Compose layers can still hold a reference.
 *
 * The Android actual hooks into `rememberLauncherForActivityResult(...)` so the launcher is
 * scoped to the surrounding ComponentActivity. iOS/Desktop actuals don't have runtime permission
 * prompts — they return a stub whose `hasPermission` is always `true`.
 */
@Composable
expect fun rememberNotificationPermissionRequester(): NotificationPermissionRequester

/**
 * **Audit-trail postscript** (Phase 9.x.cluster155.staleKdocSweep.cascade,
 * Task #611, 2026-05-28): classified as follows after recursive symbol
 * verification (two-hundred-and-third sibling of the cluster57-154 sweep —
 * CONTINUING file of the wave-27 :composeApp platform-shim expect-decl
 * 4-leaf batch alongside HideNavigationBarSideEffect plus FastScroller
 * GestureExclusion plus WebViewHost; CONTINUES :composeApp platform-shim
 * tier 3/4):
 *  (a) "Composable-factory-for-NotificationPermissionRequester + Lives-in
 *  -composeApp-commonMain-not-:shared-because-the-:shared-module-
 *  deliberately-has-no-Compose-dependency-see-migration-plan-Phase-10 +
 *  The-interface-declaration-is-in-:shared-so-non-Compose-layers-can-
 *  still-hold-a-reference + The-Android-actual-hooks-into-remember
 *  LauncherForActivityResult-so-the-launcher-is-scoped-to-the-surrounding
 *  -ComponentActivity + iOS-Desktop-actuals-don-t-have-runtime-permission
 *  -prompts-they-return-a-stub-whose-hasPermission-is-always-true" —
 *  LIVE-NOT-STALE plus FULFILLED-PORT. Verified: @Composable expect fun
 *  rememberNotificationPermissionRequester(): NotificationPermission
 *  Requester shipped as a zero-parameter Composable factory. The
 *  "interface in :shared, Composable factory in :composeApp/commonMain"
 *  split-tier stance honored — NotificationPermissionRequester interface
 *  declaration lives in :shared (so non-Compose layers can still hold a
 *  reference), the rememberLauncher-backed Composable factory lives in
 *  :composeApp/commonMain (where the Compose dependency is allowed). The
 *  "Android actual hooks into rememberLauncherForActivityResult scoped to
 *  surrounding ComponentActivity" actuals contract honored. The "iOS /
 *  Desktop actuals stub hasPermission = true" no-op posture honored.
 *  Consumed by ThemeReworkScreenRoute (cluster9 sibling X) +
 *  HomeScreenRoute (cluster44 sibling X) — the two call sites that opt-
 *  in to a notification-permission prompt. CONTINUING FILE of the
 *  cluster155 :composeApp platform-shim expect-decl 4-leaf batch (3 of
 *  4). One classification. Original Phase 5.y.1.notification-permission
 *  expect-decl prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
