package me.manga.kira.presentation.settings

import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.model.settings.SettingsToggle
import me.manga.kira.presentation.mvi.MviIntent

/**
 * User actions submitted from the Settings hub screen.
 *
 * Phase 7.x.settings.foundation rework. Sealed so the [SettingsViewModel.handle] `when` is
 * exhaustive; adding a new action requires adding a new subclass (OCP — compile-time
 * enforcement that the reducer handles every case).
 *
 * **Foundation variants**:
 *  - [OnToggle] — flip one of the visible boolean toggles (general/theme or iOS Low Power Mode).
 *  - [OnClearCache] — invoke the cache-clear action.
 *  - [OnNavigate] — tap one of the 6 nav rows.
 *
 * **Phase 7.x.settings.feedback variants**:
 *  - [OnOpenFeedbackDialog] — user tapped the "Request feature / bug" row.
 *  - [OnDismissFeedbackDialog] — user dismissed the dialog (back press, outside tap, Cancel).
 *  - [OnSubmitFeedback] — user pressed Submit with a selected type + body.
 *
 * **Phase 7.x.settings.readingmode variants**:
 *  - [OnOpenReadingModeDialog] — user tapped the "Reading mode" row.
 *  - [OnDismissReadingModeDialog] — user dismissed the dialog (back press, outside tap, Cancel).
 *  - [OnSelectReadingMode] — user tapped one of the 6 mode entries; the VM persists + closes.
 *
 * Future sibling follow-on slices append further variants without touching the base class:
 *  - `Phase 7.x.settings.downloads` would add `OnNavigate(SettingsDestination.DOWNLOADS)` once
 *    Downloads has a rework counterpart.
 *
 * Same OCP posture as [me.manga.kira.presentation.complaint.ComplaintIntent] (4 foundation
 * variants → 10 after the actions slice appended without touching the VM's base class).
 *
 * Contract §6 ISP: each variant carries only the minimal payload it needs.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster33.staleKdocSweep.cascade,
 * Task #489, 2026-05-28): one stale citation appears in the
 * [OnSubmitFeedback] member-list rationale below:
 *  - Lines 117-118 ([OnSubmitFeedback] KDoc, "legacy `SettingsScreen.
 *    kt:374-376`'s `complaintViewModel.submit(it, it.name, body,
 *    ...)` pattern"). STALE-SYMBOL-REFERENCE — Phase 9.x.
 *    settings_about.legacyui.retire (§354) DELETED the legacy `:
 *    composeApp/.../features/settings/ui/screens/SettingsScreen.kt`
 *    along with its 10 sibling helpers as part of the 11-file
 *    orphan chain retirement (Settings + About chains bundled in
 *    the same retire). A recursive search of the legacy settings
 *    folder for a `SettingsScreen.kt` with the cited line range
 *    374-376 `complaintViewModel.submit(it, it.name, body, ...)`
 *    call site returns NO MATCHES.
 *  Sibling cite at Lines 162-164 ([OnSelectReadingMode] KDoc,
 *  "legacy `:shared` `DataStoreHelper.readingModeFlow`") is
 *  CLASSIFIED LIVE — the cite-target `DataStoreHelper` survives on
 *  disk at `:shared/.../core/storage/DataStoreHelper.kt` (the
 *  facade was NOT retired in the §354 chain; it lives as the
 *  reactive on-disk preference reader consumed by the rework
 *  Settings hub via the strangler-fig the prose itself describes).
 *  The "legacy" framing in the prose is the pre-rework naming
 *  convention but the symbol is NOT retired. HOWEVER — the rework
 *  `:ui` `SettingsScreen` (same filename, different package:
 *  `me.manga.kira.ui.settings.SettingsScreen`) is LIVE as the
 *  canonical Settings hub backed by [SettingsState] +
 *  [SettingsViewModel] + this [SettingsIntent] sealed interface;
 *  the Submit-Feedback-pattern rationale (subject derived as
 *  `type.name` pre-Phase 10, mirroring the legacy submit call's
 *  parameter shape) STANDS on its own merits past the §354
 *  fulfilled landing as the LIVE rework realization — the
 *  `:presentation` VM's submit handler continues to derive the
 *  subject as `type.name` pending the Phase 10 i18n lift that
 *  re-points to the localized display name (NOT YET fulfilled —
 *  the i18n lift has not landed). The [SettingsIntent] sealed
 *  interface remains LIVE as the canonical Settings-hub intent ADT
 *  consumed by [SettingsViewModel] + the rework `:ui`
 *  `SettingsScreen`. Original §253-era prose preserved verbatim
 *  per the audit-trail-preservation convention — the citation is
 *  historical record of the design lineage including the Submit-
 *  Feedback parameter-shape rationale that was subsequently
 *  fulfilled (legacy settings chain retired) across §354; the
 *  deferred Phase-10 i18n forecast stays LIVE pending its follow-on
 *  i18n lift slice.
 */
sealed interface SettingsIntent : MviIntent {

    /**
     * User flipped a toggle. The VM invokes
     * [me.manga.kira.domain.usecase.settings.UpdateSettingsToggleUseCase] in a coroutine;
     * the upstream pref flow re-emits with the new value once the legacy
     * `SharedPreferences.putBoolean` / `DataStore.edit` write commits.
     *
     * The variant carries the post-toggle value (not a "toggle" verb) so the reducer is
     * idempotent regardless of state-vs-intent ordering: a `false → false` write is a no-op
     * at the storage level (Android's `putBoolean` short-circuits equal-value writes;
     * DataStore's `edit` produces no emission for an unchanged value). Same posture as
     * [me.manga.kira.presentation.theme.ThemeIntent.OnTogglePureBlack].
     *
     * The [SettingsToggle] variants are the boolean fields of
     * [me.manga.kira.domain.model.settings.SettingsSnapshot] — the `:data` impl's
     * exhaustive `when` mapper translates each to the matching legacy setter.
     */
    data class OnToggle(val toggle: SettingsToggle, val value: Boolean) : SettingsIntent

    /**
     * User tapped the Clear-cache row. The VM invokes
     * [me.manga.kira.domain.usecase.settings.ClearCacheUseCase] in a coroutine; on
     * `Result.success`, the impl's refresh trigger re-emits a fresh cache-size string through
     * the upstream `combine`. There is NO snackbar on either outcome (SET-PFIX-P3 native parity):
     * native `clearLargeCache()` is fire-and-forget and the only feedback is the recomputed
     * cache-size subtitle re-rendering through the upstream flow.
     *
     * In-flight protection: [SettingsState.isClearingCache] is set `true` immediately and
     * the `:ui` composable disables the row while the flag is set, preventing a double-tap
     * from queuing two `OnClearCache` intents. The VM-side guard short-circuits the second
     * intent — defence-in-depth against intent channel re-entry.
     */
    data object OnClearCache : SettingsIntent

    /**
     * User tapped one of the 6 nav rows. The VM re-emits this as a
     * [SettingsEffect.NavigateTo] effect carrying the same destination — the `:composeApp`
     * route adapter consumes the effect and calls `navController.navigate(Screen.<X>Rework)`.
     *
     * **Why an effect round-trip** (not a callback parameter to the composable): the
     * presentation layer doesn't have a `NavController` — that's a `:composeApp` concern.
     * The effect channel is the established MVI surface for one-shot side effects; nav is
     * an effect. Same posture as
     * [me.manga.kira.presentation.complaint.ComplaintEffect.ShowSuccessMessage] /
     * [me.manga.kira.presentation.reader.ReaderEffect.OpenChapterInWebView].
     */
    data class OnNavigate(val destination: SettingsDestination) : SettingsIntent

    /**
     * User tapped the "Request feature / bug" row. The VM sets
     * [SettingsState.feedbackDialogOpen] = `true`; the `:ui` composable observes the flag and
     * renders the Feedback dialog (category dropdown + body field + Submit / Cancel).
     *
     * No payload: the dropdown selection and body text are LOCAL to the composable
     * (`remember { mutableStateOf(...) }`), matching the §95 OnSubmitReply / OnSubmitEdit
     * established posture. The MVI state surface only tracks open/closed + in-flight; the
     * actual user-typed content rides along with [OnSubmitFeedback].
     */
    data object OnOpenFeedbackDialog : SettingsIntent

    /**
     * User dismissed the Feedback dialog (back press, outside tap, Cancel button). The VM
     * resets [SettingsState.feedbackDialogOpen] to `false`. If [SettingsState.isSubmittingFeedback]
     * is `true`, the VM ignores the dismiss to avoid orphaning the in-flight submission — the
     * `:ui` composable already gates this at the dialog's `properties = DialogProperties(
     * dismissOnBackPress = !isSubmittingFeedback, dismissOnClickOutside = !isSubmittingFeedback)`
     * level, but the VM-side guard is defence-in-depth.
     */
    data object OnDismissFeedbackDialog : SettingsIntent

    /**
     * User pressed Submit in the Feedback dialog. The VM invokes
     * [me.manga.kira.domain.usecase.feedback.SubmitFeedbackUseCase] in a coroutine; on
     * `Result.success`, the dialog closes and the VM emits a
     * [SettingsEffect.FeedbackResult]`(success = true)` that the `:ui` layer renders as a localized
     * confirmation snackbar. On `Result.failure`, the dialog stays open and the VM emits a
     * `FeedbackResult(success = false)` carrying the cause; the `:ui` layer shows the localized
     * error snackbar with a Retry action that re-opens the dialog (the user retains their typed text).
     *
     * Payload carries [type] (the dropdown selection), [subject] (the localized category display
     * name resolved at the `:ui` layer) and [body] (the text-field content).
     *
     * **P2-SET (F9) — subject is the localized display name.** Native submits
     * `complaintViewModel.submit(it, it.getDisplayName(context), body, ...)` — the human-readable,
     * localized category name as the complaint subject (native `SettingsScreen.kt:320-323`). The
     * prior rework derived the subject inside the VM as `type.name` (the uppercase enum constant,
     * e.g. `"TECHNICAL"`), so the persisted/admin-side subject read as a code identifier rather
     * than the user-facing category — a data-quality regression. The subject is now resolved at
     * the `:ui` boundary via the screen's `complaintTypeLabel(type)` `stringResource` helper (the
     * VM has no `Res.string` access) and rides along with this intent, matching native.
     *
     * In-flight protection: [SettingsState.isSubmittingFeedback] is set `true` immediately and
     * reset on the use case's `Result<Unit>` completion. The Submit button is disabled and the
     * dismiss path is gated while the flag is set.
     */
    data class OnSubmitFeedback(
        val type: ComplaintType,
        val subject: String,
        val body: String,
    ) : SettingsIntent

    /**
     * User tapped the "Reading mode" row. The VM sets [SettingsState.readingModeDialogOpen] =
     * `true`; the `:ui` composable observes the flag and renders the mode-picker dialog (one
     * row per [ReadingMode] entry with single-tap-commits semantics).
     *
     * No payload: the dialog reads the currently persisted mode from
     * [SettingsState.readingMode], which is driven by the VM's second `init {}` collector
     * subscribed to [me.manga.kira.domain.usecase.reader.ObserveReadingModeUseCase].
     */
    data object OnOpenReadingModeDialog : SettingsIntent

    /**
     * User dismissed the Reading-mode dialog (back press, outside tap, Cancel button). The VM
     * resets [SettingsState.readingModeDialogOpen] to `false`. Unlike the Feedback dialog there
     * is no in-flight state to guard — the dialog has no Apply button; selecting a mode commits
     * immediately via [OnSelectReadingMode] and closes the dialog from the same code path.
     */
    data object OnDismissReadingModeDialog : SettingsIntent

    /**
     * User selected one of the 6 [ReadingMode] entries in the picker dialog. The VM:
     *  1. Persists the choice via [me.manga.kira.domain.usecase.reader.SetReadingModeUseCase].
     *     The next emission from the upstream
     *     [me.manga.kira.domain.repository.ReadingModeRepository.observe] flow re-projects
     *     into [SettingsState.readingMode] (so the row's subtitle updates reactively).
     *  2. Closes the dialog ([SettingsState.readingModeDialogOpen] = `false`).
     *
     * The persistence write is fire-and-forget in `viewModelScope` — the
     * `ObservableSettings.putString` call is synchronous on every platform (in-memory state,
     * sub-microsecond), so there's no in-flight window to model. Same posture as
     * [OnToggle] (also fire-and-forget; no in-flight flag).
     *
     * Strangler-fig: the write commits under the same on-disk `reading_mode` key the legacy
     * `:shared` `DataStoreHelper.readingModeFlow` reads from. Both the rework Settings hub and
     * the legacy Reader VM see the new value reactively without coordination.
     */
    data class OnSelectReadingMode(val mode: ReadingMode) : SettingsIntent

    /**
     * Phase 7.x.settings.cbz — user tapped "Start Conversion" under the Yami Compressor section.
     * The VM invokes
     * [me.manga.kira.domain.usecase.settings.CompressExistingDownloadsUseCase] in a coroutine;
     * the result is awaited only to reset the in-flight flag — the terminal Success / Stopped /
     * Error outcome renders in the `:ui` `CbzConversionDialog` (GAP-SET-16), not a snackbar.
     *
     * In-flight protection: [SettingsState.isCompressingDownloads] is set `true` on intent
     * receipt and reset on completion; the `:ui` button is disabled while the flag is set —
     * mirrors the [OnClearCache] re-entrance guard.
     *
     * **GAP-SET-16**: while the run is in flight the VM also projects the
     * [me.manga.kira.domain.usecase.settings.ObserveCbzConversionUseCase] progress stream into
     * [SettingsState.cbzConversion], which drives the `:ui` `CbzConversionDialog` (determinate
     * progress + converted/total counts + current item + Stop button + terminal states).
     */
    data object OnCompressExistingDownloads : SettingsIntent

    /**
     * GAP-SET-16 — user pressed the Stop button in the CBZ conversion dialog. The VM invokes
     * [me.manga.kira.domain.usecase.settings.StopCbzConversionUseCase], which flips the `:data`
     * impl's cancellation flag; the conversion loop finishes the in-flight chapter, then emits a
     * terminal Stopped [me.manga.kira.domain.model.settings.CbzConversionProgress] (carrying the
     * converted/remaining counts) through the progress stream. Native-parity port of the native
     * `CbzConversionViewModel.stopConversion()` trigger.
     */
    data object OnStopConversion : SettingsIntent

    /**
     * GAP-SET-16 — user dismissed the CBZ conversion dialog from a terminal state (the Close /
     * Done button on the Error / Success / Stopped variants). The VM resets
     * [SettingsState.cbzConversion] to the idle default so the dialog hides. Native-parity port of
     * the native `CbzConversionViewModel.clearError()` (which nulls error / successMessage /
     * wasStopped). Ignored while a run is still converting — the dialog blocks dismissal then.
     */
    data object OnDismissConversionDialog : SettingsIntent
}
