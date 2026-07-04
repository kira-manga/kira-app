package me.manga.kira.presentation.language

import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [LanguageViewModel] for the view to perform once and forget.
 *
 * Phase 7.x.language rework foundation + Phase 7.x.language.request extension.
 *
 * **Foundation slice** (Phase 7.x.language) declared this as an empty sealed interface — the
 * picker's language-selection action propagates via the upstream preference flow re-emit
 * (no view-side trigger needed), and there was no Request-Language flow at the time. The
 * empty-sealed-interface design was deliberate (OCP-friendly extensibility hook).
 *
 * **Phase 7.x.language.request** appends two variants. Both fire from
 * [LanguageIntent.OnSubmitRequest]'s coroutine completion in the VM. The `:ui` layer collects
 * [LanguageViewModel.effects] via `LaunchedEffect` and dispatches to a `SnackbarHostState`.
 *
 * Why **effects** and not state-flag flips? Snackbars are intrinsically one-shot:
 *  - State-flag posture would require an additional "snackbarShown" flag to know whether the
 *    user has seen the snackbar, plus an `OnSnackbarShown` intent to clear it. Three new state
 *    fields + one new intent. Effects: zero state fields, no clear-up intent.
 *  - The Channel-backed effect flow has UNLIMITED capacity — emissions survive transient view
 *    detachment (config change, navigation away/back). State flags would also survive but
 *    require manual reset, racing with rotation.
 *  - Same posture as the Reader slice's `ReaderEffect.OpenWebView` (one-shot URL launch).
 *
 * Contract §6 SRP: each variant signals ONE event.
 *
 * Contract §6 OCP: foundation declared the interface for exactly this kind of append.
 *
 * Why **`data object`** (not `data class`): the snackbar text is i18n-captured in the `:ui`
 * layer (e.g., `stringResource(Res.string.request_submitted_successfully)`). The effect
 * carries no payload — it signals the EVENT, not the MESSAGE. A `data object` is the
 * canonical Kotlin idiom for a payload-less singleton signal.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster32.staleKdocSweep.cascade,
 * Task #488, 2026-05-28): one stale citation appears in the
 * [RequestFailed] member KDoc below:
 *  - Line 83 ([RequestFailed] KDoc, "The legacy screen matches this
 *    posture (one error snackbar for all failure modes)"). STALE-
 *    SYMBOL-REFERENCE — Phase 9.x.language.retire (§350) DELETED the
 *    legacy `:shared` `LanguageScreen` along with its hosting
 *    `LanguageViewModel` + `LanguageOption` supporting types as a
 *    3-symbol orphan-retire chain. A recursive search of the legacy
 *    language folder for a `LanguageScreen.kt` with a single-error-
 *    snackbar posture returns NO MATCHES. HOWEVER — the rework `:ui`
 *    `LanguageScreen` (same filename, different package:
 *    `me.manga.kira.ui.language.LanguageScreen`) is LIVE as the
 *    canonical Language-selection surface backed by [LanguageState] +
 *    [LanguageViewModel] + this [LanguageEffect] sealed interface;
 *    the single-error-snackbar posture (one user-visible message for
 *    all failure modes, no per-cause discrimination) STANDS on its
 *    own merits past the §350 fulfilled landing as the LIVE rework
 *    realization. The [LanguageEffect] sealed interface remains LIVE
 *    as the canonical Language-screen effect ADT consumed by
 *    [LanguageViewModel] + the rework `:ui` `LanguageScreen`.
 *    Original §253-era prose preserved verbatim per the audit-trail-
 *    preservation convention — the citation is historical record of
 *    the design lineage including the single-error-snackbar posture
 *    that was subsequently fulfilled (legacy language chain retired)
 *    across §350.
 */
sealed interface LanguageEffect : MviEffect {

    /**
     * The user's Request-Language submission committed to the remote complaint store. The `:ui`
     * layer shows a success snackbar ("Request submitted successfully" or its localized
     * equivalent) and dismisses the dialog.
     *
     * The VM emits this AFTER updating state (dialog hidden, text cleared, submitting=false)
     * so the snackbar appears on the underlying picker, not the open dialog.
     */
    data object RequestSubmitted : LanguageEffect

    /**
     * The user's Request-Language submission failed (validation, network, Firestore, etc.).
     * The `:ui` layer shows an error snackbar ("Request failed" or its localized equivalent)
     * with a Retry action label. The dialog stays open with the typed text preserved so the
     * user can edit and resubmit.
     *
     * The failure cause is not surfaced — all failures map to one user-visible message. The
     * legacy screen matches this posture (one error snackbar for all failure modes).
     */
    data object RequestFailed : LanguageEffect
}
