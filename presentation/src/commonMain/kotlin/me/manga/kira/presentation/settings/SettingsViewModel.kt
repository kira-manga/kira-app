package me.manga.kira.presentation.settings

import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.domain.usecase.feedback.SubmitFeedbackUseCase
import me.manga.kira.domain.usecase.reader.ObserveReadingModeUseCase
import me.manga.kira.domain.usecase.reader.SetReadingModeUseCase
import me.manga.kira.domain.usecase.settings.ClearCacheUseCase
import me.manga.kira.domain.usecase.settings.ClearCbzConversionUseCase
import me.manga.kira.domain.usecase.settings.CompressExistingDownloadsUseCase
import me.manga.kira.domain.usecase.settings.ObserveCbzConversionUseCase
import me.manga.kira.domain.usecase.settings.ObserveSettingsUseCase
import me.manga.kira.domain.usecase.settings.StopCbzConversionUseCase
import me.manga.kira.domain.usecase.settings.UpdateSettingsToggleUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Settings hub screen ViewModel.
 *
 * Phase 7.x.settings.foundation rework. Subscribes to [ObserveSettingsUseCase] in `init {}` and
 * projects each [me.manga.kira.domain.model.settings.SettingsSnapshot] emission into
 * [SettingsState]; reacts to three [SettingsIntent] variants —
 * [SettingsIntent.OnToggle] / [SettingsIntent.OnClearCache] / [SettingsIntent.OnNavigate].
 *
 * **Phase 7.x.settings.readingmode extension**: a second `init {}` collector subscribes to
 * [ObserveReadingModeUseCase] and projects each [ReadingMode] emission into
 * [SettingsState.readingMode]. The picker dialog's open/close flag plus the
 * [SettingsIntent.OnSelectReadingMode] handler land here too — selection commits via
 * [SetReadingModeUseCase] and closes the dialog from the same code path (single-tap-commits
 * UX; no Apply button, no in-flight flag). Same shape as the rework
 * [me.manga.kira.presentation.theme.ThemeViewModel.handle]'s `OnSelectTheme` posture.
 *
 * **Why `init {}` collector** (not an `OnEnter` intent): matches the
 * [me.manga.kira.presentation.theme.ThemeViewModel] /
 * [me.manga.kira.presentation.statistics.StatisticsViewModel] /
 * [me.manga.kira.presentation.sources.SourcesViewModel] posture. The Settings hub has no
 * lifecycle moments that mediate the observation — it's a flow-driven UI with mutate-and-re-
 * emit feedback from the upstream pref flows. `viewModelScope` ensures the collector cancels
 * when the ViewModel is cleared (host destruction), preventing leaks via structured concurrency.
 *
 * **Why no `catch {}` on the upstream**: the upstream is
 * `combine(legacy.downloadedOnlyFlow, ..., legacy.pureBlackFlow, cacheSizeFlow)`. The legacy
 * `:shared` `DataStoreHelper` / `SharedPrefsHelper` flows don't throw — they emit the current
 * value and re-emit on every change. The `cacheSizeFlow` runs the okio file walk on
 * `dispatchers.io`; if the walk fails, the legacy `getCacheFolderSize()` returns 0 (okio
 * handles missing dirs gracefully) and `formatSize(0)` returns `"0 B"` — degrades gracefully
 * without an exception. Adding a `.catch {}` would suppress nothing and add noise.
 *
 * **`isLoading` clears on the FIRST emission**: the upstream `combine` waits for every source
 * to emit once before emitting downstream (standard `combine` semantics — see
 * [kotlinx.coroutines.flow.combine] KDoc). Once the first joint emission arrives, the screen
 * has real values for all visible toggles + the cache size; setting `isLoading = false` on that
 * emission is correct.
 *
 * **`OnToggle` handler**: launches fire-and-forget in `viewModelScope`. The upstream pref
 * flows re-emit on every successful write, so the screen's toggles update reactively without
 * the VM imperatively mutating state. Failures of the use case's `Result<Unit>` are
 * intentionally swallowed — `SharedPreferences.putBoolean` / `DataStore.edit` failure modes
 * are vanishingly small in practice (the legacy posture is to not handle them either; see
 * legacy `SettingsViewModel.toggleDarkMode` etc.). If a future refactor introduces a fallible
 * write backend, this is where to add an `emitEffect(ShowSnackbar(...))` on failure.
 *
 * **`OnClearCache` handler**: re-entry guarded by [SettingsState.isClearingCache]. The flag
 * is set `true` synchronously on intent receipt, then a `viewModelScope.launch {}` invokes
 * the use case and on completion clears the flag + emits a snackbar effect (success or
 * failure path). The `:ui` composable mirrors the flag onto the cache row's
 * `enabled` property to disable taps during the in-flight window.
 *
 *   Failure-message construction mirrors the [me.manga.kira.presentation.complaint.
 *   ComplaintViewModel.completeAction] posture: `exceptionOrNull()?.message ?:
 *   simpleName ?: "Unknown error"`. The legacy doesn't surface failures from
 *   `clearFilesLargerThan1MB()` at all (it's a fire-and-forget call); the rework adds the
 *   snackbar without changing observable success-path behaviour.
 *
 * **`OnNavigate` handler**: emits a [SettingsEffect.NavigateTo] carrying the same
 * [SettingsDestination]. No state mutation — nav is a pure side effect (the destination
 * screen owns its own state; the Settings VM isn't responsible for the route's lifecycle).
 *
 * **`when (intent)` exhaustiveness**: the `:ui` composable can fire any of the 3
 * [SettingsIntent] variants. The exhaustive `when` ensures a future intent variant is a
 * compile-time error here, exactly the OCP contract for sealed-interface extension.
 *
 * Constructor: per contract §6 DIP — 3 use case classes (NOT impl types), injected by Koin
 * `viewModel` binding in `settingsReworkModule`.
 *
 * **SRP (contract §6)**: orchestrates Settings hub presentation state + visible toggles + cache
 * clear + nav routing, nothing else. No business logic — the use cases own that. No
 * preference-shape translation — the `:data` impl owns the legacy ↔ snapshot translation.
 * No styling — the `:ui` composable owns the MaterialTheme/icon lookup. No Admin routing —
 * the `:composeApp` adapter owns the user vs admin choice for [SettingsDestination.COMPLAINT].
 *
 * **Audit-trail postscript** (Phase 9.x.cluster107.staleKdocSweep.cascade,
 * Task #563, 2026-05-28): the file-scope VM manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-seventh sibling of the cluster57-106 sweep — closes
 * the wave-9 `:presentation/settings/` batch alongside SettingsState.kt):
 *  (a) "Phase 7.x.settings.foundation rework — subscribes to Observe-
 *  SettingsUseCase in init {} plus reacts to OnToggle plus OnClearCache
 *  plus OnNavigate" — LIVE-NOT-STALE. L92-101 primary constructor
 *  injects 6 collaborators (observeSettings plus observeReadingMode plus
 *  4 mutation use cases); L103-118 first init collector LIVE; L128-133
 *  three foundation `handle` branches LIVE.
 *  (b) "Phase 7.x.settings.readingmode extension — second init {}
 *  collector subscribes to ObserveReadingModeUseCase plus picker dialog
 *  open/close flag plus OnSelectReadingMode handler" — LIVE-NOT-STALE.
 *  L120-124 second observeReadingMode() collector LIVE; L141-145 three
 *  reading-mode `handle` branches LIVE; L167-170 `handleSelectReading-
 *  Mode` realization fires both `readingModeDialogOpen = false` plus
 *  `setReadingMode(mode)` from the same code path — single-tap-commits
 *  posture preserved.
 *  (c) "Why init {} collector (not OnEnter) — matches ThemeViewModel /
 *  StatisticsViewModel / SourcesViewModel posture" — LIVE-NOT-STALE.
 *  ThemeViewModel init-collector posture verified at cluster105 sibling
 *  sweep (Task #561); StatisticsViewModel init-collector verified at
 *  cluster103 sweep (Task #559); SourcesViewModel init-collector verified
 *  at cluster34 sweep (Task #490).
 *  (d) "Why no `catch {}` on the upstream — combine(legacy.*Flow,
 *  cacheSizeFlow); legacy DataStoreHelper / SharedPrefsHelper flows do
 *  not throw plus cacheSizeFlow degrades gracefully on okio failure" —
 *  LIVE-NOT-STALE. L104-118 collector LACKS `.catch {}` operator;
 *  legacy no-throw contract preserved.
 *  (e) "isLoading clears on the FIRST emission — combine waits for
 *  every source to emit once before emitting downstream" — LIVE-NOT-
 *  STALE. L108 `updateState { it.copy(isLoading = false, ...) }` fires
 *  on first joint emission; combine semantics per kotlinx.coroutines.
 *  flow.combine KDoc.
 *  (f) "OnToggle handler launches fire-and-forget plus failures
 *  intentionally swallowed" — LIVE-NOT-STALE. L129-131 single-line
 *  `viewModelScope.launch { updateToggle(intent.toggle, intent.value) }`
 *  fire-and-forget realization; no `.onFailure {}` clause.
 *  (g) "OnClearCache plus OnSubmitFeedback re-entry guards — flags set
 *  true synchronously on intent receipt plus reset to false on completion
 *  (success OR failure)" — LIVE-NOT-STALE. L149-150 `if (state.value.is-
 *  ClearingCache) return` guard plus L154 unconditional reset; L172-173
 *  `if (state.value.isSubmittingFeedback) return` guard plus L186/L180
 *  unconditional reset. Failure-message construction at L155-162 plus
 *  L188-191 mirrors ComplaintViewModel.completeAction posture (cross-ref
 *  at cluster30 sweep Task #486).
 *  Seven classifications STAND on their own merits as a faithful
 *  SettingsViewModel manifest. Original Phase 7.x.settings-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class SettingsViewModel(
    observeSettings: ObserveSettingsUseCase,
    observeReadingMode: ObserveReadingModeUseCase,
    private val updateToggle: UpdateSettingsToggleUseCase,
    private val clearCache: ClearCacheUseCase,
    private val submitFeedback: SubmitFeedbackUseCase,
    private val setReadingMode: SetReadingModeUseCase,
    private val compressExistingDownloads: CompressExistingDownloadsUseCase,
    observeCbzConversion: ObserveCbzConversionUseCase,
    private val stopCbzConversion: StopCbzConversionUseCase,
    private val clearCbzConversion: ClearCbzConversionUseCase,
) : MviViewModel<SettingsState, SettingsIntent, SettingsEffect>(
    initialState = SettingsState(),
) {

    init {
        observeSettings()
            .onEach { snapshot ->
                updateState {
                    it.copy(
                        isLoading = false,
                        downloadedOnly = snapshot.downloadedOnly,
                        incognito = snapshot.incognito,
                        followSystemTheme = snapshot.followSystemTheme,
                        darkMode = snapshot.darkMode,
                        pureBlack = snapshot.pureBlack,
                        cacheSizeBytes = snapshot.cacheSizeBytes,
                        useCbzFormat = snapshot.useCbzFormat,
                        autoConvertToCbz = snapshot.autoConvertToCbz,
                        allowCompressionInLowPower = snapshot.allowCompressionInLowPower,
                    )
                }
            }
            .launchIn(viewModelScope)

        observeReadingMode()
            .onEach { mode ->
                updateState { it.copy(readingMode = mode) }
            }
            .launchIn(viewModelScope)

        // GAP-SET-16 — third collector: project the CBZ conversion progress stream into
        // [SettingsState.cbzConversion]. The `:ui` `CbzConversionDialog` renders the determinate
        // progress + counts + current item + Stop button + terminal Success / Stopped / Error
        // states directly off this field. Native parity with the native screen observing
        // `cbzConversionViewModel.conversionProgress` via `collectAsStateWithLifecycle`.
        observeCbzConversion()
            .onEach { progress ->
                updateState { it.copy(cbzConversion = progress) }
            }
            .launchIn(viewModelScope)
    }

    override suspend fun handle(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.OnToggle -> {
                // #29: launchSafely so a throw routes to onUnhandledError, not a viewModelScope crash.
                launchSafely { updateToggle(intent.toggle, intent.value) }
            }
            is SettingsIntent.OnClearCache -> handleClearCache()
            is SettingsIntent.OnNavigate -> emit(SettingsEffect.NavigateTo(intent.destination))
            is SettingsIntent.OnOpenFeedbackDialog ->
                updateState { it.copy(feedbackDialogOpen = true) }
            is SettingsIntent.OnDismissFeedbackDialog -> {
                if (state.value.isSubmittingFeedback) return
                updateState { it.copy(feedbackDialogOpen = false) }
            }
            is SettingsIntent.OnSubmitFeedback ->
                handleSubmitFeedback(intent.type, intent.subject, intent.body)
            is SettingsIntent.OnOpenReadingModeDialog ->
                updateState { it.copy(readingModeDialogOpen = true) }
            is SettingsIntent.OnDismissReadingModeDialog ->
                updateState { it.copy(readingModeDialogOpen = false) }
            is SettingsIntent.OnSelectReadingMode -> handleSelectReadingMode(intent.mode)
            is SettingsIntent.OnCompressExistingDownloads -> handleCompressExistingDownloads()
            is SettingsIntent.OnStopConversion -> stopCbzConversion()
            is SettingsIntent.OnDismissConversionDialog -> handleDismissConversionDialog()
        }
    }

    private fun handleDismissConversionDialog() {
        // GAP-SET-16 / #14 — native `clearError()`: only dismiss from a terminal state; ignore
        // while a run is still converting (the dialog blocks dismissal then too).
        if (state.value.cbzConversion.isConverting) return
        // Reset BOTH the projected state field AND the underlying `:data` progress StateFlow.
        // The flow is hot `single`-scoped state, so without clearing it a terminal
        // Complete/Stopped/Error snapshot survives a SettingsViewModel recreation and replays into
        // the recreated VM's `init {}` collector — re-popping the dialog on a fresh entry (#14).
        clearCbzConversion()
        updateState { it.copy(cbzConversion = CbzConversionProgress()) }
    }

    private fun handleCompressExistingDownloads() {
        if (state.value.isCompressingDownloads) return
        updateState { it.copy(isCompressingDownloads = true) }
        // #29: launchSafely so a throw routes to onUnhandledError, not a viewModelScope crash; the
        // flag reset lives in `finally` so a routed error still re-enables the button.
        launchSafely {
            // GAP-SET-16 — native parity: the terminal Success / Stopped / Error outcome now
            // surfaces through the `:ui` `CbzConversionDialog` (driven by the
            // [ObserveCbzConversionUseCase] progress stream projected into
            // [SettingsState.cbzConversion]), exactly like the native screen which shows ONLY the
            // dialog (no snackbar). The prior rework emitted a [SettingsEffect.ConversionResult]
            // snackbar here because the use case returned a bare `Result<Unit>` with no progress
            // Flow; now that the Flow exists, the dialog owns the terminal copy + counts and the
            // snackbar is dropped to match the source of truth. The `Result<Unit>` is still awaited
            // so `isCompressingDownloads` resets on completion (the button re-enables).
            try {
                compressExistingDownloads()
            } finally {
                updateState { it.copy(isCompressingDownloads = false) }
            }
        }
    }

    private fun handleClearCache() {
        if (state.value.isClearingCache) return
        updateState { it.copy(isClearingCache = true) }
        // #29: launchSafely so a throw routes to onUnhandledError, not a viewModelScope crash; the
        // flag reset lives in `finally` so a routed error still re-enables the row.
        launchSafely {
            // SET-PFIX-P3 (F8) — native parity: native `clearLargeCache()` is fire-and-forget and
            // shows NO snackbar — it just recomputes the cache size, which re-renders the "Used:"
            // subtitle (native SettingsViewModel.kt:63-69). The prior rework emitted a
            // ShowSnackbar with hardcoded English ("Cache cleared" / "Failed to clear cache: …"),
            // which both diverged from native and embedded unlocalized English in the VM. The
            // snackbar is dropped so the only feedback is the cache-size subtitle re-emitting through
            // the upstream `observeSettings()` flow — matching the source of truth.
            try {
                clearCache()
            } finally {
                updateState { it.copy(isClearingCache = false) }
            }
        }
    }

    private fun handleSelectReadingMode(mode: ReadingMode) {
        updateState { it.copy(readingModeDialogOpen = false) }
        // #29: launchSafely so a throw routes to onUnhandledError, not a viewModelScope crash.
        launchSafely { setReadingMode(mode) }
    }

    private fun handleSubmitFeedback(type: ComplaintType, subject: String, body: String) {
        if (state.value.isSubmittingFeedback) return
        updateState { it.copy(isSubmittingFeedback = true) }
        // #29: launchSafely so a throw routes to onUnhandledError, not a viewModelScope crash; the
        // flag reset lives in `finally` so a routed error still re-enables the Submit button.
        launchSafely {
            try {
                // P2-SET (F9) — submit the localized category display name as the subject (resolved at
                // the :ui layer and carried in the intent), matching native's
                // `submit(it, it.getDisplayName(context), body, ...)`. Replaces the prior `type.name`
                // enum-constant subject (a code identifier, not the user-facing category).
                val result = submitFeedback(type = type, subject = subject, body = body)
                if (result.isSuccess) {
                    updateState { it.copy(feedbackDialogOpen = false) }
                    // GAP-SET-13 — typed result so :ui resolves the localized success string.
                    emit(SettingsEffect.FeedbackResult(success = true))
                } else {
                    // Backlog L8 (posture consistency with Sources/Language): the raw failure is
                    // LOGGED here, never carried in the effect — :ui shows the localized error
                    // string with a Retry action; effects stay payload-free beyond the flag.
                    Logger.withTag(TAG).w(result.exceptionOrNull()) { "feedback submit failed" }
                    emit(SettingsEffect.FeedbackResult(success = false))
                }
            } finally {
                updateState { it.copy(isSubmittingFeedback = false) }
            }
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
