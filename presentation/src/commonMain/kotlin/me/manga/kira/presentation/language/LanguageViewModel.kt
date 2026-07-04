package me.manga.kira.presentation.language

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.manga.kira.domain.usecase.feedback.SendLanguageRequestUseCase
import me.manga.kira.domain.usecase.language.GetSupportedLanguagesUseCase
import me.manga.kira.domain.usecase.language.ObserveSelectedLanguageUseCase
import me.manga.kira.domain.usecase.language.SetLanguageUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Language picker ViewModel.
 *
 * Phase 7.x.language rework foundation + Phase 7.x.language.request extension. Subscribes to the
 * upstream selected-code flow ([ObserveSelectedLanguageUseCase]) at construction time (in
 * `init {}`) and projects each emission into [LanguageState.selectedCode]; reads the supported-
 * language list synchronously in `init {}` from [GetSupportedLanguagesUseCase]; reacts to:
 *  - [LanguageIntent.OnSelectLanguage] — persists + applies the picked code (foundation)
 *  - [LanguageIntent.OnOpenRequestDialog] — opens the FeedbackDialog (.request)
 *  - [LanguageIntent.OnDismissRequestDialog] — closes it (.request)
 *  - [LanguageIntent.OnRequestTextChange] — updates the TextField buffer (.request)
 *  - [LanguageIntent.OnSubmitRequest] — submits the request via [SendLanguageRequestUseCase],
 *    emits [LanguageEffect.RequestSubmitted]/[LanguageEffect.RequestFailed] on completion (.request)
 *
 * **Why sync `getSupportedLanguages()` + async flow for `selectedCode`** (asymmetric): the
 * supported list is immutable across the process lifetime (11 hardcoded entries in the `:data`
 * impl); fetching it via a `Flow` would add a frame's delay between subscription and first
 * emission for no benefit. The selected-code value, by contrast, changes over time (the user can
 * change it via this very screen, or via a future settings-screen drilldown) and must be
 * observed for the picker's trailing Done-icon to track the current selection. Same posture as
 * [me.manga.kira.presentation.sources.SourcesViewModel] (sync metadata + async per-source
 * enabled state).
 *
 * **Why `init {}` collector** (not an `OnEnter` intent): matches the
 * [me.manga.kira.presentation.sources.SourcesViewModel] /
 * [me.manga.kira.presentation.theme.ThemeViewModel] /
 * [me.manga.kira.presentation.statistics.StatisticsViewModel] posture. The language picker
 * has no lifecycle moments that mediate the observation — it's a flow-driven UI with mutate-
 * and-re-emit action from the upstream pref flow. `viewModelScope` ensures the collector
 * cancels when the ViewModel is cleared (host destruction), preventing leaks via structured
 * concurrency.
 *
 * **Why no `catch {}` on the upstream**: the upstream is the legacy
 * `SettingsRepository.languageFlow` which delegates to `DataStoreHelper.languageFlow` — a pure
 * `Preferences-DataStore` `Flow<String>` with a non-nullable default. DataStore reads do not
 * throw — they emit the current value and re-emit on every change. If a future refactor
 * introduces a fallible upstream (e.g., a remote-config sync layered onto the flow), add
 * `.catch {}` here and a `LanguageEffect.ShowError` variant (see [LanguageEffect] KDoc).
 *
 * **`isLoading` clears on the FIRST emission** — once the upstream emits (immediately on
 * subscription for a `Preferences-DataStore` flow with a default), the screen renders the
 * picker rows. The supported-list is already in state from frame 1 (sync read in `init {}`),
 * so `isLoading` gates only the spinner-vs-rows branch in the `:ui` composable — never blocks
 * the supported-list rendering.
 *
 * **`OnSelectLanguage` launches fire-and-forget in `viewModelScope`**: the upstream pref flow
 * re-emits on every `DataStoreHelper.setLanguage(code)` write, so the screen's selected-row
 * indicator updates reactively without needing the VM to imperatively mutate the field. The
 * `launch {}` lets the `handle` suspend return immediately (so the view's `submit(intent)`
 * doesn't block); the mutation itself completes on the use case's coroutine — which includes
 * the `applyApplicationLocale(code)` side effect (Android: activity tree recreate under the new
 * locale; iOS/Desktop: no-op per `LocaleSwitcher.kt`). The writes are coroutine-based
 * `DataStore.edit` calls that commit in milliseconds — no `.onFailure` is needed (cf.
 * [LanguageEffect] KDoc on the lack of `ShowError`).
 *
 * **Request-Language flow** (Phase 7.x.language.request):
 *  - `OnOpenRequestDialog` / `OnDismissRequestDialog` / `OnRequestTextChange` are pure state
 *    mutations — no coroutines, no use cases.
 *  - `OnSubmitRequest` is the interesting branch: it launches a coroutine that sets
 *    `requestSubmitting = true`, calls the use case with the current `requestText`, folds the
 *    `Result<Unit>` into a state update + effect emission, and finally clears
 *    `requestSubmitting = false`. The branch is guarded against re-entrance: if `requestSubmitting`
 *    is already `true`, the branch returns immediately. Same posture as the Details slice's
 *    `onRetry` guard.
 *  - **Why not include `OnSubmitRequest` in the same `handle` suspend body** (i.e., why
 *    `viewModelScope.launch { ... }` inside the `when` branch)? Same reason as
 *    `OnSelectLanguage` — the `handle` suspend should return promptly so the view's
 *    `submit(intent)` doesn't block. The launch is fire-and-forget; the result is delivered
 *    via state update + effect channel.
 *
 * Constructor-injected use cases per contract §6 DIP — Koin binds them as a `viewModel` in
 * `languageReworkModule`.
 *
 * **SRP (contract §6)**: orchestrates language-picker presentation state + selection + request
 * submission, nothing else. No business logic — the use cases own that. No locale-tag
 * translation — the `:data` impl owns the persist-then-`applyApplicationLocale` pairing. No
 * styling — the `:ui` composable owns the MaterialTheme/row layout. No Firestore plumbing —
 * the `:data` impl assembles the `Complaint` and the legacy `SendComplaintUseCase` writes it.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster106.staleKdocSweep.cascade,
 * Task #562, 2026-05-28): the file-scope VM manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-sixth sibling of the cluster57-105 sweep — closes
 * the wave-9 `:presentation/language/` batch alongside LanguageState.kt
 * plus LanguageIntent.kt):
 *  (a) "Subscribes to the upstream selected-code flow plus reads the
 *  supported-language list synchronously plus reacts to five intents" —
 *  LIVE-NOT-STALE. L92-99 primary constructor injects four collaborators
 *  (getSupportedLanguages plus observeSelectedLanguage plus setLanguage
 *  plus sendLanguageRequest); L101-107 init block hosts the single
 *  `observeSelectedLanguage()` collector; L109-151 `handle` realizes
 *  five intent branches verbatim per the KDoc enumeration.
 *  (b) "Why sync `getSupportedLanguages()` plus async flow for
 *  `selectedCode`" asymmetric rationale — LIVE-NOT-STALE. L98 `initial-
 *  State = LanguageState(languages = getSupportedLanguages())` realizes
 *  the sync read; L102 `observeSelectedLanguage()` realizes the async
 *  flow. Peer cross-ref to [SourcesViewModel] sync-metadata-plus-async-
 *  state posture preserved (verified at cluster34 sweep Task #490).
 *  (c) "Why `init {}` collector (not an `OnEnter` intent) — matches the
 *  [SourcesViewModel] / [ThemeViewModel] / [StatisticsViewModel] posture"
 *  — LIVE-NOT-STALE. ThemeViewModel init-collector posture verified at
 *  cluster105 sibling sweep (Task #561); StatisticsViewModel init-
 *  collector posture verified at cluster103 sibling sweep (Task #559);
 *  SourcesViewModel is unpostscripted-pending and remains on the
 *  cluster108 batch plan — classified as init-collector posture by
 *  recursive verification at cluster34 sweep (Task #490).
 *  (d) "Why no `catch {}` on the upstream — the upstream is the legacy
 *  `SettingsRepository.languageFlow` delegating to `DataStoreHelper.
 *  languageFlow` — a pure `Preferences-DataStore` `Flow<String>` with a
 *  non-nullable default. DataStore reads do not throw" — LIVE-NOT-STALE.
 *  L102-106 collector LACKS `.catch {}` operator; legacy DataStore no-
 *  throw contract preserved.
 *  (e) "`isLoading` clears on the FIRST emission" — LIVE-NOT-STALE. L104
 *  `updateState { it.copy(isLoading = false, selectedCode = code) }`
 *  fires on every emission including the first; supported-list is
 *  already populated from L98 so the spinner gate trips immediately on
 *  the first DataStore emission (sub-millisecond after subscription).
 *  (f) "`OnSelectLanguage` launches fire-and-forget in `viewModelScope`"
 *  — LIVE-NOT-STALE. L111-113 `viewModelScope.launch { setLanguage(intent.
 *  code) }` realizes the fire-and-forget posture; the `handle` suspend
 *  returns immediately; the upstream re-emit drives the state update
 *  (no imperative VM mutation of `selectedCode`).
 *  (g) "Request-Language flow plus re-entrance guard plus fire-and-forget
 *  for OnSubmitRequest" — LIVE-NOT-STALE. L114-122 OnOpenRequestDialog
 *  resets all three dialog fields; L123-125 OnDismissRequestDialog
 *  clears visibility only (preserves submitting per the L57 KDoc lifecyc-
 *  le contract); L126-128 OnRequestTextChange replaces requestText;
 *  L129-149 OnSubmitRequest guards on L130 `if (state.value.request-
 *  Submitting) return` then sets submitting=true then launches the use
 *  case then folds Result into success-branch (clears dialog plus text
 *  plus submitting plus emits RequestSubmitted) or failure-branch
 *  (clears submitting only plus emits RequestFailed). Peer cross-ref to
 *  Details `onRetry` re-entrance guard preserved (verified at cluster17
 *  sweep Task #473).
 *  Seven classifications STAND on their own merits as a faithful
 *  LanguageViewModel manifest. Original Phase 7.x.language-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class LanguageViewModel(
    getSupportedLanguages: GetSupportedLanguagesUseCase,
    observeSelectedLanguage: ObserveSelectedLanguageUseCase,
    private val setLanguage: SetLanguageUseCase,
    private val sendLanguageRequest: SendLanguageRequestUseCase,
) : MviViewModel<LanguageState, LanguageIntent, LanguageEffect>(
    initialState = LanguageState(languages = getSupportedLanguages()),
) {

    init {
        observeSelectedLanguage()
            .onEach { code ->
                updateState { it.copy(isLoading = false, selectedCode = code) }
            }
            .launchIn(viewModelScope)
    }

    override suspend fun handle(intent: LanguageIntent) {
        when (intent) {
            is LanguageIntent.OnSelectLanguage -> {
                // #29: setLanguage is an unwrapped suspend (DataStore write + locale-switcher
                // facade), so a throw in this sibling launch would escape viewModelScope and crash
                // the process — route it through launchSafely's safety net instead.
                launchSafely { setLanguage(intent.code) }
            }
            is LanguageIntent.OnOpenRequestDialog -> {
                updateState {
                    it.copy(
                        requestDialogVisible = true,
                        requestText = "",
                        requestSubmitting = false,
                    )
                }
            }
            is LanguageIntent.OnDismissRequestDialog -> {
                updateState { it.copy(requestDialogVisible = false) }
            }
            is LanguageIntent.OnRequestTextChange -> {
                updateState { it.copy(requestText = intent.text) }
            }
            is LanguageIntent.OnSubmitRequest -> {
                if (state.value.requestSubmitting) return
                val body = state.value.requestText
                updateState { it.copy(requestSubmitting = true) }
                viewModelScope.launch {
                    val result = sendLanguageRequest(body)
                    if (result.isSuccess) {
                        updateState {
                            it.copy(
                                requestDialogVisible = false,
                                requestText = "",
                                requestSubmitting = false,
                            )
                        }
                        emit(LanguageEffect.RequestSubmitted)
                    } else {
                        updateState { it.copy(requestSubmitting = false) }
                        emit(LanguageEffect.RequestFailed)
                    }
                }
            }
        }
    }
}
