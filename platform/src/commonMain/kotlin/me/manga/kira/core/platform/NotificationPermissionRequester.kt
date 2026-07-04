package me.manga.kira.core.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * Cross-platform handle to the OS-level notification permission state.
 *
 * On Android (API 33+), notification posting requires the `POST_NOTIFICATIONS` runtime permission.
 * On iOS, the equivalent is `UNUserNotificationCenter.requestAuthorizationWithOptions(...)`, but
 * the iOS app shell requests that at launch — so our iOS actual treats permission as implicitly
 * granted from this screen's perspective.
 *
 * On Desktop (JVM AWT SystemTray), there is no permission prompt — system tray notifications are
 * always allowed for the running process (subject to OS settings, but not gated by a user prompt).
 *
 * The interface lives in commonMain so non-Compose layers (e.g. ViewModels) can hold a reference.
 * The Composable factory (`rememberNotificationPermissionRequester()`) is declared in
 * composeApp/commonMain because :shared deliberately has no Compose dependency — see Phase 10
 * notes in the migration plan.
 */
interface NotificationPermissionRequester {

    /** Hot flow that emits the current permission state. Starts with the cached/initial value. */
    val hasPermission: StateFlow<Boolean>

    /**
     * Triggers the platform's permission prompt if available. No-op when the platform has no
     * runtime prompt (iOS/Desktop) or permission is already granted.
     *
     * @param onResult invoked with the final outcome once the prompt resolves: `true` when the
     *   permission is granted (or no runtime prompt exists), `false` on an actual user denial.
     *   Callers should surface a "denied" message only from `onResult(false)` — never by inferring
     *   denial from [hasPermission] state, which cannot distinguish "request in flight" from
     *   "denied" (both leave [hasPermission] `false`).
     */
    fun request(onResult: (granted: Boolean) -> Unit = {})

    /**
     * Opens the app's settings screen so the user can grant the permission manually after a
     * "Don't ask again" denial. On platforms without runtime permission prompts, this should
     * still attempt to surface the relevant settings UI (or no-op + log if there is none).
     */
    fun openAppSettings()
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster176.staleKdocSweep.cascade,
 * Task #640, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-fifty-second sibling of the cluster57-175
 * sweep — third leaf of the wave-46 commonMain core/platform 4-leaf
 * batch; NotificationPermissionRequester interface 3/4).
 *
 *  (a) KDoc "Cross-platform-handle-to-the-OS-level-notification-permission-
 *  state + On-Android-API-33-plus-notification-posting-requires-the-POST_
 *  NOTIFICATIONS-runtime-permission + On-iOS-the-equivalent-is-UNUser
 *  NotificationCenter-requestAuthorizationWithOptions-but-the-iOS-app-
 *  shell-requests-that-at-launch-so-our-iOS-actual-treats-permission-as-
 *  implicitly-granted-from-this-screen-s-perspective + On-Desktop-JVM-
 *  AWT-SystemTray-there-is-no-permission-prompt-system-tray-notifications-
 *  are-always-allowed-for-the-running-process" — LIVE-NOT-STALE (the
 *  three-target permission-asymmetry IS the structural truth: Android
 *  API-33+ POST_NOTIFICATIONS is the documented runtime-permission gate
 *  from Tiramisu onwards; iOS-app-shell-at-launch + treat-as-granted
 *  posture matches the iosMain actual that returns hasPermission as a
 *  hot StateFlow with initial value true (verified by reading the
 *  RememberNotificationPermissionRequester.ios.kt actual under
 *  cluster162-swept); Desktop SystemTray's no-prompt posture is JVM-AWT
 *  invariant — system tray icons require no permission gate). (b) KDoc
 *  "The-interface-lives-in-commonMain-so-non-Compose-layers-e-g-ViewModels-
 *  can-hold-a-reference + The-Composable-factory-rememberNotification
 *  PermissionRequester-is-declared-in-composeApp-commonMain-because-
 *  shared-deliberately-has-no-Compose-dependency-see-Phase-10-notes-in-
 *  the-migration-plan" — LIVE-NOT-STALE (the layering separation IS
 *  preserved: this file at shared/commonMain/.../core/platform/ has NO
 *  Compose imports — only kotlinx.coroutines.flow.StateFlow. The
 *  rememberNotificationPermissionRequester() factory lives at
 *  composeApp/commonMain/.../RememberNotificationPermissionRequester.kt
 *  per cluster162 sweep — the Compose-free :shared module / Compose-
 *  aware :composeApp module split IS structurally enforced. The
 *  "ViewModels can hold a reference" claim holds — ThemeViewModel +
 *  onboarding flow inject this interface via Koin without pulling
 *  Compose runtime). (c) KDoc on hasPermission "Hot-flow-that-emits-the-
 *  current-permission-state-Starts-with-the-cached-initial-value" —
 *  LIVE-NOT-STALE (StateFlow<Boolean> IS Kotlin's canonical hot-flow
 *  primitive — starts with initial value at subscription time AND emits
 *  every subsequent change. The "cached/initial value" contract matches
 *  the per-platform actual implementations: Android queries
 *  ContextCompat.checkSelfPermission() at construction time; iOS
 *  defaults to true at construction; Desktop defaults to true at
 *  construction). (d) KDoc on request "Triggers-the-platform-s-permission-
 *  prompt-if-available-No-op-when-the-platform-has-no-runtime-prompt-
 *  iOS-Desktop-or-permission-is-already-granted" — LIVE-NOT-STALE (the
 *  conditional-prompt semantics ARE the documented contract: Android
 *  actual invokes ActivityResultContracts.RequestPermission; iOS actual
 *  is no-op (early return on true initial state); Desktop actual is
 *  no-op. The "already granted" short-circuit IS implemented in the
 *  Android actual via checkSelfPermission gate). (e) KDoc on
 *  openAppSettings "Opens-the-app-s-settings-screen-so-the-user-can-
 *  grant-the-permission-manually-after-a-Don-t-ask-again-denial + On-
 *  platforms-without-runtime-permission-prompts-this-should-still-
 *  attempt-to-surface-the-relevant-settings-UI-or-no-op-plus-log-if-
 *  there-is-none" — LIVE-NOT-STALE (the deeplink-to-settings semantics
 *  ARE the documented contract: Android actual uses
 *  Settings.ACTION_APPLICATION_DETAILS_SETTINGS with the app's package
 *  URI; iOS actual uses UIApplication.openURL with
 *  UIApplicationOpenSettingsURLString; Desktop actual is a documented
 *  no-op + Kermit log per the "no settings UI" fallback).
 *
 * Verified: interface NotificationPermissionRequester with hasPermission
 * StateFlow + request() + openAppSettings() methods. Sibling: ToastShower.kt
 * + AppVersionProvider.kt (cluster176 prior siblings); IntentLauncher.kt
 * (cluster176 closing sibling). THIRD FILE of the cluster176 commonMain
 * core/platform 4-leaf batch (3 of 4). Five classifications. Original
 * Phase 5-era NotificationPermissionRequester interface prose preserved
 * verbatim per the audit-trail-preservation convention.
 */

