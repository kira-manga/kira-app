package me.manga.kira.presentation.language

import me.manga.kira.presentation.mvi.MviIntent

/**
 * User actions submitted from the language picker.
 *
 * Phase 7.x.language rework foundation + Phase 7.x.language.request extension. Sealed so the
 * [LanguageViewModel.handle] `when` is exhaustive; adding a new action requires adding a new
 * subclass (OCP — compile-time enforcement that the reducer handles every case).
 *
 * **Foundation variant** (Phase 7.x.language):
 *  - [OnSelectLanguage] — user tapped a language row in the picker.
 *
 * **Request-Language variants** (Phase 7.x.language.request — append-only OCP extension):
 *  - [OnOpenRequestDialog] — user tapped the bottom "Request a language" row.
 *  - [OnDismissRequestDialog] — user tapped Cancel/scrim on the FeedbackDialog.
 *  - [OnRequestTextChange] — user typed in the dialog's TextField (per keystroke).
 *  - [OnSubmitRequest] — user tapped Send on the dialog.
 *
 * No navigation intents (the rework language route is terminal; the back arrow is owned by the
 * route adapter's [androidx.activity.compose.BackHandler]/TopAppBar nav-icon, not the VM).
 * No `OnEnter` — the VM's `init {}` collector handles initial subscription.
 *
 * Contract §6 OCP: the foundation slice declared this interface specifically to allow this
 * append. Each new variant lands here without modifying [OnSelectLanguage]; the VM's
 * exhaustive `when` flags missing branches at compile time. Same OCP-friendly posture as
 * [me.manga.kira.presentation.theme.ThemeIntent].
 *
 * Contract §6 ISP: each variant carries only the minimal payload it needs.
 * [OnRequestTextChange] carries the new text; the rest carry nothing. The `data object` choice
 * for payload-less variants matches the rework convention (vs `data class` with empty primary
 * constructor) — `data object` produces a singleton with proper `equals`/`hashCode`/`toString`
 * for free, ideal for "this happened" signals.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster106.staleKdocSweep.cascade,
 * Task #562, 2026-05-28): the file-scope intent-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-sixth sibling of the cluster57-105 sweep —
 * opens the wave-9 `:presentation/language/` batch alongside Language-
 * State.kt plus LanguageViewModel.kt):
 *  (a) "Phase 7.x.language rework foundation plus Phase 7.x.language.
 *  request extension" — LIVE-NOT-STALE. Recursive count of variants
 *  L36-94 confirms 5 sealed variants exactly: 1 foundation (OnSelect-
 *  Language) plus 4 request (OnOpenRequestDialog, OnDismissRequest-
 *  Dialog, OnRequestTextChange, OnSubmitRequest).
 *  (b) "Foundation variant — OnSelectLanguage tap with use-case-driven
 *  persistence" — LIVE-NOT-STALE. L50 `data class OnSelectLanguage(val
 *  code: String)` LIVE; LanguageViewModel.kt L111-113 `handle` branch
 *  launches fire-and-forget `setLanguage(intent.code)` per the OCP
 *  foundation-slice contract.
 *  (c) "Request-Language variants — append-only OCP extension" — LIVE-
 *  NOT-STALE. L60/L70/L77/L93 four variants LIVE; LanguageViewModel.kt
 *  L114-149 four `handle` branches LIVE; the foundation slice's KDoc
 *  predicted these as "sibling additions" — the .request slice (§250)
 *  is exactly that, no rewrites to OnSelectLanguage required.
 *  (d) "No navigation intents (rework language route is terminal; back
 *  arrow owned by route adapter) plus no `OnEnter` (init collector)" —
 *  LIVE-NOT-STALE. Sealed surface has zero navigation variants;
 *  LanguageViewModel.kt L101-107 init block hosts the upstream
 *  `observeSelectedLanguage` collector; no `OnEnter` reducer branch
 *  exists.
 *  (e) "Contract §6 OCP plus ISP — `data object` for payload-less
 *  variants plus `data class` for payload variants" — LIVE-NOT-STALE.
 *  L50 plus L77 use `data class` (carry String payload); L60/L70/L93
 *  use `data object` (payload-less); the data-modifier-vs-plain-object
 *  posture preserved per the rework MVI convention. Peer cross-ref to
 *  [ThemeIntent] OCP-friendly posture still holds (verified at
 *  cluster29 sweep Task #485).
 *  Five classifications STAND on their own merits as a faithful
 *  LanguageIntent surface manifest. Original Phase 7.x.language-era
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
sealed interface LanguageIntent : MviIntent {

    /**
     * User tapped a language row in the picker. The VM invokes
     * [me.manga.kira.domain.usecase.language.SetLanguageUseCase] in a coroutine; the upstream
     * `observeSelectedLanguageCode()` flow re-emits with the new code once the legacy
     * `DataStoreHelper.languageFlow` write commits, and the `:data` impl also fires
     * `applyApplicationLocale(code)` as the platform locale-switch side effect (Android-only;
     * iOS/Desktop are no-op per `LocaleSwitcher.kt`).
     *
     * The user can re-tap the currently-selected row — the use case writes the same value
     * twice, which is a no-op at the DataStore level (Preferences-DataStore `edit` short-circuits
     * on equal values). No idempotence guard is needed in the reducer.
     */
    data class OnSelectLanguage(val code: String) : LanguageIntent

    /**
     * User tapped the bottom "Request a language" row. VM opens the FeedbackDialog by setting
     * `requestDialogVisible = true` and clearing `requestText` + `requestSubmitting` to their
     * defaults (so a re-open starts fresh — no stale text from a previous attempt).
     *
     * Idempotent: re-tapping while the dialog is already open is harmless (the state mutation
     * is a no-op because the flags are already at their target values).
     */
    data object OnOpenRequestDialog : LanguageIntent

    /**
     * User dismissed the FeedbackDialog (Cancel button or scrim tap). VM sets
     * `requestDialogVisible = false`. Does NOT clear `requestSubmitting` — if an in-flight
     * submission completes after dismissal, the resulting effect (success/failure snackbar)
     * still shows on the underlying screen.
     *
     * Idempotent: dismissing an already-closed dialog is a no-op state mutation.
     */
    data object OnDismissRequestDialog : LanguageIntent

    /**
     * User typed in the dialog's TextField. [text] is the new full value (TextField's
     * `onValueChange` callback gives the entire new string, not the delta). VM sets
     * `requestText = text`.
     */
    data class OnRequestTextChange(val text: String) : LanguageIntent

    /**
     * User tapped Send on the FeedbackDialog. VM sets `requestSubmitting = true` and launches
     * the [me.manga.kira.domain.usecase.feedback.SendLanguageRequestUseCase] with the
     * current `requestText`. On completion (success OR failure):
     *  - Sets `requestSubmitting = false`.
     *  - On success: also sets `requestDialogVisible = false` + `requestText = ""`, and emits
     *    [LanguageEffect.RequestSubmitted].
     *  - On failure: keeps `requestDialogVisible = true` + preserves `requestText` so the user
     *    can edit and retry, and emits [LanguageEffect.RequestFailed].
     *
     * **Re-entrance guard**: if the user taps Send while `requestSubmitting == true`, the VM
     * branch is a no-op (same posture as the Details slice's `onRetry` re-entrance guard).
     * This prevents double-submission if the UI doesn't disable the button.
     */
    data object OnSubmitRequest : LanguageIntent
}
