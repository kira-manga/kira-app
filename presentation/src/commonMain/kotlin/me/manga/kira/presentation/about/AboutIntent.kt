package me.manga.kira.presentation.about

import me.manga.kira.presentation.mvi.MviIntent

/**
 * Sealed Intent hierarchy for the rework About screen.
 *
 * Phase 7.x.about + Phase 7.x.about.whatsnewrow. Three variants, matching the legacy
 * screen's two external-launch paths plus the in-app Whats-new nav added by the follow-on
 * slice once `Screen.WhatsNewRework` foundation (Phase 7.x.whatsnew, commit `e5d91b0`)
 * unblocked the row:
 *
 * - [OnOpenPlayStore] — user tapped "Check for update" or "Rate our app". Both rows
 *   open the Play Store page for the running app's package id. The VM does not duplicate
 *   the two rows into separate intents because the action is identical
 *   ([AboutEffect.OpenPlayStorePage] with [AboutState.packageName]); the row label is a
 *   pure-display concern owned by [AboutScreen]. Same pattern as the legacy screen
 *   (`launcher.openPlayStorePage(versionProvider.packageName)` in two click handlers).
 * - [OnOpenUrl] — user tapped a row that opens an external URL (Privacy policy in this
 *   slice). Carries the target URL string; the VM emits [AboutEffect.OpenUrl] verbatim.
 *   The URL string is hardcoded by the :ui composable per legacy parity (the legacy
 *   `AboutScreen.kt` line 172 literal `"https://yamimanga.me/privacy"`) — the rework keeps
 *   the same value at the same call site. Future URL rows (e.g., source code link once it
 *   goes public) add to this same intent without growing the hierarchy.
 * - [OnOpenWhatsNew] — user tapped the "What's new" row added by Phase 7.x.about.whatsnewrow.
 *   The VM emits [AboutEffect.NavigateToWhatsNew] and the route adapter routes the
 *   in-app nav via `navController.navigate(Screen.WhatsNewRework)`. Parameterless: the
 *   target route is a parameterless `Screen.WhatsNewRework` object.
 *
 * **Why `OnOpenUrl(url: String)` instead of per-target intents like `OnOpenPrivacy`** —
 * URL-launching is a single side-effect class with a single parameter shape. Splitting into
 * `OnOpenPrivacy` / `OnOpenSourceCode` / `OnOpenChangelog` would just push the URL string
 * to a `when (intent)` block in `handle()` — same dispatch, more code. Same posture as the
 * Reader's `OnOpenInWebView(url: String)` from Phase 7.x.reader.modelayout.openwebview.
 *
 * **Why `OnOpenWhatsNew` as a per-target `data object` and NOT a generic
 * `OnOpenScreen(target: Screen)`** — a generic carrier would pull the `:composeApp`-layer
 * `Screen` ADT into `:presentation`, reversing the layer-dependency arrow. The other rework
 * slices that need in-app nav (Reader → next chapter, Details → reader entry) all use
 * per-target effects, not a generic carrier. Consistency with the established pattern wins;
 * adding per-row intents is cheap (one `data object` + one `when` arm) and OCP-friendly
 * (each new row gets explicit dispatch logic at every layer — no implicit "navigate-anywhere"
 * hatch).
 *
 * **Source-code / Social media rows still NOT modelled** — Source code was already disabled
 * in the legacy (no-op click + "soon" subtitle). Social media row needs
 * `material-icons-extended` (forbidden in the rework `:ui` module). Both lift in a follow-on
 * slice; the intent surface grows then with new variants — strict-MVI OCP §6.
 *
 * **Contract §6 OCP**: sealed interface — adding `OnOpenSourceCode` later doesn't modify
 * existing variants. The VM's `when (intent)` block grows one arm; nothing else changes.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster33.staleKdocSweep.cascade,
 * Task #489, 2026-05-28): four stale citations appear in this file's
 * class-level KDoc + member-list rationale above:
 *  - Line 8 (class-level KDoc, "matching the legacy screen's two
 *    external-launch paths").
 *  - Line 17 ([OnOpenPlayStore] descriptor, "Same pattern as the
 *    legacy screen (`launcher.openPlayStorePage(versionProvider.
 *    packageName)` in two click handlers)").
 *  - Lines 21-22 ([OnOpenUrl] descriptor, "the legacy `AboutScreen.
 *    kt` line 172 literal `\"https://yamimanga.me/privacy\"`").
 *  - Line 46 (member-list rationale, "Source code was already
 *    disabled in the legacy (no-op click + 'soon' subtitle)").
 *  All four classified as STALE-SYMBOL-REFERENCE — Phase 9.x.
 *  settings_about.legacyui.retire (§354) DELETED the legacy `:
 *  composeApp/.../features/about/ui/screens/AboutScreen.kt` along
 *  with its 10 sibling helpers as part of the 11-file orphan chain
 *  retirement. A recursive search of the legacy about folder for an
 *  AboutScreen.kt with the cited two-click-handler / line-172 /
 *  source-code-disabled call sites returns NO MATCHES. HOWEVER —
 *  the rework `:ui` `AboutScreen` (same filename, different package:
 *  `me.manga.kira.ui.about.AboutScreen`) is LIVE as the canonical
 *  About surface backed by [AboutState] + [AboutViewModel] + this
 *  [AboutIntent] sealed interface; all four architectural rationales
 *  STAND on their own merits past the §354 fulfilled landing as
 *  the LIVE rework realization: (a) two-external-launch-paths
 *  (Play-Store + URL) carry through unchanged in the rework `:ui`
 *  AboutScreen click handlers; (b) the single Play-Store-page
 *  invocation shape carries through (same packageName-from-state
 *  lookup); (c) the privacy-URL literal carries through verbatim
 *  in the rework `:ui` AboutScreen click handler; (d) the source-
 *  code-row disabled posture (no-op click + "soon" subtitle) carries
 *  through pending the follow-on source-code-row slice. The
 *  [AboutIntent] sealed interface remains LIVE as the canonical
 *  About-screen intent ADT consumed by [AboutViewModel] + the rework
 *  `:ui` `AboutScreen`. Original §253-era prose preserved verbatim
 *  per the audit-trail-preservation convention — the citations are
 *  historical record of the design lineage including the two-
 *  external-launch-paths / Play-Store-pattern / privacy-URL-literal
 *  / source-code-disabled rationales that were subsequently
 *  fulfilled (legacy about chain retired) across §354.
 */
sealed interface AboutIntent : MviIntent {

    /** User tapped "Check for update" or "Rate our app". */
    data object OnOpenPlayStore : AboutIntent

    /** User tapped a row that opens an external URL. Carries the target. */
    data class OnOpenUrl(val url: String) : AboutIntent

    /**
     * User tapped the "What's new" row. The VM emits [AboutEffect.NavigateToWhatsNew] and
     * the route adapter routes the in-app nav via `navController.navigate(Screen.WhatsNewRework)`.
     * Added by Phase 7.x.about.whatsnewrow once Phase 7.x.whatsnew shipped the
     * `Screen.WhatsNewRework` foundation.
     */
    data object OnOpenWhatsNew : AboutIntent
}
