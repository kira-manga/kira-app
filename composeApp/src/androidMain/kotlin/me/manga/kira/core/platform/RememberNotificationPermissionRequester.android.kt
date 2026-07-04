package me.manga.kira.core.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android actual for [rememberNotificationPermissionRequester].
 *
 * Ported in spirit from upstream `ThemeSelectionScreen.kt` + `NotificationPermissionRequester`
 * helper in `ThemeSelectionScreenRoute.kt`. Uses `ActivityResultContracts.RequestPermission()`
 * scoped via `rememberLauncherForActivityResult` so the launcher participates in the normal
 * Compose-Activity lifecycle.
 *
 * Initial state matches the original `hasPostNotificationPermission(context)` check: pre-TIRAMISU
 * devices short-circuit to `true`; TIRAMISU+ devices probe `POST_NOTIFICATIONS` via
 * `ContextCompat.checkSelfPermission`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster42.staleKdocSweep.cascade,
 * Task #498, 2026-05-28): two stale citations into §307-retired legacy
 * `:shared/.../ThemeSelectionScreen.kt` + §291-rewritten in-file
 * `NotificationPermissionRequester` helper class appear above:
 *  - Line 23 ("Ported in spirit from upstream `ThemeSelectionScreen.kt`")
 *    cites the legacy `:shared/.../features/onboarding/ui/screens/
 *    ThemeSelectionScreen.kt` as the design-lineage anchor for this
 *    `:platform`-style cross-platform actual.
 *  - Lines 23-24 ("+ `NotificationPermissionRequester` helper in
 *    `ThemeSelectionScreenRoute.kt`") cites the in-file
 *    `NotificationPermissionRequester` helper class shape that
 *    originally lived inside the legacy route adapter.
 *  Classified as follows:
 *  (a) Line 23 — STALE-SYMBOL-REFERENCE. The legacy
 *  `:shared/.../features/onboarding/ui/screens/ThemeSelectionScreen.kt`
 *  was DELETED in Phase 9.x.onboarding.legacy_retire (§307 sweep, commit
 *  `6c83364` "delete 5 unreachable legacy onboarding files") — a
 *  recursive Glob for `ThemeSelectionScreen.kt` returns NO MATCHES. The
 *  Markdown backtick-prose mention `ThemeSelectionScreen.kt` survives
 *  only as documentation prose in sibling KDocs + project documentation
 *  Markdown (ARCHITECTURE.md, SOLID_AUDIT.md, migration logs,
 *  PLAN_*.md) — the Kotlin source class itself is retired. HOWEVER —
 *  the architectural rationale of the citation STANDS on its own
 *  merits past the §307 fulfilled landing as a LIVE design-lineage
 *  record: the "Ported in spirit from upstream" framing describes the
 *  HISTORICAL lineage of this `:platform`-style cross-platform actual
 *  (Android probes `POST_NOTIFICATIONS` via `ContextCompat.
 *  checkSelfPermission` pre-TIRAMISU short-circuit pattern) — the
 *  in-spirit port lineage is historically accurate even though the
 *  cite-target file is retired. The pre-TIRAMISU short-circuit + the
 *  TIRAMISU+ `POST_NOTIFICATIONS` runtime-permission probe + the
 *  `ActivityResultContracts.RequestPermission()` launcher pattern are
 *  the LIVE realizations of the original upstream design.
 *  (b) Lines 23-24 — STALE-SYMBOL-REFERENCE for the cite of the
 *  in-file `NotificationPermissionRequester` helper class shape. The
 *  legacy `ThemeSelectionScreenRoute.kt` route adapter SURVIVES on
 *  disk (rewritten under §291 (Phase 7.x.theme.swap) to host the
 *  rework `:ui` ThemeScreen backed by the rework `ThemeViewModel`,
 *  per the §291 swap landing); the route key + adapter survive, but
 *  the original in-file `NotificationPermissionRequester` helper class
 *  shape (a private class inside the route adapter file) was REWRITTEN
 *  under §291 into the cross-platform `expect`/`actual`
 *  `NotificationPermissionRequester` interface in
 *  `:composeApp/commonMain/.../core/platform/`, with Android actual
 *  here. The bare cite-target helper class is retired but the
 *  conceptual reference to "the legacy in-file helper" survives as a
 *  historical pointer to the original single-file shape — the in-
 *  spirit port lineage is historically accurate even though the
 *  cite-target helper class is now structured differently. The
 *  architectural rationale stands on its own merits: the §291
 *  cross-platform interface lift is the LIVE realization of the
 *  original single-file helper class.
 *  Ancillary references in the same KDoc are LIVE-NOT-STALE and
 *  require no individual stale-classification on their own merits:
 *  (c) Line 21 — `[rememberNotificationPermissionRequester]` Dokka
 *  link to the `expect` declaration in
 *  `:composeApp/commonMain/.../core/platform/
 *  RememberNotificationPermissionRequester.kt` resolves LIVE — the
 *  `expect` declaration is present and is the canonical cross-
 *  platform shape for which this file is the Android actual;
 *  (d) Lines 24-26 — `ActivityResultContracts.RequestPermission()` +
 *  `rememberLauncherForActivityResult` cite Android-only platform
 *  APIs (LIVE Compose-Activity integration);
 *  (e) Lines 28-30 — `hasPostNotificationPermission(context)` cites
 *  the private function at L68-76 (LIVE in-file helper);
 *  (f) Line 29 — TIRAMISU API-level cite (LIVE Android `Build.
 *  VERSION_CODES.TIRAMISU` constant);
 *  (g) Line 30 — `ContextCompat.checkSelfPermission` cite (LIVE
 *  `androidx.core.content.ContextCompat` import).
 *  Original §253-era prose preserved verbatim per the audit-trail-
 *  preservation convention — the citations are historical record of
 *  the design lineage including the §307-retired ThemeSelectionScreen
 *  cite and the §291-rewritten in-file `NotificationPermissionRequester`
 *  helper class shape; the Android actual continues to provide the
 *  canonical `POST_NOTIFICATIONS` permission requester surface past
 *  the §307 retire + §291 cross-platform lift.
 */
@Composable
actual fun rememberNotificationPermissionRequester(): NotificationPermissionRequester {
    val context = LocalContext.current
    val app = context.applicationContext
    val hasPermission = remember { MutableStateFlow(hasPostNotificationPermission(app)) }

    // Re-probe on ON_RESUME so a permission granted from system settings (after openAppSettings())
    // is reflected when the user returns to the app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, app) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission.value = hasPostNotificationPermission(app)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Holds the callback for the request currently in flight so the launcher result can report the
    // actual granted/denied outcome back to the caller (the launcher's own callback closure is
    // created once at remember-time and can't capture a per-request callback directly).
    val pendingResult = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission.value = granted
        pendingResult.value?.invoke(granted)
        pendingResult.value = null
    }

    return remember(app, launcher) {
        object : NotificationPermissionRequester {
            override val hasPermission: StateFlow<Boolean> = hasPermission.asStateFlow()

            override fun request(onResult: (granted: Boolean) -> Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pendingResult.value = onResult
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Pre-TIRAMISU: no runtime permission needed — implicitly granted.
                    hasPermission.value = true
                    onResult(true)
                }
            }

            override fun openAppSettings() {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", app.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(intent)
            }
        }
    }
}

private fun hasPostNotificationPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

/*
 * Audit-trail postscript (Phase 9.x.cluster234.staleKdocSweep.cascade, Task #690, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster234 leaf 1/3 — composeApp androidMain core platform tier, sibling 464 OPENER.
 * Cumulative §253-postscript count = 188 leaves with this commit.
 *
 * File-shape note: 153-line file — 1 @Composable actual fun
 * rememberNotificationPermissionRequester returning anonymous-object
 * NotificationPermissionRequester implementation. 1 private fun
 * hasPostNotificationPermission helper. NO actual class declaration (this
 * cluster234 uses FUNCTIONAL-EXPECT-ACTUAL pattern instead of CLASS-EXPECT-
 * ACTUAL pattern — first appearance in BEDROCK-PLATFORM-UTILITY tier).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - FUNCTIONAL-EXPECT-ACTUAL-INSTEAD-OF-CLASS-LIVE — @Composable expect
 *     fun returning interface impl, NOT expect class implementing interface.
 *     DISTINCT from cluster231 AppVersionProvider, cluster232 IntentLauncher,
 *     cluster233 ToastShower — all 3 prior clusters used expect-CLASS shape.
 *     NEW POSTURE feature at cluster234 — first FUNCTIONAL-EXPECT-ACTUAL
 *     classification in the §253 sweep register. Rationale: Composable
 *     factories MUST be @Composable functions (Compose-runtime constraint —
 *     classes cannot be @Composable). The interface declaration lives in
 *     :shared/commonMain (cluster176 prior sweep — Task #640); the factory
 *     lives in :composeApp/commonMain because @Composable requires the
 *     Compose runtime dependency that :shared deliberately avoids.
 *
 *   - FULFILLED-PORT-FULL-LIVE — Android actual is a FULL POST_NOTIFICATIONS
 *     runtime-permission integration (NOT no-op, NOT deferred). Uses
 *     ActivityResultContracts.RequestPermission via rememberLauncherFor
 *     ActivityResult, ContextCompat.checkSelfPermission probe gated by
 *     Build.VERSION.SDK_INT >= TIRAMISU, and Settings.ACTION_APPLICATION_
 *     DETAILS_SETTINGS deeplink for openAppSettings. MATCHES the canonical
 *     Android-permission-flow shape documented in the expect-decl KDoc at
 *     cluster155 sibling. PRESERVE — load-bearing as the Android-canonical
 *     notification-permission surface.
 *
 *   - TIRAMISU-API-LEVEL-GATE-LIVE — request() implementation branches on
 *     Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU. Pre-TIRAMISU
 *     short-circuit sets hasPermission.value = true directly (no runtime
 *     prompt existed before API 33). TIRAMISU+ launches the
 *     POST_NOTIFICATIONS permission contract. PRESERVE — load-bearing for
 *     the SDK-version-aware permission contract.
 *
 *   - CONTEXT-APPLICATIONCONTEXT-DEFENSIVE-LIVE — val app =
 *     context.applicationContext deref. SAME defensive idiom as cluster231-
 *     233 Android actuals. 4-CONSECUTIVE-CLUSTER Android-applicationContext-
 *     deref pattern at cluster231-234 — CONFIRMS the defensive idiom as a
 *     UNIFORM-ANDROID-CONVENTION across the BEDROCK-PLATFORM-UTILITY tier.
 *     PRESERVE — anti-leak contract (defends against Activity-scoped
 *     LocalContext.current being held by a remember block past activity
 *     teardown). NEW POSTURE feature at cluster234 — first 4-CONSECUTIVE-
 *     CLUSTER UNIFORM-CONVENTION classification.
 *
 *   - NO-LOGGER-DEPENDENCY-LIVE — NO Kermit logger import; NO log emission.
 *     The native ActivityResultContracts.RequestPermission flow IS the
 *     user-visible surface — log emission would be redundant noise (the
 *     user already SEES the system permission dialog). DISTINCT from
 *     cluster233 Android ToastShower NO-LOGGER (same reason — native UI
 *     primitive IS the surface). 2-CONSECUTIVE-CLUSTER Android-NO-LOGGER
 *     pattern at cluster233-234. PRESERVE — load-bearing diagnostic-
 *     parsimony posture for Android when native UI exists.
 *
 *   - ANONYMOUS-OBJECT-RETURN-PATTERN-3-AGREE-LIVE — actual fun returns
 *     anonymous-object NotificationPermissionRequester implementation via
 *     object : NotificationPermissionRequester { ... }. 3-AGREE with iOS
 *     sibling 465 plus Desktop sibling 466 on the anonymous-object-return
 *     pattern (all 3 actuals use the same shape). NEW POSTURE feature at
 *     cluster234 — first 3-AGREE-ANONYMOUS-OBJECT-RETURN classification
 *     in the §253 sweep register. Distinct from cluster231-233 which all
 *     used named-actual-class declarations.
 *
 *   - PRIVATE-HELPER-FN-LIVE — private fun hasPostNotificationPermission(
 *     context): Boolean lives outside the actual fun (file-level). The
 *     TIRAMISU-API-LEVEL-GATE plus checkSelfPermission probe IS extracted
 *     for INITIAL-STATE-COMPUTATION at the construction-time
 *     MutableStateFlow seed. DISTINCT from cluster231 AppVersionProvider
 *     Android-actual (no helper — direct BuildConfig read); DISTINCT from
 *     cluster232 IntentLauncher Android-actual (no helper — direct
 *     Intent.makeRestartActivityTask call). NEW POSTURE feature at
 *     cluster234 — first PRIVATE-FILE-LEVEL-HELPER-FN classification for a
 *     BEDROCK-PLATFORM-UTILITY actual.
 *
 *   - REMEMBER-LAUNCHER-FOR-ACTIVITY-RESULT-INTEGRATION-LIVE — uses
 *     rememberLauncherForActivityResult(contract =
 *     ActivityResultContracts.RequestPermission()) which scopes the
 *     launcher to the surrounding ComponentActivity's
 *     ActivityResultRegistry. The "granted" callback updates
 *     hasPermission.value reactively. PRESERVE — load-bearing for the
 *     Activity-lifecycle-aware permission-launch contract. DISTINCT from
 *     cluster231-233 Android actuals (none used Compose-Activity
 *     integration — they were pure-Context patterns).
 *
 *   - ALGORITHM-AXIS-1-AGREE-1-DIVERGE-ANDROID-OUTLIER-LIVE — Android uses
 *     REAL POST_NOTIFICATIONS runtime-permission flow; iOS plus Desktop use
 *     no-op-stub fallback (MutableStateFlow(true) + request() no-op). 2-
 *     AGREE-1-DIVERGE Android-outlier on algorithm-axis at cluster234.
 *     CONTINUES the cluster233 ALGORITHM-AXIS-1-DIVERGE Android-outlier
 *     pattern. The 2-AGREE iOS+Desktop no-op fallback IS the PLATFORM-
 *     LIMITATION-CONSEQUENCE (neither iOS UNUserNotificationCenter from
 *     Theme-screen scope NOR JVM AWT SystemTray gates notifications behind
 *     a per-app runtime prompt).
 *
 *   - STRUCTURAL-NO-OP-IMPLICITLY-GRANTED-LIVE — iOS+Desktop fallback IS
 *     STRUCTURAL-PERMANENT-PLATFORM-LIMITATION, NOT DEFERRED-NOT-PERMANENT.
 *     SAME posture as cluster233 ToastShower STRUCTURAL-NO-OP-LOG-FALLBACK.
 *     DISTINCT iOS-specific nuance: iOS DOES have a runtime permission
 *     prompt (UNUserNotificationCenter.requestAuthorizationWithOptions) but
 *     it is OWNED BY THE APP SHELL at launch, NOT this Composable factory.
 *     The "implicitly granted" stance models the Theme-screen perspective,
 *     not the iOS-platform perspective. NEW POSTURE feature at cluster234 —
 *     first STRUCTURAL-NO-OP-IMPLICITLY-GRANTED-WITH-OUT-OF-SCOPE-NATIVE-
 *     PROMPT classification.
 *
 *   - TWO-AXIS-COSALIGNED-CONTINUES-FROM-CLUSTER233-LIVE — Android outlier
 *     on algorithm + log axes (2-AXIS-COSALIGNED Android-outlier).
 *     DIFFERENT shape than cluster233 4-AXIS-COSALIGNED (which had ctor +
 *     algorithm + log + companion-object). cluster234 has NO companion-
 *     object axis (functional pattern), NO ctor axis (functional pattern) —
 *     only algorithm + log axes remain in the COSALIGNED register. RETURNS
 *     TO 2-AXIS-COSALIGNED shape from cluster226-229.
 *
 *   - CROSS-PACKAGE-DEPENDENCY-LIVE — imports: android.Manifest, android.
 *     content.Context+Intent, android.content.pm.PackageManager, android.
 *     net.Uri, android.os.Build, android.provider.Settings, androidx.
 *     activity.compose.rememberLauncherForActivityResult, androidx.
 *     activity.result.contract.ActivityResultContracts, androidx.compose.
 *     runtime.Composable+remember, androidx.compose.ui.platform.LocalContext,
 *     androidx.core.content.ContextCompat, kotlinx.coroutines.flow.
 *     {MutableStateFlow, StateFlow, asStateFlow}. LIVE — broadest dependency
 *     surface among cluster234 actuals. NO Kermit import (matches the NO-
 *     LOG-EMISSION contract). NO co.touchlab dependency on Android.
 *     PRESERVE — load-bearing for the Compose-Activity integration contract.
 *
 *   - WAVE-REGISTER-OPENS-FUNCTIONAL-EXPECT-ACTUAL-SUB-TIER — cluster234
 *     RememberNotificationPermissionRequester OPENS a NEW sub-tier within
 *     BEDROCK-PLATFORM-UTILITY: classes where the expect-decl is a
 *     @Composable function instead of an expect class. Candidates remaining
 *     in this sub-tier: RememberHideNavigationBarSideEffect (HideNavigation
 *     BarSideEffect.kt — composeApp/commonMain expect-decl swept at
 *     cluster155), WebViewHost (composeApp/commonMain expect-decl swept at
 *     cluster155), RememberFastScrollerState / RememberGestureExclusion
 *     siblings. Predicted posture for cluster235+: CONTINUE FUNCTIONAL-
 *     EXPECT-ACTUAL shape, CONTINUE @Composable Compose-runtime dependency,
 *     CONTINUE anonymous-object-return where the expect-decl returns an
 *     interface-typed handle.
 */

