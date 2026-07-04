package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.ui.welcome.WelcomeScreen

/**
 * Route host for the welcome (first-launch) screen — **Phase 7.x.welcome** (Task #306).
 *
 * **What changed in this slice**: this adapter no longer renders the legacy
 * `:composeApp/.../presentation/features/onboarding/welcome/WelcomeScreen.kt` composable
 * (which used the Lottie-replacement [me.manga.kira.presentation.features.onboarding.
 * components.AnimatedBackground] gradient sweep). It now renders the architecture-rework
 * [me.manga.kira.ui.welcome.WelcomeScreen] composable, which intentionally omits the
 * decorative animated background (matches §122 / §138 precedent — purely cosmetic, no
 * semantic value) and uses inline literal strings for the visible copy (matches every
 * prior rework `:ui` screen; Phase 10 i18n lift unifies legacy + rework in one pass).
 *
 * **Onboarding chain preserved verbatim**: **Welcome (rework via this slice)** → Theme
 * (rework via §138) → Sources (rework via §140) → `Screen.RepoSettings(isFirstOpen = true)`
 * (rework via §124, with Finish → Library + `first_launch = false` flip + back-stack
 * clear) → Library. The nav target on Get-Started (`Screen.Theme`) is preserved verbatim.
 *
 * **Stateless — no VM**: Welcome has no state and no business logic. No Koin module is
 * needed; no `:domain`/`:data`/`:presentation` layers were added. Adding empty layers
 * would be premature abstraction. Same posture as the pre-swap adapter (which also had
 * no VM dependency).
 *
 * **`backStackEntry` unused but preserved**: same posture as the pre-swap adapter. Compose-
 * MP's `composable<Screen.Welcome>(...)` block always hands one to its content lambda even
 * when the route is parameter-less; the signature parity simplifies the App.kt nav graph
 * dispatch.
 *
 * **Banned features**: No `!!`, `Any`, `lateinit`, `Thread` in this file. The adapter is a
 * single Compose call into the rework `:ui` screen.
 *
 * **No load-bearing fix touched**: This file does NOT touch the Coil ImageLoader, the
 * Reader's per-request listener, the Reader's decoder hints, the OkHttp interceptor, or
 * any of the prior load-bearing image-quality posture (Welcome has no images).
 *
 * **Legacy file disposition post-swap**:
 *  - `composeApp/.../presentation/features/onboarding/welcome/WelcomeScreen.kt` (legacy
 *    composable) — no longer user-reachable through this adapter. Stays on disk until a
 *    future `Phase 9.x` cleanup sweep retires it alongside other retired legacy screens.
 *  - `composeApp/.../presentation/features/onboarding/components/AnimatedBackground.kt`
 *    — still on the classpath (referenced only by the legacy `WelcomeScreen.kt` and the
 *    legacy `ThemeSelectionScreen.kt`, both no-longer-reachable post-§138/§140). Phase 9.x
 *    cleanup retires it alongside the legacy screens.
 *
 * @param navController parent nav controller — used to navigate to the next onboarding
 *                      step (Theme picker, rework via §138) on Get-Started.
 * @param backStackEntry passed through for parity with sibling route-adapter signatures
 *                       (unused — the rework Welcome screen is stateless, no VM scope to
 *                       resolve).
 *
 * **Audit-trail postscript** (Phase 9.x.welcome.staleKdocSweep.cascade,
 * Task #457, 2026-05-28): two stale citations into the §307-retired legacy
 * onboarding sources appear above, plus a now-fulfilled prediction:
 *  - Lines 44-46 (Legacy file disposition bullet 1): "no longer user-reachable
 *    through this adapter. Stays on disk until a future `Phase 9.x` cleanup
 *    sweep retires it alongside other retired legacy screens" — referring to
 *    `composeApp/.../presentation/features/onboarding/welcome/WelcomeScreen.kt`.
 *  - Lines 47-50 (Legacy file disposition bullet 2): "still on the classpath
 *    (referenced only by the legacy `WelcomeScreen.kt` and the legacy
 *    `ThemeSelectionScreen.kt`, both no-longer-reachable post-§138/§140).
 *    Phase 9.x cleanup retires it alongside the legacy screens" — referring to
 *    `composeApp/.../presentation/features/onboarding/components/AnimatedBackground.kt`.
 * Both the legacy `presentation/features/onboarding/welcome/WelcomeScreen.kt`
 * and the legacy `presentation/features/onboarding/components/AnimatedBackground.kt`
 * (along with three sibling onboarding files) were retired in Phase
 * 9.x.onboarding.legacy_retire (§307 sweep, commit `6c83364` "delete 5
 * unreachable legacy onboarding files"); verified by a filesystem check
 * returning zero hits for both paths. The forecast made in the original
 * disposition paragraphs ("Phase 9.x cleanup retires it") has been
 * fulfilled — the rework adapter is now the sole renderer of the Welcome
 * step. The stateless-no-VM rationale stands on its own merits — Welcome
 * has no state, no business logic, and one nav callback; adding empty
 * `:presentation`/`:domain` layers would be premature abstraction
 * independent of which legacy file originally established the
 * Lottie-replacement AnimatedBackground precedent. Original §253-era prose
 * preserved verbatim per the audit-trail-preservation convention — the
 * citations are historical record of the design lineage; the route adapter
 * continues to bridge the onboarding step-1 entry correctly through the
 * legacy retire.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster39.staleKdocSweep.cascade,
 * Task #495, 2026-05-28): one stale citation beyond those enumerated in
 * the cluster `welcome` (§457) postscript above appears in this file:
 *  - Lines 13-15 ("What changed in this slice" paragraph): the Dokka
 *    link `[me.manga.kira.presentation.features.onboarding.components.
 *    AnimatedBackground]` framed as the Lottie-replacement gradient
 *    sweep cited by the now-retired legacy
 *    `:composeApp/.../presentation/features/onboarding/welcome/
 *    WelcomeScreen.kt`.
 *  Classified as STALE-SYMBOL-REFERENCE — Phase 9.x.onboarding.legacy_retire
 *  (§307, commit `6c83364` "delete 5 unreachable legacy onboarding files")
 *  DELETED the legacy `AnimatedBackground.kt` along with 4 sibling legacy
 *  onboarding files as a cascade-orphan-retire chain (re-verified by §457
 *  + §458 + §465 + cluster36 + cluster37 + cluster38 prior sweeps — a
 *  recursive Glob for `AnimatedBackground.kt` returns NO MATCHES). The
 *  Dokka `[fully.qualified.symbol]` link syntax is now broken — IDE
 *  resolution + Dokka HTML generation both fall through to a no-target.
 *  The §457 postscript above enumerated only the L44-50 disposition
 *  bullets (legacy WelcomeScreen.kt + AnimatedBackground.kt forecast-
 *  fulfilled prose) and missed this L13-15 Dokka link enumeration. The
 *  bare `AnimatedBackground` symbol survives only as documentation
 *  prose in sibling theme / sources / library KDocs + project
 *  documentation Markdown — the Kotlin source class itself is retired.
 *  HOWEVER — the architectural rationale of the citation STANDS on its
 *  own merits past the §307 fulfilled landing as a LIVE design-lineage
 *  record: the L13-15 paragraph describes the historical lineage of
 *  the rework Welcome screen ("It now renders the architecture-rework
 *  [me.manga.kira.ui.welcome.WelcomeScreen] composable, which
 *  intentionally omits the decorative animated background") — the
 *  intentional omission is the LIVE design choice (matches the §122 /
 *  §138 precedent of dropping purely-cosmetic decorative chrome with
 *  no semantic value), and the cited legacy file's retirement under
 *  §307 retroactively validates the "intentionally omits" framing
 *  (there is no longer a competing legacy renderer to omit *vs*).
 *  Original Phase 7.x.welcome-era prose preserved verbatim per the
 *  audit-trail-preservation convention — the citation is historical
 *  record of the design lineage including the §307-retired
 *  AnimatedBackground precedent that originally established the
 *  Lottie-to-Compose-primitive substitution pattern.
 */
@Composable
fun WelcomeScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    WelcomeScreen(
        onGetStarted = {
            navController.safeNavigate(Screen.Theme)
        },
    )
}
