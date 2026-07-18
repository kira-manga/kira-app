package me.manga.kira.presentation.about

import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [AboutViewModel] for the route adapter to perform once.
 *
 * Phase 7.x.about + Phase 7.x.about.whatsnewrow. Three variants — the two external-launch
 * paths from the original slice plus the in-app Whats-new nav added once the
 * `Screen.WhatsNewRework` foundation shipped (Phase 7.x.whatsnew, commit `e5d91b0`):
 *
 * - [OpenPlayStorePage] — route adapter invokes
 *   `IntentLauncher.openPlayStorePage(packageName)`. The VM emits this in response to
 *   [AboutIntent.OnOpenPlayStore]; the package id is pulled from the current
 *   [AboutState.packageName] at emit time so the effect is self-contained (the route
 *   adapter does NOT need to re-read state). Same pattern as the Reader's
 *   `OpenChapterInWebView(url)` effect — the trigger plus the single param the platform
 *   call needs.
 * - [OpenUrl] — route adapter invokes `IntentLauncher.openUrl(url)`. The VM passes through
 *   the URL from [AboutIntent.OnOpenUrl] verbatim — no validation, no normalisation. The
 *   legacy `IntentLauncher.openUrl()` actuals (Android `Intent.ACTION_VIEW` /
 *   iOS `UIApplication.openURL` / Desktop `Desktop.browse`) handle malformed URLs the same
 *   way the legacy About screen does today (fail silently or open a generic error in the
 *   system handler).
 * - [NavigateToWhatsNew] — route adapter invokes
 *   `navController.navigate(Screen.WhatsNewRework)`. The VM emits this in response to
 *   [AboutIntent.OnOpenWhatsNew]. Parameterless `data object` — the target route is the
 *   parameterless `Screen.WhatsNewRework` object, and the route adapter (an `:composeApp`-
 *   layer type) holds the `Screen` ADT and the `NavController` reference. Keeping
 *   `Screen` out of the effect payload preserves the `:presentation` → `:composeApp`
 *   layer-dependency direction (the route adapter knows its target; the VM doesn't).
 *
 * **Strict-MVI contract §17**: effects carry only the trigger — never rendering data. The
 * `packageName` and `url` here are the bare params the platform call needs, not
 * UI-rendering data. [NavigateToWhatsNew] carries nothing because the route adapter knows
 * the target destination. The :ui composable does NOT consume these effects directly; the
 * route adapter ([me.manga.kira.navigation.routes.AboutReworkScreenRoute]) collects
 * them and routes them through Koin-resolved
 * [me.manga.kira.core.platform.IntentLauncher] actuals + the parent
 * `NavController`.
 *
 * **Why not a ShowError effect** — the legacy `IntentLauncher` actuals are
 * structurally infallible (no exceptions thrown from `openUrl` / `openPlayStorePage` —
 * Android catches `ActivityNotFoundException` internally; iOS / Desktop ignore failures).
 * `NavController.navigate(Screen.X)` is also infallible (the target route is statically
 * registered in `App.kt`'s NavHost). The rework matches both postures: no error path
 * needed. If a future actual changes its failure contract (e.g., remote-URL validation
 * throws on malformed input), this sealed interface gains a `ShowError(error: AppError)`
 * variant — strict-MVI OCP §6.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster32.staleKdocSweep.cascade,
 * Task #488, 2026-05-28): one stale citation appears in the [OpenUrl]
 * member-list rationale above:
 *  - Line 23 ([OpenUrl] descriptor, "the same way the legacy About
 *    screen does today (fail silently or open a generic error in the
 *    system handler)"). STALE-SYMBOL-REFERENCE — Phase 9.x.settings_
 *    about.legacyui.retire (§354) DELETED the legacy `:composeApp/
 *    .../features/about/ui/screens/AboutScreen.kt` along with its 10
 *    sibling helpers as part of the 11-file orphan chain retirement.
 *    A recursive search of the legacy about folder for an
 *    AboutScreen.kt with the cited URL-handling behaviour returns NO
 *    MATCHES.
 *  Sibling cites at Line 21 ("legacy `IntentLauncher.openUrl()`
 *  actuals") and Line 42 ("the legacy `IntentLauncher` actuals are
 *  structurally infallible") are CLASSIFIED LIVE — the cite-target
 *  `IntentLauncher` survives on disk both in `:shared/.../core/
 *  platform/IntentLauncher.kt` (legacy facade still LIVE under the
 *  Phase 5.3 relocation) AND in `:platform/.../platform/intent/
 *  IntentLauncher.kt` (rework actuals — see Task #166); the "legacy"
 *  framing in the prose is the pre-rework naming convention but the
 *  symbol is NOT retired. HOWEVER — the rework `:ui` `AboutScreen`
 *  (same filename, different package: `me.manga.kira.ui.about.
 *  AboutScreen`) is LIVE as the canonical About surface backed by
 *  [AboutState] + [AboutViewModel] + this [AboutEffect] sealed
 *  interface; the URL-handling-behavioural-parity rationale (fail
 *  silently or open a generic error in the system handler) STANDS on
 *  its own merits past the §354 fulfilled landing as the LIVE rework
 *  realization (the cross-platform actuals continue to behave that
 *  way — Android `Intent.ACTION_VIEW` / iOS `UIApplication.openURL`
 *  / Desktop `Desktop.browse` all fail silently on malformed input).
 *  The [AboutEffect] sealed interface remains LIVE as the canonical
 *  About-screen effect ADT consumed by [AboutViewModel] + the rework
 *  `:ui` `AboutScreen`. Original §253-era prose preserved verbatim
 *  per the audit-trail-preservation convention — the citation is
 *  historical record of the design lineage including the URL-
 *  handling-behavioural-parity rationale that was subsequently
 *  fulfilled (legacy About screen retired) across §354.
 */
sealed interface AboutEffect : MviEffect {
    /** Route adapter should invoke `IntentLauncher.openPlayStorePage(packageName)`. */
    data class OpenPlayStorePage(
        val packageName: String,
    ) : AboutEffect

    /** Route adapter should request the platform-native in-app review flow. */
    data object RequestReview : AboutEffect

    /** Route adapter should invoke `IntentLauncher.openUrl(url)`. */
    data class OpenUrl(
        val url: String,
    ) : AboutEffect

    /**
     * Route adapter should invoke `navController.navigate(Screen.WhatsNewRework)`. Added by
     * Phase 7.x.about.whatsnewrow once Phase 7.x.whatsnew shipped the foundation. The route
     * adapter holds the `Screen` ADT reference + the parent `NavController`; the VM stays
     * free of `:composeApp`-layer types.
     */
    data object NavigateToWhatsNew : AboutEffect
}
