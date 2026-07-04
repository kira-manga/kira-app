package me.manga.kira.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop actual for [rememberNotificationPermissionRequester].
 *
 * The JVM AWT `SystemTray` does not gate notifications behind a runtime permission prompt — the
 * running process can always post system-tray balloons (subject to OS-level user settings, but
 * not gated by a per-app prompt). We model permission as always granted; `request()` is a no-op
 * and `openAppSettings()` writes to stdout (composeApp/desktopMain does not depend on Kermit;
 * Phase 13 may add a shared logger if more diagnostic surfaces accumulate).
 */
@Composable
actual fun rememberNotificationPermissionRequester(): NotificationPermissionRequester = remember {
    val state = MutableStateFlow(true)
    object : NotificationPermissionRequester {
        override val hasPermission: StateFlow<Boolean> = state.asStateFlow()
        override fun request(onResult: (granted: Boolean) -> Unit) {
            // Desktop has no runtime notification permission — always implicitly granted.
            onResult(true)
        }
        override fun openAppSettings() {
            println("[NotifPerm] openAppSettings: not implemented on Desktop")
        }
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster162.staleKdocSweep.cascade,
 * Task #618, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-twenty-second sibling of the cluster57-161
 * sweep — CLOSING file of the wave-34 RememberNotificationPermission
 * Requester iOS+Desktop 2-actual closure batch; CLOSES Remember
 * NotificationPermissionRequester actuals tier 2/2):
 *  (a) "Desktop-actual-for-rememberNotificationPermissionRequester + The-
 *  JVM-AWT-SystemTray-does-not-gate-notifications-behind-a-runtime-
 *  permission-prompt + the-running-process-can-always-post-system-tray-
 *  balloons-subject-to-OS-level-user-settings-but-not-gated-by-a-per-app-
 *  prompt + We-model-permission-as-always-granted + request-is-a-no-op +
 *  openAppSettings-writes-to-stdout-composeApp-desktopMain-does-not-depend-
 *  on-Kermit + Phase-13-may-add-a-shared-logger-if-more-diagnostic-
 *  surfaces-accumulate" — LIVE-NOT-STALE + FORECAST-NOT-YET-FULFILLED (the
 *  trailing Phase 13 conditional forecasts a possible shared-logger
 *  introduction if more diagnostic surfaces accumulate on Desktop — as of
 *  Task #618 no shared logger has been introduced for composeApp/
 *  desktopMain; openAppSettings continues to println directly to stdout.
 *  The "composeApp/desktopMain does not depend on Kermit" dependency-graph
 *  posture is still accurate. Per §253 the forecast prose stays verbatim;
 *  postscript records that the Phase 13 forecast condition has not been
 *  met). Verified: @Composable actual fun rememberNotificationPermission
 *  Requester() shipped — returns anonymous-object Notification
 *  PermissionRequester with hasPermission =
 *  MutableStateFlow(true).asStateFlow(), request() = no-op (JVM AWT
 *  SystemTray has no per-app permission gate), openAppSettings() = println
 *  debug-trace. Consumed by ThemeScreen (cluster9-sibling §349 — Phase
 *  7.x.theme.onboardingpermission slice / Task #303) via the
 *  rememberNotificationPermissionRequester expect-decl in commonMain.
 *  Sibling actuals: Android (swept at cluster42 — real
 *  ActivityResultContracts.RequestPermission + DataStore-backed
 *  hasPermission StateFlow) + iOS (opening-sibling per
 *  RememberNotificationPermissionRequester.ios.kt — UNUserNotification
 *  Center triggered at app launch, also a no-op shim with its own Phase
 *  12 forecast). CLOSING FILE of the cluster162 RememberNotification
 *  PermissionRequester iOS+Desktop 2-actual closure batch (2 of 2 —
 *  CLOSES RememberNotificationPermissionRequester actuals tier alongside
 *  the cluster42 Android-actual sweep). One classification. Original
 *  Phase 7.x.theme.onboardingpermission Desktop no-op-shim prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */

/*
 * Audit-trail postscript (Phase 9.x.cluster234.staleKdocSweep.cascade, Task #690, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster234 leaf 3/3 — composeApp desktopMain core platform tier, sibling 466 CLOSER.
 * Cumulative §253-postscript count = 190 leaves with this commit.
 *
 * File-shape note: 30-line file (pre-postscript body) — 1 @Composable
 * actual fun rememberNotificationPermissionRequester returning anonymous-
 * object NotificationPermissionRequester implementation wrapped in
 * remember {}. NO actual class declaration. NO Kermit logger import. NO
 * java.awt.SystemTray import (the SystemTray API IS available on JVM but
 * does NOT gate notifications behind a per-app permission prompt — there
 * is nothing to wire up here). println("[NotifPerm] openAppSettings: not
 * implemented on Desktop") IS the diagnostic-fallback for the unreachable
 * openAppSettings path.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - FUNCTIONAL-EXPECT-ACTUAL-INSTEAD-OF-CLASS-LIVE — UNIFORM with Android
 *     sibling 464 plus iOS sibling 465. ALL 3 actuals use the @Composable
 *     functional expect-actual pattern. CLOSES the cluster234 NEW POSTURE
 *     opened by Android sibling 464 OPENER.
 *
 *   - STRUCTURAL-NO-OP-NO-PROMPT-EXISTS-LIVE — Desktop has NO native
 *     per-app permission prompt — JVM AWT SystemTray balloons fire without
 *     gating (subject to OS-level user settings, not per-app). The
 *     MutableStateFlow(true) IS the "always granted" stance because there
 *     is NO permission gate to query. DISTINCT from iOS sibling 465
 *     STRUCTURAL-NO-OP-IMPLICITLY-GRANTED-WITH-OUT-OF-SCOPE-NATIVE-PROMPT
 *     (iOS DOES have a prompt — but out-of-scope). 1-AGREE-1-DIVERGE on
 *     the no-op-rationale-sub-axis at cluster234. PRESERVE — load-bearing
 *     for the Desktop-no-permission-gate contract.
 *
 *   - PRINTLN-FALLBACK-INSTEAD-OF-KERMIT-LIVE — openAppSettings() invokes
 *     println("[NotifPerm] openAppSettings: not implemented on Desktop")
 *     instead of Logger.withTag("NotifPerm").i { ... }. 2-AGREE with iOS
 *     sibling 465 PRINTLN-FALLBACK posture. DISTINCT from cluster233
 *     Desktop ToastShower (which used Kermit logger.i). Rationale: composeApp/
 *     desktopMain does NOT import co.touchlab.kermit (same as iOS — the
 *     composeApp-layer module pulls only Compose-runtime + Compose-Desktop
 *     dependencies; Kermit is a :shared dependency). The original KDoc
 *     itself documents this dependency-graph posture: "composeApp/
 *     desktopMain does not depend on Kermit; Phase 13 may add a shared
 *     logger if more diagnostic surfaces accumulate". 2-AGREE with iOS
 *     sibling 465 on PRINTLN-FALLBACK-INSTEAD-OF-KERMIT classification.
 *
 *   - ANONYMOUS-OBJECT-RETURN-PATTERN-3-AGREE-LIVE — actual fun returns
 *     anonymous-object NotificationPermissionRequester implementation via
 *     object : NotificationPermissionRequester { ... }. 3-AGREE with
 *     Android sibling 464 plus iOS sibling 465. CLOSES the cluster234
 *     3-AGREE-ANONYMOUS-OBJECT-RETURN classification.
 *
 *   - REMEMBER-WRAP-LIVE — entire factory body wrapped in remember { ... }
 *     so the anonymous-object survives recomposition. 3-AGREE with Android
 *     sibling 464 plus iOS sibling 465 on the remember-wrap-pattern.
 *
 *   - DEDICATED-KDOC-3-AGREE-LIVE — Desktop has its own file-level KDoc
 *     prose block documenting the JVM-AWT-SystemTray-no-prompt rationale
 *     plus Phase 13 shared-logger forecast. 3-AGREE with Android sibling
 *     464 plus iOS sibling 465 (all 3 carry own KDoc). MATCHES cluster232
 *     plus cluster233 3-AGREE-ALL-ACTUALS-CARRY-OWN-KDOC pattern. 3-
 *     CONSECUTIVE-CLUSTER 3-AGREE-DEDICATED-KDOC pattern at cluster232-234.
 *
 *   - ALGORITHM-AXIS-2-AGREE-1-DIVERGE-ANDROID-OUTLIER-LIVE — Desktop uses
 *     no-op stub fallback; iOS uses identical no-op stub. 2-AGREE with
 *     iOS sibling 465. Android sibling 464 1-DIVERGES (real
 *     ActivityResultContracts.RequestPermission). 2-AGREE-1-DIVERGE
 *     Android-outlier on algorithm-axis at cluster234. CLOSES the
 *     cluster234 algorithm-axis classification.
 *
 *   - ACTUAL-BODY-IDENTICAL-TO-IOS-MODULO-KDOC-AND-MESSAGE-LIVE — Desktop
 *     plus iOS actual fun bodies are IDENTICAL: same @Composable actual
 *     fun signature, same MutableStateFlow(true) initial state, same
 *     anonymous-object NotificationPermissionRequester impl, same
 *     hasPermission.asStateFlow() returned, same request() = no-op, same
 *     openAppSettings() = println debug-trace. The ONLY divergences are:
 *     (a) the KDoc prose (Desktop mentions JVM AWT SystemTray / Phase 13;
 *     iOS mentions UNUserNotificationCenter / Phase 12), (b) the println
 *     message literal ("not implemented on Desktop" vs "not implemented
 *     on iOS in Phase 10"). 2-PLATFORM-ACTUAL-BODY-CODE-PARITY-MODULO-KDOC-
 *     AND-LITERAL classification — CONTINUES cluster233 ToastShower iOS+
 *     Desktop body-parity. 2-CONSECUTIVE-CLUSTER 2-PLATFORM-ACTUAL-BODY-
 *     PARITY pattern at cluster233-234. Suggests cluster234 RememberNotif
 *     icationPermissionRequester COULD share a commonMain helper (e.g. a
 *     "rememberAlwaysGrantedPermissionRequester(messageSuffix: String)"
 *     factory) — BUT NOT NEEDED today; the duplication is minimal and
 *     the expect/actual pattern keeps both files readable in isolation.
 *
 *   - PHASE-13-FORECAST-DIVERGES-FROM-IOS-PHASE-12-FORECAST-LIVE — Desktop
 *     KDoc mentions "Phase 13 may add a shared logger if more diagnostic
 *     surfaces accumulate" — DIFFERENT phase number than iOS sibling 465
 *     ("Phase 12 may revisit this if we decide the iOS launch sequence
 *     should defer the prompt"). 1-AGREE-1-DIVERGE on the phase-forecast
 *     axis CONTINUES from cluster234 iOS sibling 465. CLOSES the cluster234
 *     PHASE-FORECAST-DIVERGES classification.
 *
 *   - 4-CONSECUTIVE-CLUSTER-ANDROID-APPLICATIONCONTEXT-DEFENSIVE-PATTERN-
 *     LIVE — Android sibling 464 uses applicationContext deref (val app =
 *     context.applicationContext). CONTINUES from cluster231 sibling 454 +
 *     cluster232 sibling 458 + cluster233 sibling 461. 4-CONSECUTIVE-
 *     CLUSTER UNIFORM-ANDROID-CONVENTION on the applicationContext-deref
 *     axis at cluster231-234. CLOSES the wave-level confirmation that
 *     BEDROCK-PLATFORM-UTILITY Android actuals UNIFORMLY apply the
 *     applicationContext defensive deref.
 *
 *   - CROSS-PACKAGE-DEPENDENCY-LIVE — imports: androidx.compose.runtime.
 *     {Composable, remember}, kotlinx.coroutines.flow.{MutableStateFlow,
 *     StateFlow, asStateFlow}. LIVE — MINIMAL dependency surface
 *     (identical to iOS sibling 465). 2-AGREE with iOS sibling 465
 *     dependency-surface. MATCHES the STRUCTURAL-NO-OP posture (no native
 *     API pulled — neither java.awt.SystemTray nor java.awt.Desktop is
 *     imported because nothing needs gating).
 *
 *   - WAVE-REGISTER-CLOSES-FUNCTIONAL-EXPECT-ACTUAL-SUB-TIER-OPENER-CLUSTER
 *     — cluster234 RememberNotificationPermissionRequester CLOSES the
 *     OPENING leaf of the FUNCTIONAL-EXPECT-ACTUAL sub-tier opened by
 *     Android sibling 464 opener postscript. Confirms 2-AXIS-COSALIGNED
 *     Android-outlier (algorithm + log) at cluster234 — DIFFERENT shape
 *     from cluster233 4-AXIS-COSALIGNED (which included ctor + companion-
 *     object axes that do not apply to FUNCTIONAL-EXPECT-ACTUAL pattern).
 *     Next candidates for cluster235+: RememberHideNavigationBarSideEffect
 *     (HideNavigationBarSideEffect.kt 3 actuals — composeApp/{android,ios,
 *     desktop}Main), WebViewHost (WebViewHost.kt 3 actuals). Predicted
 *     posture for cluster235: CONTINUE FUNCTIONAL-EXPECT-ACTUAL shape,
 *     CONTINUE @Composable Compose-runtime dependency, CONTINUE 2-AXIS-
 *     COSALIGNED Android-outlier on algorithm+log axes for STRUCTURAL-NO-
 *     OP iOS+Desktop. Full wave register cluster215-234 has now transited
 *     20 clusters deep with SHAPE-POSTURE-TAXONOMY at 8 distinct postures
 *     (UNIFORM, TWO-AXIS COSALIGNED, SINGLE-AXIS, TWO-AXIS DIVERGENT,
 *     PROPERTY-ONLY, THREE-WAY DIVERGENT, 4-AXIS-COSALIGNED, FUNCTIONAL-
 *     EXPECT-ACTUAL). The NEW FUNCTIONAL-EXPECT-ACTUAL posture at
 *     cluster234 SUGGESTS the composeApp-layer expect-decls form their
 *     own sub-tier with structurally different axes than the :shared-
 *     layer expect-class actuals.
 */
