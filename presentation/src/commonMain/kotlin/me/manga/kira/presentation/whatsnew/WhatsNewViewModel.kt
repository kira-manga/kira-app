package me.manga.kira.presentation.whatsnew

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.whatsnew.WhatsNewFeature
import me.manga.kira.domain.usecase.whatsnew.GetWhatsNewFeaturesUseCase
import me.manga.kira.domain.usecase.whatsnew.MarkWhatsNewSeenUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * ViewModel for the rework What's New screen.
 *
 * Phase 7.x.whatsnew (foundation). Strict-MVI: state lives in [WhatsNewState]; intents are
 * sealed ([WhatsNewIntent]); effects are one-shot ([WhatsNewEffect] — currently empty).
 * Constructor-injected use cases (DIP §6) — the VM never depends on the `:data` impl, the
 * legacy `:shared` `WhatsNewRemoteDataSource`, `SharedPrefsHelper`, or `AppVersionProvider`
 * directly.
 *
 * **`init {}` block dispatches a single `launchSafely` that calls the suspend
 * [GetWhatsNewFeaturesUseCase] once and updates state** — same posture as
 * [me.manga.kira.presentation.about.AboutViewModel] / Theme picker's preference pre-load.
 * No `OnEnter` intent is modelled because the feature list is needed unconditionally the
 * moment the screen mounts; an `OnEnter` would just push the trigger to the :ui's
 * `LaunchedEffect(Unit)` for no behavioural gain.
 *
 * **Why `viewModelScope.launch` not `useCase().onEach {...}.launchIn(viewModelScope)`** —
 * [GetWhatsNewFeaturesUseCase] is a `suspend fun invoke()` (one-shot, returns a single
 * `AppResult<List<WhatsNewFeature>>`), not a `Flow`. The remote endpoint isn't observed for changes
 * (the wire format is server-static for the running process). Same shape as
 * [AboutViewModel]'s init `getAppMetadata()` call. A `launch { ... }` captures the result
 * once and updates state once.
 *
 * **Error path**: data failures and unexpected load throws populate [WhatsNewState.error]; only
 * success sets [WhatsNewState.hasLoadedSuccessfully], including a successfully empty list.
 * Cancellation propagates and cannot manufacture an error or automatic seen-mark eligibility.
 *
 * **`OnRetry`**: re-runs [GetWhatsNewFeaturesUseCase] after the previous job completes. While a load
 * is active, repeated Retry is ignored. Each load clears the previous error and success flag before
 * suspending so the UI renders loading, not a stale error, during recovery.
 *
 * **`OnMarkSeen`**: awaits [MarkWhatsNewSeenUseCase] in the intent handler. Explicit dismissal
 * remains separate from loading; the automatic route submits the mark only after successful load.
 * The historical audit notes below describe the old bare-list/error-placeholder behavior.
 *
 * **`OnPageChanged(index)`** (Phase 7.x.whatsnew.pager): mirrors the pager's `currentPage`
 * into [WhatsNewState.currentPage]. No suspending work, no effect emission — a pure state
 * mutation via [updateState]. The `:ui` dispatches it from a `LaunchedEffect` keyed on
 * `pagerState.currentPage`, so the VM update is debounced by Compose's snapshot system
 * (one dispatch per resting swipe). The mirror serves config-change survival in addition
 * to the `rememberPagerState`'s own `rememberSaveable` backing — same belt-and-suspenders
 * posture as the Reader's `currentChapterPosition` mirror.
 *
 * **SOLID notes (Contract §6)**:
 * - **SRP**: orchestrates WhatsNew state + 2-intent dispatch. No remote fetch logic (lives
 *   in `:shared` via `:data`), no nav decisions (route adapter owns nav).
 * - **OCP**: sealed [WhatsNewIntent] / [WhatsNewEffect] hierarchies grow with new variants
 *   (pager `OnPageChanged`, fullscreen `OnOpenMedia`/`OnDismissMedia`, should-show
 *   `OnAutoShow`) without touching this VM's existing `when` arms.
 * - **LSP**: extends `MviViewModel<WhatsNewState, WhatsNewIntent, WhatsNewEffect>` — drop-in
 *   `ViewModel`.
 * - **ISP**: 3-surface contract from the base class (`state` / `effects` / `submit`).
 * - **DIP**: depends on `:domain`'s use cases (interface seam) — the strangler-fig `:data`
 *   impl reaching into `:shared` is invisible from here.
 *
 * **Lifecycle**: bound as `viewModel` in `whatsNewReworkModule` — Koin's `viewModel { }`
 * scope handles `viewModelScope` cancellation on host destruction.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster109.staleKdocSweep.cascade,
 * Task #565, 2026-05-28): the file-scope VM manifest above is classified
 * as follows after recursive symbol verification across the KMP graph
 * (forty-ninth sibling of the cluster57-108 sweep — closes the wave-9
 * `:presentation/whatsnew/` batch and the wave-9 `:presentation/` tier
 * overall alongside WhatsNewEffect.kt plus WhatsNewIntent.kt):
 *  (a) "Foundation Phase 7.x.whatsnew strict-MVI — state lives in
 *  WhatsNewState; intents are sealed (WhatsNewIntent); effects are one-
 *  shot (WhatsNewEffect — currently empty); 2 use cases constructor-
 *  injected" — LIVE-NOT-STALE. L74-79 primary constructor injects
 *  exactly 2 collaborators (getWhatsNewFeatures plus markWhatsNewSeen);
 *  empty-effect channel verified at cluster109 sibling WhatsNewEffect
 *  sweep (this commit).
 *  (b) "Init {} block dispatches a single viewModelScope.launch that
 *  calls the suspend GetWhatsNewFeaturesUseCase once — peer cross-ref
 *  AboutViewModel posture" — LIVE-NOT-STALE. L81-83 init block calls
 *  `loadFeatures()` which at L93-104 wraps the suspend call in a view-
 *  ModelScope.launch{}; AboutViewModel init-launch posture verified
 *  at cluster106 sibling sweep (Task #562).
 *  (c) "Why viewModelScope.launch not useCase().onEach{...}.launchIn —
 *  GetWhatsNewFeaturesUseCase is a suspend fun invoke() (one-shot), not
 *  a Flow" — LIVE-NOT-STALE. L93-104 realization confirms one-shot
 *  posture: single suspend call, single updateState, no Flow.collect/
 *  onEach/launchIn.
 *  (d) "Error path — `:data` impl currently returns empty list on any
 *  remote failure; the VM treats empty as a valid terminal state;
 *  retry path is wired-but-dormant" — LIVE-NOT-STALE. L93-104 load-
 *  Features lacks any `.onFailure` / `try-catch` posture; the error-
 *  Message state field exists (WhatsNewState L101) but no write path
 *  realizes a non-null value today. Retry intent (L87 `WhatsNewIntent.
 *  OnRetry rename-to loadFeatures()`) is LIVE-wired and will become
 *  user-visible once explicit failure surfacing lands.
 *  (e) "OnRetry — re-runs GetWhatsNewFeaturesUseCase in a fresh view-
 *  ModelScope.launch, setting isLoading = true plus errorMessage = null
 *  BEFORE the suspend call" — LIVE-NOT-STALE. L93-95 loadFeatures
 *  realization: `updateState { it.copy(isLoading = true, errorMessage =
 *  null) }` BEFORE the suspend `getWhatsNewFeatures()` call at L96.
 *  Same posture as legacy WhatsNewViewModel.retryLoadFeatures() — the
 *  legacy retire at §351 verified preserved-by-rework.
 *  (f) "OnMarkSeen — fires-and-forgets MarkWhatsNewSeenUseCase on
 *  viewModelScope; structurally infallible sync prefs write; no state
 *  mutation, no effect emission; auto-trigger deferred to Phase 7.x.
 *  whatsnew.gate" — MIXED LIVE-PLUS-STALE-WORDING. L88 `WhatsNewIntent.
 *  OnMarkSeen rename-to markWhatsNewSeen()` realization calls the
 *  suspend use case DIRECTLY from within `handle` (which itself runs on
 *  the intent-processing coroutine via the MviViewModel base class) —
 *  NOT wrapped in `viewModelScope.launch{}`. The "fires-and-forgets on
 *  viewModelScope" wording is mildly inaccurate (the call suspends the
 *  intent-processing coroutine until the prefs write completes) but
 *  immaterial given the use case's structurally-infallible sync nature.
 *  Gate auto-trigger forecast remains LIVE — FORECAST-NOT-YET-FULFILLED.
 *  (g) "OnPageChanged(index) Phase 7.x.whatsnew.pager — mirrors pager's
 *  currentPage into WhatsNewState.currentPage; no suspending work, no
 *  effect emission; pure state mutation via updateState" — LIVE-NOT-
 *  STALE. L89 `is WhatsNewIntent.OnPageChanged rename-to updateState {
 *  it.copy(currentPage = intent.index) }` realization confirms pure-
 *  state-mutation posture — no launch, no effect, no use case dispatch.
 *  (h) "SOLID 5-tier (SRP plus OCP plus LSP plus ISP plus DIP)" — LIVE-
 *  NOT-STALE. L74-79 VM class extends `MviViewModel<WhatsNewState,
 *  WhatsNewIntent, WhatsNewEffect>` (LSP drop-in); 3-surface base-class
 *  contract (ISP); use case dependencies (DIP); sealed-intent OCP
 *  additive; SRP rules-one-screen-state-orchestration.
 *  Eight classifications STAND on their own merits as a faithful
 *  WhatsNewViewModel manifest. Original Phase 7.x.whatsnew-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class WhatsNewViewModel(
    private val getWhatsNewFeatures: GetWhatsNewFeaturesUseCase,
    private val markWhatsNewSeen: MarkWhatsNewSeenUseCase,
) : MviViewModel<WhatsNewState, WhatsNewIntent, WhatsNewEffect>(
    initialState = WhatsNewState(),
) {
    private var loadJob: Job? = null

    init {
        loadFeatures()
    }

    override suspend fun handle(intent: WhatsNewIntent) {
        when (intent) {
            WhatsNewIntent.OnRetry -> loadFeatures()
            WhatsNewIntent.OnMarkSeen -> markWhatsNewSeen()
            is WhatsNewIntent.OnPageChanged -> updateState { it.copy(currentPage = intent.index) }
            is WhatsNewIntent.OnOpenVideo -> emit(WhatsNewEffect.OpenVideo(intent.url))
        }
    }

    @Suppress("TooGenericExceptionCaught") // Preserve unexpected Throwable mapping after rethrowing cancellation.
    private fun loadFeatures() {
        if (loadJob?.isActive == true) return
        loadJob =
            launchSafely {
                updateState { it.copy(isLoading = true, error = null, hasLoadedSuccessfully = false) }
                try {
                    val result = getWhatsNewFeatures()
                    currentCoroutineContext().ensureActive()
                    completeLoad(result)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    val error = AppError.Unexpected(message = t::class.simpleName.orEmpty(), cause = t)
                    completeLoad(AppResult.Failure(error))
                } finally {
                    // Clearing the spinner after cancellation is not successful-load evidence.
                    updateState { it.copy(isLoading = false) }
                }
            }
    }

    private fun completeLoad(result: AppResult<List<WhatsNewFeature>>) {
        updateState { state ->
            when (result) {
                is AppResult.Success ->
                    state.copy(
                        isLoading = false,
                        features = result.value,
                        error = null,
                        hasLoadedSuccessfully = true,
                    )
                is AppResult.Failure ->
                    state.copy(
                        isLoading = false,
                        error = result.error,
                        hasLoadedSuccessfully = false,
                    )
            }
        }
    }
}
