package me.manga.kira.core.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Android actual — hides the navigation bar for the duration of the composition.
 *
 * Body is the native pre-KMP `HideSystemBars()` implementation
 * (`yami-manga-apk-main/.../ReaderScreen.kt:768-786`) ported verbatim. The Activity is resolved
 * via the standard `LocalContext` → `Context.findActivity()` walk (`Context` can be a
 * `ContextWrapper` chain ending in the Activity when used from a Compose host inside a
 * `ComponentActivity`).
 *
 * `WindowCompat.getInsetsController` is the back-compat aware way to obtain a
 * `WindowInsetsControllerCompat` — it picks the framework `WindowInsetsController` on API 30+
 * and the support-library `WindowInsetsControllerCompat` shim below that, so we don't need an
 * SDK version branch here.
 *
 * Defensive fallback: if no Activity is reachable from the context chain, the side-effect
 * becomes a no-op (`return@DisposableEffect onDispose {}` is *not* sufficient on its own — the
 * Kotlin shape used here mirrors the native code's `?: return@DisposableEffect onDispose {}`).
 * In practice every Compose host inside this app is an `Activity`, so the fallback is
 * defence-in-depth, not an expected branch.
 */
@Composable
actual fun HideNavigationBarSideEffect() {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
            ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        insetsController.hide(WindowInsetsCompat.Type.navigationBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT

        onDispose {
            insetsController.show(WindowInsetsCompat.Type.navigationBars())
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster163.staleKdocSweep.cascade,
 * Task #619, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-twenty-third sibling of the cluster57-162
 * sweep — OPENING file of the wave-35 HideNavigationBarSideEffect 3-actual
 * fan batch; OPENS HideNavigationBarSideEffect actuals tier 1/3):
 *  (a) "Android-actual-hides-the-navigation-bar-for-the-duration-of-the-
 *  composition + Body-is-the-native-pre-KMP-HideSystemBars-implementation-
 *  yami-manga-apk-main-ReaderScreen.kt-768-786-ported-verbatim + The-Activity-
 *  is-resolved-via-the-standard-LocalContext-Context.findActivity-walk +
 *  Context-can-be-a-ContextWrapper-chain-ending-in-the-Activity-when-used-
 *  from-a-Compose-host-inside-a-ComponentActivity + WindowCompat.
 *  getInsetsController-is-the-back-compat-aware-way-to-obtain-a-
 *  WindowInsetsControllerCompat-it-picks-the-framework-
 *  WindowInsetsController-on-API-30-plus-and-the-support-library-shim-below-
 *  that-so-we-don-t-need-an-SDK-version-branch-here + Defensive-fallback-if-
 *  no-Activity-is-reachable-from-the-context-chain-the-side-effect-becomes-
 *  a-no-op + In-practice-every-Compose-host-inside-this-app-is-an-Activity-
 *  so-the-fallback-is-defence-in-depth-not-an-expected-branch" — LIVE-NOT-
 *  STALE + FULFILLED-PORT (the native pre-KMP HideSystemBars() body from
 *  upstream ReaderScreen.kt:768-786 has shipped on Android as advertised —
 *  no forecast remaining; the port is complete). Verified: @Composable
 *  actual fun HideNavigationBarSideEffect() shipped — LocalContext.current
 *  → DisposableEffect(Unit) → context.findActivity()?.window ?:
 *  return@DisposableEffect onDispose {} fallback → WindowCompat.
 *  getInsetsController(window, window.decorView) → insetsController.hide
 *  (WindowInsetsCompat.Type.navigationBars()) + systemBarsBehavior =
 *  BEHAVIOR_DEFAULT → onDispose insetsController.show(navigationBars()).
 *  Private extension fun Context.findActivity() walks the ContextWrapper
 *  chain until it finds an Activity or returns null. The "native port
 *  verbatim" claim honored — code structure matches upstream
 *  HideSystemBars() including the `?: return@DisposableEffect onDispose {}`
 *  defensive shape. The "no SDK version branch needed because
 *  WindowCompat.getInsetsController handles API 30+ vs below" rationale
 *  honored — no Build.VERSION.SDK_INT check in body. Consumed by Reader
 *  rework screen (cluster9-sibling §367 — Phase 7.x.reader.controls slice)
 *  via the HideNavigationBarSideEffect expect-decl in commonMain. Sibling
 *  actuals: iOS (interior-sibling per HideNavigationBarSideEffect.ios.kt —
 *  intentionally empty no-op, home indicator is not analogous to Android
 *  navigation bar) + Desktop (closing-sibling per HideNavigationBarSideEffect.
 *  desktop.kt — intentionally empty no-op, Win32/AppKit/Wayland/X11 have no
 *  Android-style nav bar). OPENING FILE of the cluster163
 *  HideNavigationBarSideEffect 3-actual fan batch (1 of 3). One
 *  classification. Original Phase 7.x.reader.controls Android-port prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
