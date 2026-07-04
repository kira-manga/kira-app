package me.manga.kira.presentation.settings

import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.presentation.mvi.MviState

/**
 * Settings hub MVI state.
 *
 * Phase 7.x.settings.foundation rework. Holds the 5 toggle booleans (general 2 + theme 3) +
 * the formatted cache-size string + flags covering the gap between subscription and the first
 * [me.manga.kira.domain.model.settings.SettingsSnapshot] emission ([isLoading]) and the
 * in-flight clear-cache action ([isClearingCache]).
 *
 * The state is **flow-driven**: the VM's `init {}` collector projects each upstream
 * [me.manga.kira.domain.model.settings.SettingsSnapshot] into a fresh state. Toggle writes
 * via [me.manga.kira.domain.usecase.settings.UpdateSettingsToggleUseCase] re-emit through
 * the legacy `:shared` `SettingsRepository`'s underlying pref flows → the `:data` impl's
 * `combine` → here. Cache-clear writes via
 * [me.manga.kira.domain.usecase.settings.ClearCacheUseCase] re-emit through the `:data`
 * impl's `cacheRefresh` `MutableSharedFlow` trigger → the same `combine` → here.
 *
 * **First-run defaults** mirror the legacy [SettingsRepository] / `DataStoreHelper` defaults:
 *  - [downloadedOnly] = `false`, [incognito] = `false` (DataStore default `false`)
 *  - [followSystemTheme] = `true` (matches `isFollowSystem() = true`)
 *  - [darkMode] = `false`, [pureBlack] = `true` (match `isDarkMode() = false`,
 *    `isPureBlack() = true`)
 *  - [cacheSize] = `""` (the first emission populates this with the formatted bytes —
 *    typically sub-frame, the user never perceives the blank).
 *
 * **`isLoading` clears on the FIRST emission from the upstream** — once any field has a real
 * value, the screen renders the legacy-compatible defaults; the actual values arrive on the
 * upstream's first emission (sub-millisecond for `SharedPreferences.booleanPrefFlow` reads
 * and `DataStoreHelper` reads — the user does not perceive a half-loaded state).
 *
 * **`isClearingCache`**: set `true` when the VM starts the clear action; reset to `false` on
 * the use case's `Result<Unit>` completion (success OR failure). The `:ui` composable disables
 * the cache row while the flag is set to prevent double-tap re-entry — defence-in-depth
 * against the [MviViewModel] intent channel queuing two `OnClearCache` intents from a
 * fast double-tap.
 *
 * **No `error` field**: the toggle / clear-cache / conversion writes carry no error surface at all
 * (native parity, SET-PFIX-P3) — toggle writes are sync `SharedPreferences` / suspending DataStore
 * writes whose failure modes are vanishingly small, and the clear-cache outcome simply re-emits the
 * cache-size subtitle into state. The only one-shot feedback effect is
 * [me.manga.kira.presentation.settings.SettingsEffect.FeedbackResult], the typed feedback-
 * submission outcome the `:ui` layer renders as a localized snackbar. Same posture as
 * [me.manga.kira.presentation.theme.ThemeState] / [me.manga.kira.presentation.complaint.
 * ComplaintState]'s action-failure handling.
 *
 * **`feedbackDialogOpen` + `isSubmittingFeedback`** (Phase 7.x.settings.feedback): the Feedback
 * dialog's open/closed state and its in-flight-submission flag. The dialog's category dropdown
 * + body text field are LOCAL to the `:ui` composable (`remember { mutableStateOf(...) }`),
 * matching the [me.manga.kira.presentation.complaint.ComplaintIntent.OnSubmitReply] /
 * [me.manga.kira.presentation.complaint.ComplaintIntent.OnSubmitEdit] established posture
 * — payloads ride along with the submit intent rather than being mirrored into MVI state. This
 * keeps the state surface narrow (2 fields, not 4) and preserves "user can resume their typing
 * mid-edit" behaviour without needing OnFeedbackTypeChange / OnFeedbackBodyChange intents that
 * fire per-keystroke. `isSubmittingFeedback` gates the Submit button's enabled state and the
 * dismiss path (the dialog refuses to close mid-submission to avoid orphaning the in-flight
 * use-case call).
 *
 * **`readingMode` + `readingModeDialogOpen`** (Phase 7.x.settings.readingmode): the currently
 * persisted reading mode (driven by a second `init {}` collector subscribed to
 * [me.manga.kira.domain.usecase.reader.ObserveReadingModeUseCase]) and the picker dialog's
 * open/closed state. The dialog itself is single-tap-commits — selecting an entry fires
 * [SettingsIntent.OnSelectReadingMode] which both persists the choice via
 * [me.manga.kira.domain.usecase.reader.SetReadingModeUseCase] AND closes the dialog. No
 * local "pending selection" state is needed in the `:ui` composable; the displayed mode comes
 * directly from this field, which re-emits when the upstream
 * [me.manga.kira.domain.repository.ReadingModeRepository.observe] flow updates after the
 * write commits. Same shape as the rework Theme picker
 * ([me.manga.kira.presentation.theme.ThemeState.theme] / `OnSelectTheme`).
 *
 * Default value `ReadingMode.DEFAULT` matches the repository's unset-disk fallback, so the
 * "Reading mode" row shows a sensible label before the first emission (sub-millisecond gap on
 * the same cold start as the toggle defaults).
 *
 * Contract §6 SRP: one rule — "what the Settings hub renders right now". No business logic,
 * no derivation; the 5 boolean fields + cache-size string come verbatim from the upstream
 * [SettingsSnapshot].
 *
 * Contract §17: no `Any`, no `!!`, no `lateinit`. All fields are concrete value types with
 * sensible defaults.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster107.staleKdocSweep.cascade,
 * Task #563, 2026-05-28): the file-scope state-shape manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-seventh sibling of the cluster57-106 sweep — opens
 * the wave-9 `:presentation/settings/` batch alongside SettingsView-
 * Model.kt):
 *  (a) "Holds the 5 toggle booleans (general 2 plus theme 3) plus the
 *  formatted cache-size string plus 2 transient flags (isLoading,
 *  isClearingCache) plus 2 feedback-dialog fields plus 2 reading-mode-
 *  dialog fields" — LIVE-NOT-STALE. L85-98 data-class shape verbatim —
 *  12 `val`-only properties: isLoading plus downloadedOnly plus
 *  incognito plus followSystemTheme plus darkMode plus pureBlack plus
 *  cacheSize plus isClearingCache plus feedbackDialogOpen plus
 *  isSubmittingFeedback plus readingMode plus readingModeDialogOpen.
 *  (b) "Flow-driven projection — VM's init {} collector projects each
 *  upstream SettingsSnapshot into a fresh state plus second init {}
 *  collector subscribes to ObserveReadingModeUseCase" — LIVE-NOT-STALE.
 *  SettingsViewModel.kt L103-118 first observeSettings() collector;
 *  L120-124 second observeReadingMode() collector — both LIVE.
 *  (c) "First-run defaults mirror the legacy SettingsRepository /
 *  DataStoreHelper defaults" — LIVE-NOT-STALE. L86-97 defaults verbatim
 *  match the KDoc enumeration: false/false/true/false/true/empty-string
 *  plus all flags false plus ReadingMode.DEFAULT.
 *  (d) "isLoading clears on the FIRST emission; isClearingCache /
 *  isSubmittingFeedback gate the action surfaces" — LIVE-NOT-STALE.
 *  SettingsViewModel.kt L108 `isLoading = false` set on first observe-
 *  Settings emission; L150-151 + L173-174 re-entrance guards on Clear-
 *  Cache + SubmitFeedback realize the gate flag posture.
 *  (e) "feedbackDialogOpen plus readingMode lifecycle rationale —
 *  dialog state lives on MVI state; the dropdown plus textfield payloads
 *  ride with the submit intent (not mirrored per-keystroke)" — LIVE-NOT-
 *  STALE. SettingsViewModel.kt L140 `OnSubmitFeedback(val type:
 *  ComplaintType, val body: String)` realizes the payload-rides-with-
 *  submit posture; no per-keystroke OnFeedbackTypeChange / OnFeedback-
 *  BodyChange intents exist.
 *  (f) "Contract §6 SRP plus §17" — LIVE-NOT-STALE. One rule (what the
 *  Settings hub renders right now); 12 `val`-only properties of concrete
 *  value types; no `Any`, no `!!`, no `lateinit`.
 *  Six classifications STAND on their own merits as a faithful
 *  SettingsState manifest. Original Phase 7.x.settings-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
data class SettingsState(
    val isLoading: Boolean = true,
    val downloadedOnly: Boolean = false,
    val incognito: Boolean = false,
    val followSystemTheme: Boolean = true,
    val darkMode: Boolean = false,
    val pureBlack: Boolean = true,
    // Typed wire (2026-07 backlog L15): raw bytes; `null` = first cache-size computation still in
    // flight, rendered by :ui as the "Calculating…" placeholder. :ui formats non-null values via
    // the localized size_* unit patterns.
    val cacheSizeBytes: Long? = null,
    val isClearingCache: Boolean = false,
    val feedbackDialogOpen: Boolean = false,
    val isSubmittingFeedback: Boolean = false,
    val readingMode: ReadingMode = ReadingMode.DEFAULT,
    val readingModeDialogOpen: Boolean = false,
    // Phase 7.x.settings.cbz — Yami Compressor download-settings section. `useCbzFormat`
    // defaults `true`, `autoConvertToCbz` defaults `false` (legacy DataStoreHelper defaults).
    // `isCompressingDownloads` gates the "compress existing" action button while the in-flight call
    // runs (mirrors `isClearingCache`).
    val useCbzFormat: Boolean = true,
    val autoConvertToCbz: Boolean = false,
    val isCompressingDownloads: Boolean = false,
    // GAP-SET-16 — live CBZ conversion progress (native parity with the native
    // `CbzConversionViewModel.conversionProgress` StateFlow). Driven by the VM's third `init {}`
    // collector subscribed to ObserveCbzConversionUseCase; projects the per-chapter counts +
    // current-item + terminal Success / Stopped / Error fields into the `:ui` `CbzConversionDialog`.
    // The dialog renders itself based on this field's `isConverting` / `error` / `successMessage`
    // flags (same visibility rule as native's dialog) — independent of `isCompressingDownloads`,
    // which only gates the trigger button.
    val cbzConversion: CbzConversionProgress = CbzConversionProgress(),
) : MviState
