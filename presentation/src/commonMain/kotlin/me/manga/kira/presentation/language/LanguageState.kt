package me.manga.kira.presentation.language

import me.manga.kira.domain.model.language.Language
import me.manga.kira.presentation.mvi.MviState

/**
 * Language picker MVI state.
 *
 * Phase 7.x.language rework foundation + Phase 7.x.language.request extension. Holds:
 *  - the supported-language list (passed at VM construction time via
 *    `initialState = LanguageState(languages = getSupportedLanguages())` — sync read from
 *    [me.manga.kira.domain.usecase.language.GetSupportedLanguagesUseCase] because the list is a
 *    compile-time constant in the `:data` impl)
 *  - the currently-selected IETF code projected from
 *    [me.manga.kira.domain.usecase.language.ObserveSelectedLanguageUseCase]
 *  - [isLoading] covering the gap between subscription and first emission from the upstream flow
 *  - **Phase 7.x.language.request**: three Request-Language dialog fields ([requestDialogVisible],
 *    [requestText], [requestSubmitting]) controlling the FeedbackDialog the user opens to submit a
 *    "Please add language X" complaint
 *
 * The state is **flow-driven** for [selectedCode]: the VM's `init {}` collector projects each
 * upstream emission into the field. Selection changes propagate naturally — the DataStore write
 * via [me.manga.kira.domain.usecase.language.SetLanguageUseCase] re-emits through
 * `legacy.languageFlow` → the `:data` impl → here, so the picker is reactive without an explicit
 * `OnRefresh` intent. The [languages] list is set ONCE at construction time and never mutates.
 *
 * No `error` field — the upstream is a pure DataStore flow (no I/O), and the writes are
 * DataStore-`edit` calls whose runtime-failure modes are vanishingly small. The Request-Language
 * submission CAN fail (Firestore, network, validation), but the failure is surfaced as a one-shot
 * `LanguageEffect.RequestFailed` (snackbar), NOT stored on the state — same posture as the
 * Details slice's transient retry errors. Persisting the failure on state would require manual
 * dismiss; an effect auto-dismisses with the snackbar.
 *
 * **First-run defaults** (state-class defaults; the VM overrides [languages] at construction):
 *  - [languages] = `emptyList()` — defensive default for this data-class; the VM overrides via
 *    the `initialState` arg so the list appears in state from frame 1.
 *  - [selectedCode] = `""` — matches the upstream `DataStoreHelper.languageFlow` non-nullable
 *    default. The picker renders no trailing Done-icon row in this state.
 *  - [isLoading] = `true` — set to `false` on the first emission from
 *    `observeSelectedLanguage()`. The supported list is already populated so the screen can
 *    render the rows immediately; [isLoading] gates only the spinner-vs-rows branch in the
 *    `:ui` composable.
 *  - [requestDialogVisible] = `false` — dialog opens via `OnOpenRequestDialog` intent.
 *  - [requestText] = `""` — TextField is empty when the dialog first opens.
 *  - [requestSubmitting] = `false` — Send button enabled, no progress indicator.
 *
 * **Dialog state lifecycle**:
 *  - User taps "Request a language" row → VM sets `requestDialogVisible = true`,
 *    `requestText = ""`, `requestSubmitting = false`.
 *  - User types → VM sets `requestText` per keystroke via `OnRequestTextChange`.
 *  - User taps Send → VM sets `requestSubmitting = true`, launches the use case.
 *  - Use case completes → VM emits `RequestSubmitted`/`RequestFailed`, and on EITHER outcome
 *    sets `requestSubmitting = false`. On success: also sets `requestDialogVisible = false`
 *    and `requestText = ""` (clearing for next time). On failure: keeps the dialog open with
 *    the typed text so the user can edit and retry without re-opening.
 *  - User taps Cancel/scrim → VM sets `requestDialogVisible = false`. Does NOT clear
 *    `requestSubmitting` — if an in-flight submission completes after dismissal, the success/
 *    failure snackbar still shows on the underlying screen. Acceptable.
 *
 * Contract §6 SRP: one rule — "what the language picker renders right now, including dialog
 * state". The dialog state lives here (not on a separate `LanguageDialogState`) because the
 * dialog IS part of the language-picker experience; splitting would over-segment a coherent
 * surface. The Snackbar message text lives in the `:ui` layer (i18n captures); state only
 * carries the dialog-visibility + submitting flags + body text.
 *
 * Contract §17: no `Any`, no `!!`, no `lateinit`. All fields are concrete value types with
 * sensible defaults.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster106.staleKdocSweep.cascade,
 * Task #562, 2026-05-28): the file-scope state-shape manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-sixth sibling of the cluster57-105 sweep — sibling
 * of cluster106 LanguageIntent.kt plus LanguageViewModel.kt):
 *  (a) "Holds the supported-language list, the currently-selected IETF
 *  code, isLoading, plus three Request-Language dialog fields" — LIVE-
 *  NOT-STALE. L69-76 data-class shape verbatim — six `val`-only
 *  properties: `isLoading: Boolean = true` plus `languages: List<
 *  Language> = emptyList()` plus `selectedCode: String = ""` plus
 *  `requestDialogVisible: Boolean = false` plus `requestText: String =
 *  ""` plus `requestSubmitting: Boolean = false`.
 *  (b) "Sync read of supported list at construction time plus flow-
 *  driven selectedCode via init {} collector" — LIVE-NOT-STALE.
 *  LanguageViewModel.kt L98 `initialState = LanguageState(languages =
 *  getSupportedLanguages())` LIVE (sync read); L101-107 init block
 *  hosts `observeSelectedLanguage()` flow collector that projects
 *  emissions into `selectedCode` plus clears `isLoading`.
 *  (c) "No `error` field — request submission failures surface as one-
 *  shot RequestFailed effect not stored on state" — LIVE-NOT-STALE.
 *  State has no `error` field; LanguageViewModel.kt L146 `emit(Language-
 *  Effect.RequestFailed)` on use-case failure (failure-as-effect
 *  preserved, transient retry posture vs persisted-error posture).
 *  (d) "First-run defaults" — LIVE-NOT-STALE. L70-75 defaults verbatim
 *  match the KDoc: emptyList list (defensive — VM overrides), empty
 *  selectedCode, isLoading true, dialog flags all false plus empty
 *  text plus false submitting.
 *  (e) "Dialog state lifecycle" — LIVE-NOT-STALE. LanguageViewModel.kt
 *  L114-149 four dialog-branch reducers realize each transition: open
 *  resets fields plus sets visible=true; dismiss sets visible=false
 *  (preserves submitting); textchange replaces requestText; submit
 *  re-entrance-guards then sets submitting=true then on success clears
 *  dialog plus text plus submitting else on failure clears submitting
 *  only.
 *  (f) "Contract §6 SRP plus §17" — LIVE-NOT-STALE. One rule (what the
 *  picker plus dialog render right now); six `val`-only properties of
 *  concrete value types; no `Any`, no `!!`, no `lateinit`. Dialog state
 *  co-located rationale preserved (vs separate LanguageDialogState —
 *  the dialog IS part of the picker experience).
 *  Six classifications STAND on their own merits as a faithful
 *  LanguageState manifest. Original Phase 7.x.language-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
data class LanguageState(
    val isLoading: Boolean = true,
    val languages: List<Language> = emptyList(),
    val selectedCode: String = "",
    val requestDialogVisible: Boolean = false,
    val requestText: String = "",
    val requestSubmitting: Boolean = false,
) : MviState
