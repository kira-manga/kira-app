package me.manga.kira.presentation.sources

import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [SourcesViewModel] for the view to perform once and forget.
 *
 * Phase 7.x.sources rework — the foundation slice landed this as an empty sealed interface
 * (no effects at the time; toggles propagate via the upstream Room flow re-emit).
 *
 * **Phase 7.x.sources.complaint extension** (per §84.8's named OCP-extension hook): the
 * "Request adding source" dialog needs success / failure feedback, surfaced as the two payload-
 * light [RequestSubmitted] / [RequestFailed] events (NP Phase 2 GAP-SRC-02/03 i18n lift — the
 * `:ui` layer resolves the localized snackbar copy via `stringResource`). Same posture as
 * [me.manga.kira.presentation.language.LanguageEffect.RequestSubmitted]
 * (Phase 7.x.language.request) — a transient notification, not persistent state. Modelling
 * submission outcome as state would require state-clearing intents and timing fragility; the
 * effect channel is fire-and-forget.
 *
 * Sources remains terminal (no outbound navigation), so no `NavigateTo` variant is needed.
 *
 * Contract §6 OCP: a future `Phase 7.x.sources.onboardingfinish` slice can append
 * `NavigateToLibrary` (or a `Finished` no-payload variant) here without touching the VM's
 * base-class signature.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster108.staleKdocSweep.cascade,
 * Task #564, 2026-05-28): the file-scope effect-surface manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-eighth sibling of the cluster57-107 sweep — opens the
 * wave-9 `:presentation/sources/` batch alongside SourcesViewModel.kt):
 *  (a) "Foundation slice landed this as an empty sealed interface (no
 *  effects at the time); Phase 7.x.sources.complaint extension adds the
 *  submission-feedback effects" — the sealed interface now declares two
 *  variants (the payload-less `RequestSubmitted` data object + the
 *  `RequestFailed(body: String)` data class), the NP Phase 2 GAP-SRC-02/03
 *  i18n lift having replaced the original single `ShowSnackbar(message)`
 *  variant; foundation-to-complaint append posture preserved.
 *  (b) "Same posture as LanguageEffect.RequestSubmitted — transient
 *  notification not persistent state" — LIVE-NOT-STALE. LanguageEffect.
 *  RequestSubmitted verified at cluster106 sibling sweep (Task #562).
 *  Effect channel fire-and-forget posture preserved.
 *  (c) "Sources remains terminal (no outbound navigation), so no
 *  NavigateTo variant is needed" — LIVE-NOT-STALE. The interface confirms
 *  zero NavigateTo variants; SourcesViewModel.kt's handle emits ONLY
 *  RequestSubmitted / RequestFailed (via handleSubmitComplaint) — no
 *  `emit(SourcesEffect.NavigateTo`-prefixed) calls anywhere.
 *  (d) "Contract §6 OCP: a future Phase 7.x.sources.onboardingfinish
 *  slice can append NavigateToLibrary (or a Finished no-payload variant)"
 *  — FORECAST-NOT-YET-FULFILLED. The peer Phase 7.x.sources.onboarding-
 *  seed HAS landed (SourcesViewModel.kt L116-118 OnSeedDefaultLanguage
 *  handler realized; see SourcesIntent.OnSeedDefaultLanguage KDoc cross-
 *  ref at SourcesViewModel.kt L57-62) but the onboardingfinish forecast
 *  (a NavigateToLibrary / Finished outbound nav variant) remains unbuilt
 *  — the interface declares ONLY RequestSubmitted / RequestFailed.
 *  Forecast posture preserved verbatim for the future slice's landing.
 *  Four classifications STAND on their own merits as a faithful
 *  SourcesEffect surface manifest. Original Phase 7.x.sources-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
sealed interface SourcesEffect : MviEffect {

    /**
     * The Request-Source submission committed successfully. The `:ui` layer shows a success
     * snackbar resolved via `stringResource(Res.string.request_submitted_successfully)` and
     * dismisses the dialog.
     *
     * **NP Phase 2 (GAP-SRC-02) i18n lift**: this replaces the former
     * `ShowSnackbar(message: String)` variant whose message was an English literal built
     * VM-side, which bypassed the Arabic locale. The effect now signals the EVENT, not the
     * MESSAGE — the snackbar copy is i18n-captured in `:ui` via `stringResource`, matching the
     * payload-less [me.manga.kira.presentation.language.LanguageEffect.RequestSubmitted]
     * posture. `data object` is the canonical Kotlin idiom for a payload-less singleton signal.
     */
    data object RequestSubmitted : SourcesEffect

    /**
     * The Request-Source submission failed. The `:ui` layer shows an error snackbar resolved via
     * `stringResource(Res.string.request_failed)` with a "Retry" action label
     * (`stringResource(Res.string.retry)`) and Long duration; tapping Retry re-dispatches
     * [me.manga.kira.presentation.sources.SourcesIntent.OnSubmitComplaint] with the preserved
     * [body]. The dialog stays open with the typed text preserved so the user can edit + resubmit.
     *
     * **NP Phase 2 (GAP-SRC-02 + GAP-SRC-03)**: carries the user-typed [body] so the `:ui`
     * Retry action can re-attempt the submission without re-prompting. The failure cause is not
     * surfaced — all failures map to one localized user-visible message, matching the legacy
     * `RepoSettingsScreen.kt:178-209` onError posture (`R.string.request_failed` + Retry action).
     */
    data class RequestFailed(val body: String) : SourcesEffect
}
