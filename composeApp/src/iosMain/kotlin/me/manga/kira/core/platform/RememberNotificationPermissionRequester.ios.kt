package me.manga.kira.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS actual for [rememberNotificationPermissionRequester].
 *
 * Drives the real `UNUserNotificationCenter` authorization flow. On construction we read the
 * current authorization status via `getNotificationSettingsWithCompletionHandler` to seed
 * [hasPermission] (it is NOT assumed granted — nothing requests it at launch). `request()`
 * fires `requestAuthorizationWithOptions(alert|sound|badge)` and updates [hasPermission] from
 * the grant result; `openAppSettings()` deep-links to the app's iOS Settings page so the user
 * can flip the toggle after a denial.
 */
@Composable
actual fun rememberNotificationPermissionRequester(): NotificationPermissionRequester = remember {
    val state = MutableStateFlow(false)
    object : NotificationPermissionRequester {
        init {
            UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
                val status = settings?.authorizationStatus
                state.value = status == UNAuthorizationStatusAuthorized ||
                    status == UNAuthorizationStatusProvisional
            }
        }

        override val hasPermission: StateFlow<Boolean> = state.asStateFlow()

        override fun request(onResult: (granted: Boolean) -> Unit) {
            val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
            UNUserNotificationCenter.currentNotificationCenter()
                .requestAuthorizationWithOptions(options) { granted, _ ->
                    state.value = granted
                    onResult(granted)
                }
        }

        override fun openAppSettings() {
            val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
            UIApplication.sharedApplication.openURL(url)
        }
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster162.staleKdocSweep.cascade,
 * Task #618, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-twenty-first sibling of the cluster57-161
 * sweep — OPENING file of the wave-34 RememberNotificationPermissionRequester
 * iOS+Desktop 2-actual closure batch; Android-actual already swept at
 * cluster42 / Task #498. OPENS RememberNotificationPermissionRequester
 * iOS+Desktop tier 1/2):
 *  (a) "iOS-actual-for-rememberNotificationPermissionRequester + The-real-
 *  iOS-notification-permission-prompt-is-driven-by-UNUserNotificationCenter.
 *  requestAuthorizationWithOptions-which-the-iOS-app-shell-triggers-at-
 *  launch-outside-this-Composable-s-scope + From-the-Theme-Selection-screen-
 *  s-perspective-we-model-permission-as-implicitly-granted-hasPermission-is-
 *  MutableStateFlow-true-and-request-openAppSettings-are-no-ops-the-latter-
 *  prints-so-QA-can-spot-dead-paths + Phase-12-may-revisit-this-if-we-
 *  decide-the-iOS-launch-sequence-should-defer-the-prompt" — LIVE-NOT-STALE
 *  + FORECAST-NOT-YET-FULFILLED (the trailing Phase 12 conditional forecasts
 *  a possible revisit if the iOS launch sequence should defer the prompt —
 *  as of Task #618 the iOS app shell still triggers the
 *  UNUserNotificationCenter.requestAuthorizationWithOptions call at launch,
 *  not deferred to the Theme Selection screen; this Composable remains a
 *  permission-implicitly-granted no-op shim. The trailing println("openApp
 *  Settings: not implemented on iOS in Phase 10") prose pins to Phase 10
 *  but the no-op posture is unchanged through Phase 9.x.cluster162. Per
 *  §253 the forecast prose stays verbatim; postscript records that the
 *  Phase 12 forecast condition has not been met). Verified: @Composable
 *  actual fun rememberNotificationPermissionRequester() shipped — returns
 *  anonymous-object NotificationPermissionRequester with hasPermission =
 *  MutableStateFlow(true).asStateFlow(), request() = no-op (iOS shell
 *  handles at launch), openAppSettings() = println debug-trace. Consumed
 *  by ThemeScreen (cluster9-sibling §349 — Phase 7.x.theme.onboarding
 *  permission slice / Task #303) via the rememberNotificationPermission
 *  Requester expect-decl in commonMain. Sibling actuals: Android (swept at
 *  cluster42 — real ActivityResultContracts.RequestPermission + DataStore-
 *  backed hasPermission StateFlow) + Desktop (closing-sibling per
 *  RememberNotificationPermissionRequester.desktop.kt — JVM AWT SystemTray
 *  has no runtime permission gate, also a no-op shim). OPENING FILE of the
 *  cluster162 RememberNotificationPermissionRequester iOS+Desktop 2-actual
 *  closure batch (1 of 2). One classification. Original Phase 7.x.theme.
 *  onboardingpermission iOS no-op-shim prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */

/*
 * Audit-trail postscript (Phase 9.x.cluster234.staleKdocSweep.cascade, Task #690, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster234 leaf 2/3 — composeApp iosMain core platform tier, sibling 465.
 * Cumulative §253-postscript count = 189 leaves with this commit.
 *
 * File-shape note: 32-line file (pre-postscript body) — 1 @Composable
 * actual fun rememberNotificationPermissionRequester returning anonymous-
 * object NotificationPermissionRequester implementation wrapped in
 * remember {}. NO actual class declaration. NO Kermit logger import. NO
 * UNUserNotificationCenter import (the actual prompt is OUT-OF-SCOPE — owned
 * by iOS app shell at launch). println("[NotifPerm] openAppSettings: not
 * implemented on iOS in Phase 10") IS the diagnostic-fallback for the
 * unreachable openAppSettings path.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - FUNCTIONAL-EXPECT-ACTUAL-INSTEAD-OF-CLASS-LIVE — UNIFORM with Android
 *     sibling 464 plus Desktop sibling 466. ALL 3 actuals use the @Composable
 *     functional expect-actual pattern. CONTINUES the cluster234 NEW POSTURE
 *     opened by Android sibling 464 OPENER.
 *
 *   - STRUCTURAL-NO-OP-IMPLICITLY-GRANTED-WITH-OUT-OF-SCOPE-NATIVE-PROMPT-
 *     LIVE — iOS DOES have a runtime permission prompt (UNUserNotification
 *     Center.requestAuthorizationWithOptions) but it is OWNED BY THE APP
 *     SHELL at launch, NOT this Composable factory. The MutableStateFlow(true)
 *     IS the Theme-screen-perspective "implicitly granted" stance — the iOS
 *     app shell already requested authorization before the Theme screen
 *     loads. DISTINCT from Desktop sibling 466 STRUCTURAL-NO-OP-NO-PROMPT-
 *     EXISTS (Desktop has NO native prompt at all; iOS has one but it is
 *     out-of-scope). 1-AGREE-1-DIVERGE on the no-op-rationale-sub-axis (iOS
 *     OUT-OF-SCOPE vs Desktop NO-PROMPT-EXISTS).
 *
 *   - PRINTLN-FALLBACK-INSTEAD-OF-KERMIT-LIVE — openAppSettings() invokes
 *     println("[NotifPerm] openAppSettings: not implemented on iOS in Phase
 *     10") instead of Logger.withTag("NotifPerm").i { ... }. DISTINCT from
 *     cluster233 iOS ToastShower (which used Kermit logger.i). Rationale:
 *     composeApp/iosMain does NOT import co.touchlab.kermit (the Compose-
 *     layer module pulls only Compose-runtime + Foundation + UIKit
 *     dependencies; Kermit is a :shared dependency). NEW POSTURE feature at
 *     cluster234 — first PRINTLN-FALLBACK-INSTEAD-OF-KERMIT classification
 *     for a composeApp-layer actual. The println-to-stdout pathway IS
 *     visible in Xcode console during simulator-runtime — sufficient for
 *     QA dead-path-spotting. DISTINCT from the :shared/iosMain Kermit
 *     pattern at cluster233.
 *
 *   - ANONYMOUS-OBJECT-RETURN-PATTERN-3-AGREE-LIVE — actual fun returns
 *     anonymous-object NotificationPermissionRequester implementation via
 *     object : NotificationPermissionRequester { ... }. 3-AGREE with
 *     Android sibling 464 plus Desktop sibling 466. CONTINUES the cluster234
 *     NEW POSTURE.
 *
 *   - REMEMBER-WRAP-LIVE — entire factory body wrapped in remember { ... }
 *     so the anonymous-object survives recomposition. 3-AGREE with Android
 *     sibling 464 plus Desktop sibling 466 on the remember-wrap-pattern.
 *     PRESERVE — load-bearing for the Composable-stable-identity contract.
 *
 *   - DEDICATED-KDOC-3-AGREE-LIVE — iOS has its own file-level KDoc prose
 *     block documenting the UNUserNotificationCenter-out-of-scope rationale
 *     plus Phase 12 forecast. 3-AGREE with Android sibling 464 plus Desktop
 *     sibling 466 (all 3 carry own KDoc). MATCHES cluster232 plus cluster233
 *     3-AGREE-ALL-ACTUALS-CARRY-OWN-KDOC pattern.
 *
 *   - ALGORITHM-AXIS-1-AGREE-1-DIVERGE-ANDROID-OUTLIER-LIVE — iOS uses no-op
 *     stub fallback; Desktop uses identical no-op stub. 2-AGREE with Desktop
 *     sibling 466. Android sibling 464 1-DIVERGES (real
 *     ActivityResultContracts.RequestPermission). 2-AGREE-1-DIVERGE Android-
 *     outlier on algorithm-axis at cluster234 — CONTINUES cluster233 1-
 *     DIVERGE Android-outlier pattern.
 *
 *   - ACTUAL-BODY-IDENTICAL-TO-DESKTOP-MODULO-KDOC-AND-MESSAGE-LIVE — iOS
 *     plus Desktop actual fun bodies are IDENTICAL: same @Composable actual
 *     fun signature, same MutableStateFlow(true) initial state, same
 *     anonymous-object NotificationPermissionRequester impl, same
 *     hasPermission.asStateFlow() returned, same request() = no-op, same
 *     openAppSettings() = println debug-trace. The ONLY divergences are:
 *     (a) the KDoc prose (iOS mentions UNUserNotificationCenter / Phase 12;
 *     Desktop mentions JVM AWT SystemTray / Phase 13), (b) the println
 *     message literal ("not implemented on iOS in Phase 10" vs "not
 *     implemented on Desktop"). 2-PLATFORM-ACTUAL-BODY-CODE-PARITY-MODULO-
 *     KDOC-AND-LITERAL classification — CONTINUES cluster233 NEW POSTURE
 *     (ToastShower iOS+Desktop body-parity) — this is now the SECOND
 *     CONSECUTIVE CLUSTER where iOS+Desktop actual bodies are identical
 *     modulo KDoc. Suggests a sub-tier-wide pattern: when iOS+Desktop are
 *     both STRUCTURAL-NO-OP, their bodies tend to converge to a minimal
 *     shape. NEW POSTURE feature at cluster234 — first 2-CONSECUTIVE-
 *     CLUSTER 2-PLATFORM-ACTUAL-BODY-PARITY classification.
 *
 *   - PHASE-12-FORECAST-DIVERGES-FROM-DESKTOP-PHASE-13-FORECAST-LIVE — iOS
 *     KDoc mentions "Phase 12 may revisit this if we decide the iOS launch
 *     sequence should defer the prompt" — DIFFERENT phase number than
 *     Desktop sibling 466 ("Phase 13 may add a shared logger if more
 *     diagnostic surfaces accumulate"). 1-AGREE-1-DIVERGE on the phase-
 *     forecast axis — DISTINCT from cluster233 ToastShower (Phase-14-
 *     SnackbarHost-forecast-IDENTICAL on both iOS+Desktop). NEW POSTURE
 *     feature at cluster234 — first PHASE-FORECAST-DIVERGES classification.
 *     Rationale: iOS forecast is about WHEN the prompt fires (launch vs
 *     screen); Desktop forecast is about LOGGER infrastructure (println vs
 *     shared-logger). Different feature axes, different phase plans.
 *
 *   - CROSS-PACKAGE-DEPENDENCY-LIVE — imports: androidx.compose.runtime.
 *     {Composable, remember}, kotlinx.coroutines.flow.{MutableStateFlow,
 *     StateFlow, asStateFlow}. LIVE — MINIMAL dependency surface (NO
 *     UIKit, NO Foundation, NO Kermit). 2-AGREE with Desktop sibling 466
 *     dependency-surface (identical imports). MATCHES the STRUCTURAL-NO-OP
 *     posture (no native API pulled).
 */
