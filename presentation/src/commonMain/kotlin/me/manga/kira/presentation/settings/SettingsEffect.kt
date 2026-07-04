package me.manga.kira.presentation.settings

import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [SettingsViewModel] for the view to perform once and forget.
 *
 * Phase 7.x.settings.foundation rework. Two variants today:
 *  - [NavigateTo] — VM-driven navigation to one of the 6 rework destinations.
 *  - [FeedbackResult] — the typed (unlocalized) terminal outcome of a feedback submission, which
 *    the `:ui` layer renders as a localized snackbar (with Retry on failure). Cache-clear /
 *    conversion feedback is no longer a snackbar effect: clearing re-emits the cache-size subtitle
 *    into state and the CBZ conversion outcome renders in the `CbzConversionDialog`.
 *
 * **Why navigation is an effect** (not a callback parameter to the composable): the
 * presentation layer can't reference `androidx.navigation.NavController` — that's a
 * `:composeApp` concern (the `:presentation` module's commonMain set doesn't depend on
 * the Compose-runtime navigation lib). The effect surface is the established MVI bridge for
 * one-shot view-side actions including nav. Same posture as
 * [me.manga.kira.presentation.reader.ReaderEffect.OpenInExternalBrowser] (also a "tell
 * the view to do something the VM can't" effect).
 *
 * **Why feedback is an effect** (not state): the snackbar a [FeedbackResult] drives is a transient
 * notification, not persistent state. Modelling it as state would require the VM to clear the field
 * after the snackbar dismisses — adding state-clearing intents and timing fragility. The effect
 * channel is fire-and-forget; the view's `SnackbarHostState.showSnackbar(...)` handles the
 * dismiss timer. Same posture as [me.manga.kira.presentation.complaint.ComplaintEffect.
 * ShowSuccessMessage] / [.ShowErrorMessage] and
 * [me.manga.kira.presentation.language.LanguageEffect.RequestLanguageSubmitted].
 *
 * Contract §6 OCP: a future settings slice can append e.g. `LaunchInBrowser(url: String)`
 * (for the "Help" row if it routes externally) or `RestartApp` (if a future "reset settings"
 * variant lands) without touching the VM's base class.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster16.staleKdocSweep.cascade,
 * Task #472, 2026-05-28): one stale citation into the §354-retired
 * legacy `:composeApp/.../features/settings/ui/screens/SettingsScreen.kt`
 * appears above:
 *  - Lines 40-42 (NavigateTo admin-branch detail): "same posture as the
 *    legacy `SettingsScreen.kt:272-278` branch. The VM doesn't know
 *    about Admin state; that's correct (admin role is a `:composeApp`
 *    concern)". The legacy `:composeApp/.../features/settings/ui/screens/
 *    SettingsScreen.kt` was retired in Phase 9.x.settings_about.legacyui.
 *    retire (§354 sweep, commit `5cc42d2` "(1/2): delete 5 orphan
 *    settings UI files"); verified by filesystem check returning zero
 *    hits. The §301 settings.swap already re-pointed `Screen.Settings`
 *    to the rework SettingsScreen, so the legacy-branch-citation framing
 *    is historical — but the admin-gate-at-`:composeApp` separation-of-
 *    concerns rationale stands on its own merits past the retire (the
 *    rework `SettingsReworkScreenRoute` adapter consults `Admin.isAdmin`
 *    when handling `SettingsEffect.NavigateTo(COMPLAINT)`, choosing
 *    between `Screen.ComplaintAdminRework` / `Screen.ComplaintRework`).
 * The two-variant surface (`NavigateTo` for VM-driven nav +
 * `FeedbackResult` for the typed feedback outcome) + the rationales for
 * each (no `NavController` dep in `:presentation` + transient-not-state-
 * fragility-free) all stand on their own merits past the §354 retire. The rework
 * SettingsEffect remains LIVE as the canonical effect surface for the
 * rework SettingsViewModel, documented inline above and via the §§253
 * + §301 KDocs. Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citation is historical
 * record of the design lineage including the legacy-branch-parity
 * reference that was subsequently retired.
 */
sealed interface SettingsEffect : MviEffect {

    /**
     * The view should navigate to [destination]. The `:composeApp` route adapter's effect
     * collector maps each [SettingsDestination] enum value to a concrete `Screen.<X>Rework`
     * and calls `navController.navigate(...)`.
     *
     * The adapter is also free to consult `Admin.isAdmin` to choose between
     * `Screen.ComplaintAdminRework` / `Screen.ComplaintRework` for [SettingsDestination.
     * COMPLAINT] — same posture as the legacy `SettingsScreen.kt:272-278` branch. The VM
     * doesn't know about Admin state; that's correct (admin role is a `:composeApp` concern).
     */
    data class NavigateTo(val destination: SettingsDestination) : SettingsEffect

    /**
     * The feedback-submission attempt finished (GAP-SET-13). [success] distinguishes the terminal
     * outcome — payload-free beyond the flag (backlog L8: the former `cause` string was never
     * `null` on success.
     *
     * Carried as a distinct, *unlocalized* signal (rather than a pre-built snackbar string)
     * so the `:ui` layer can resolve the native localized success / failure strings via
     * `stringResource`, show the error snackbar with a **Retry** action + **Long** duration, and
     * re-dispatch [SettingsIntent.OnOpenFeedbackDialog] when Retry is tapped — all view concerns the
     * VM (which has no `Res.string` access) must not own. The cache-clear / conversion paths carry
     * no snackbar effect at all (cache re-emits the size subtitle into state; conversion renders in
     * the `CbzConversionDialog`); only the feedback path routes through this typed result so its copy
     * is localizable and its snackbar can offer Retry.
     */
    data class FeedbackResult(val success: Boolean) : SettingsEffect

    // GAP-SET-16 — the prior `ConversionResult` effect (a typed snackbar signal for the CBZ
    // "compress existing downloads" terminal outcome) was removed once the domain progress Flow
    // landed: the terminal Success / Stopped / Error states (with converted/remaining counts +
    // current item) now render in the `:ui` `CbzConversionDialog` driven by
    // [me.manga.kira.domain.usecase.settings.ObserveCbzConversionUseCase], matching native
    // which surfaces the outcome through the dialog alone (no snackbar).
}
