package me.manga.kira.presentation.sources

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.usecase.feedback.SubmitFeedbackUseCase
import me.manga.kira.domain.usecase.sources.EnableDefaultLanguageSourcesUseCase
import me.manga.kira.domain.usecase.sources.ObserveSourcesUseCase
import me.manga.kira.domain.usecase.sources.SetLanguageEnabledUseCase
import me.manga.kira.domain.usecase.sources.SetSourceEnabledUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Sources screen ViewModel.
 *
 * Phase 7.x.sources rework. Subscribes to [ObserveSourcesUseCase] at construction time (in
 * `init {}`) and projects each emission into [SourcesState]; reacts to two foundation
 * [SourcesIntent] variants — both toggle mutators (per-source, per-language).
 *
 * **Phase 7.x.sources.complaint extension**: handles 3 additional intents
 * ([SourcesIntent.OnOpenComplaintDialog] / [OnDismissComplaintDialog] / [OnSubmitComplaint])
 * for the "Request adding source" dialog, mirroring the [me.manga.kira.presentation.
 * settings.SettingsViewModel] feedback-dialog posture from Phase 7.x.settings.feedback. The
 * complaint type is fixed at [ComplaintType.SITES_ADD] (pinned by the row label) — the VM
 * passes it verbatim to [SubmitFeedbackUseCase].
 *
 * **Why `init {}` collector** (not an `OnEnter` intent): matches the
 * [me.manga.kira.presentation.updates.UpdatesViewModel] /
 * [me.manga.kira.presentation.history.HistoryViewModel] /
 * [me.manga.kira.presentation.statistics.StatisticsViewModel] posture. The Sources screen
 * has no lifecycle moments that mediate the observation — it's a flow-driven list with
 * mutate-and-re-emit toggle actions from the upstream Room `Flow<List<SourcesEntity>>`.
 * `viewModelScope` ensures the collector cancels when the ViewModel is cleared (host
 * destruction), preventing leaks via structured concurrency.
 *
 * **Why no `catch {}` on the upstream**: the upstream is Room's `allSources` flow from
 * `SourcesDao.getAllSources()` (via the legacy facade's `allSources` property). Room's
 * observe-site does not throw — it emits the current rows and re-emits on every tracked write.
 * If a future refactor introduces a fallible upstream (e.g., a network-sync step layered onto
 * the flow), add `.catch {}` here and a `SourcesEffect.ShowError` variant (see
 * [SourcesEffect] KDoc).
 *
 * **Toggle intents launch fire-and-forget in `viewModelScope`**: the upstream Room flow
 * re-emits on every `UPDATE sources SET isEnabled` write, so the screen's state updates
 * reactively without needing the VM to imperatively mutate `items`. The `launch {}` lets the
 * `handle` suspend return immediately (so the view's `submit(intent)` doesn't block); the
 * mutation itself completes on the use case's coroutine. The `UPDATE` SQL is structurally
 * infallible — no `.onFailure` is needed (cf. [SourcesEffect] KDoc on the lack of
 * `ShowError`).
 *
 * **`OnToggleLanguage`'s fan-out runs on the use-case coroutine, not in the VM**: see
 * [me.manga.kira.data.repository.SourcesRepositoryImpl.setLanguageEnabled] for the fan-out
 * details. The VM stays a thin dispatcher — `launch { setLanguageEnabled(lang, enabled) }`.
 *
 * **Phase 7.x.sources.onboardingseed extension**: handles
 * [SourcesIntent.OnSeedDefaultLanguage] by dispatching the raw locale code to
 * [EnableDefaultLanguageSourcesUseCase] in `viewModelScope.launch {}`. The use case owns the
 * tag-formatting + EN-fallback policy (the VM forwards verbatim); dedup lives in the `:ui`
 * composable's `LaunchedEffect(onboardingLanguageTag)` key — the VM has no state field for
 * this command intent. See [SourcesIntent.OnSeedDefaultLanguage] KDoc for the rationale.
 *
 * **`OnSubmitComplaint` handler**: re-entry guarded by [SourcesState.isSubmittingComplaint].
 * The flag is set `true` synchronously on intent receipt, then a `viewModelScope.launch {}`
 * invokes [SubmitFeedbackUseCase] with the pinned [ComplaintType.SITES_ADD] type + the localized
 * subject carried in the intent (resolved in `:ui`; NP Phase 2 GAP-SRC-02) + user-typed body.
 * On `Result.success` the dialog closes and the VM emits payload-free [SourcesEffect.RequestSubmitted];
 * on failure the dialog stays open (preserving the user's typed text) and the VM emits
 * [SourcesEffect.RequestFailed] carrying the body. No failure message is built VM-side — all
 * snackbar copy is resolved in `:ui` via `stringResource` (GAP-SRC-02).
 *
 * Constructor-injected use cases per contract §6 DIP — Koin binds them as a `viewModel` in
 * `sourcesReworkModule`.
 *
 * **SRP (contract §6)**: orchestrates Sources presentation state + toggles + complaint
 * submission, nothing else. No business logic — the use cases own that. No persistence — the
 * repository owns that. No language-grouping — the [SourcesState.groupedByLanguage] derived
 * getter (used by the `:ui`) owns that. Same locality posture as
 * [me.manga.kira.presentation.updates.UpdatesViewModel] (date-grouping in `:ui`).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster108.staleKdocSweep.cascade,
 * Task #564, 2026-05-28): the file-scope VM manifest above is classified
 * as follows after recursive symbol verification across the KMP graph
 * (forty-eighth sibling of the cluster57-107 sweep — closes the wave-9
 * `:presentation/sources/` batch alongside SourcesEffect.kt):
 *  (a) "Foundation 2-toggle posture (OnToggleSource plus OnToggleLanguage)
 *  plus Phase 7.x.sources.complaint extension adds 3 intents (OnOpen-
 *  ComplaintDialog plus OnDismissComplaintDialog plus OnSubmitComplaint)
 *  plus Phase 7.x.sources.onboardingseed extension adds 1 intent (OnSeed-
 *  DefaultLanguage)" — LIVE-NOT-STALE. L101-120 six handle branches
 *  realize the combined foundation plus complaint plus onboardingseed
 *  surface. Count matches: 2 toggles plus 3 complaint plus 1 onboarding-
 *  seed equals 6.
 *  (b) "Init {} collector — peer cross-ref to UpdatesViewModel plus
 *  HistoryViewModel plus StatisticsViewModel" — LIVE-NOT-STALE. L93-99
 *  init block hosts the single observeSources() collector; Updates init-
 *  collector posture verified at cluster108 sibling UpdatesViewModel
 *  sweep (this commit); History init-collector posture verified at
 *  cluster104 sweep (Task #560); Statistics init-collector posture
 *  verified at cluster103 sweep (Task #559).
 *  (c) "No `catch {}` on the upstream — Room SourcesDao.getAllSources()
 *  flow doesn't throw at the observe site" — LIVE-NOT-STALE. L93-99
 *  collector LACKS `.catch {}` operator; same impl-boundary posture as
 *  the Updates/History/Statistics siblings.
 *  (d) "Toggle intents launch fire-and-forget in viewModelScope" — LIVE-
 *  NOT-STALE. L103-108 OnToggleSource plus OnToggleLanguage both wrap
 *  the use case call in `viewModelScope.launch {}`; no `.onFailure`
 *  chain attached; the upstream Room re-emit on UPDATE realizes
 *  reactive UI state.
 *  (e) "OnSeedDefaultLanguage fire-and-forget posture; use case owns
 *  tag-formatting plus EN-fallback policy; dedup lives in `:ui` Launched-
 *  Effect" — LIVE-NOT-STALE. L116-118 wraps `enableDefaultLanguageSources
 *  (intent.languageTag)` in viewModelScope.launch{}; no VM state field
 *  for this command intent; raw locale code forwarded verbatim.
 *  (f) "OnSubmitComplaint re-entry guarded by SourcesState.isSubmitting-
 *  Complaint" — LIVE-NOT-STALE. L122-123 handleSubmitComplaint helper
 *  opens with `if (state.value.isSubmittingComplaint) return`; L124
 *  sets flag true synchronously; L128-141 reset flag in BOTH success
 *  AND failure branches; dialog stays open on failure (preserving typed
 *  text) per L137 absence of complaintDialogOpen reset.
 *  (g) "Failure-message construction mirrors SettingsViewModel.handle-
 *  ClearCache plus handleSubmitFeedback posture" — STALE. No failure
 *  message is built VM-side any more: the NP Phase 2 GAP-SRC-02/03 i18n
 *  lift removed the `exceptionOrNull()` construction, and handleSubmit-
 *  Complaint now emits payload-free RequestSubmitted / RequestFailed(body)
 *  with all snackbar copy resolved in `:ui` via `stringResource`.
 *  Seven classifications STAND on their own merits as a faithful
 *  SourcesViewModel manifest. Original Phase 7.x.sources-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class SourcesViewModel(
    observeSources: ObserveSourcesUseCase,
    private val setSourceEnabled: SetSourceEnabledUseCase,
    private val setLanguageEnabled: SetLanguageEnabledUseCase,
    private val submitFeedback: SubmitFeedbackUseCase,
    private val enableDefaultLanguageSources: EnableDefaultLanguageSourcesUseCase,
) : MviViewModel<SourcesState, SourcesIntent, SourcesEffect>(
    initialState = SourcesState(),
) {

    init {
        observeSources()
            .onEach { snapshot ->
                updateState { it.copy(isLoading = false, items = snapshot) }
            }
            // #17-family (backlog L7): Room rarely throws at the observe site, but a mapper/driver
            // throw would otherwise escape viewModelScope and crash. Same degrade posture as
            // DownloadsViewModel: clear the spinner; the screen renders its empty state.
            .catch { updateState { it.copy(isLoading = false) } }
            .launchIn(viewModelScope)
    }

    override suspend fun handle(intent: SourcesIntent) {
        when (intent) {
            is SourcesIntent.OnToggleSource -> {
                // #29: launchSafely so a Room write throw routes to onUnhandledError, not a crash.
                launchSafely { setSourceEnabled(intent.source.api, intent.enabled) }
            }
            is SourcesIntent.OnToggleLanguage -> {
                // #29: launchSafely so a Room write throw routes to onUnhandledError, not a crash.
                launchSafely { setLanguageEnabled(intent.language, intent.enabled) }
            }
            is SourcesIntent.OnOpenComplaintDialog ->
                updateState { it.copy(complaintDialogOpen = true) }
            is SourcesIntent.OnDismissComplaintDialog -> {
                if (state.value.isSubmittingComplaint) return
                updateState { it.copy(complaintDialogOpen = false) }
            }
            is SourcesIntent.OnSubmitComplaint ->
                handleSubmitComplaint(body = intent.body, subject = intent.subject)
            is SourcesIntent.OnSeedDefaultLanguage -> {
                // #29: launchSafely so a Room write throw routes to onUnhandledError, not a crash.
                launchSafely { enableDefaultLanguageSources(intent.languageTag) }
            }
        }
    }

    private fun handleSubmitComplaint(body: String, subject: String) {
        if (state.value.isSubmittingComplaint) return
        updateState { it.copy(isSubmittingComplaint = true) }
        viewModelScope.launch {
            // NP Phase 2 P2 (sources complaint subject): the subject is the localized
            // ComplaintType.SITES_ADD display name ("Add Manga Site"), resolved in `:ui` and
            // threaded down via the intent — matching native RepoSettingsScreen, which submits
            // `getDisplayName(context)` as the subject. Supersedes the former `type.name`
            // ("SITES_ADD") subject, a data divergence visible to whoever triages requests.
            val type = ComplaintType.SITES_ADD
            val result = submitFeedback(type = type, subject = subject, body = body)
            if (result.isSuccess) {
                updateState {
                    it.copy(
                        isSubmittingComplaint = false,
                        complaintDialogOpen = false,
                    )
                }
                emit(SourcesEffect.RequestSubmitted)
            } else {
                // NP Phase 2 (GAP-SRC-02 + GAP-SRC-03): the dialog stays open (typed text
                // preserved) and the localized failure snackbar carries a Retry action that
                // re-submits this exact body. Snackbar copy is resolved in `:ui` via
                // stringResource — no English literal here.
                updateState { it.copy(isSubmittingComplaint = false) }
                emit(SourcesEffect.RequestFailed(body))
            }
        }
    }
}
