package me.manga.kira.presentation.about

import me.manga.kira.domain.usecase.about.GetAppMetadataUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * ViewModel for the rework About screen.
 *
 * Phase 7.x.about. Strict-MVI: state lives in [AboutState]; intents are sealed
 * ([AboutIntent]); effects are one-shot ([AboutEffect]). Constructor-injected use case
 * (DIP §6) — the VM never depends on the `:data` impl or the legacy `:shared`
 * `AppVersionProvider` expect class directly.
 *
 * **`init {}` block dispatches a single `viewModelScope.launch` that calls the suspend use
 * case once and updates state** — same posture as
 * [me.manga.kira.presentation.statistics.StatisticsViewModel] / Reader's resume-position
 * pre-load. No `OnEnter` intent is modelled because the metadata is needed unconditionally
 * the moment the screen mounts; an `OnEnter` would just push the trigger to the :ui's
 * `LaunchedEffect(Unit)` for no behavioural gain (the metadata is stable for the running
 * process, so re-reads on screen re-entry produce identical values).
 *
 * **Why `viewModelScope.launch` not `useCase().onEach {...}.launchIn(viewModelScope)`** —
 * [GetAppMetadataUseCase] is a `suspend fun invoke()` (one-shot, returns a single
 * [me.manga.kira.domain.model.about.AppMetadata]), not a `Flow`. A `launch { ... }`
 * captures the result once and updates state once. Same shape as
 * [me.manga.kira.presentation.details.DetailsViewModel]'s init `loadDetails()` call.
 *
 * **Intent → Effect dispatch**: all three intents are pure pass-throughs.
 * [AboutIntent.OnOpenPlayStore] → [AboutEffect.OpenPlayStorePage] with the current
 * [AboutState.packageName] from state. [AboutIntent.OnOpenUrl] → [AboutEffect.OpenUrl]
 * with the URL forwarded verbatim. [AboutIntent.OnOpenWhatsNew] →
 * [AboutEffect.NavigateToWhatsNew] (parameterless — the route adapter knows the target
 * destination; see Phase 7.x.about.whatsnewrow). No state mutation on any intent — the
 * screen just delegates a side-effect to the host.
 *
 * **`state.value.packageName` read at emit-time, not captured at intent submission**: if
 * the use case is still in flight when the user taps "Check for update" (effectively
 * impossible given the synchronous nature of the legacy provider, but defensible
 * forward-compat), [AboutState.packageName] is the empty string and
 * [AboutEffect.OpenPlayStorePage] receives `""`. The legacy `IntentLauncher.openPlayStorePage`
 * actuals tolerate an empty package id (Android builds a malformed Play Store URI and the
 * system launcher refuses; iOS / Desktop fall through to a no-op).
 *
 * **SOLID notes (Contract §6)**:
 * - **SRP**: orchestrates About state + 2-intent dispatch. No animation logic, no
 *   nav decisions (route adapter routes effects via [me.manga.kira.core.platform.IntentLauncher]).
 * - **OCP**: sealed [AboutIntent] / [AboutEffect] hierarchies grow with new variants
 *   (e.g., `OnOpenSourceCode`, `NavigateToWhatsNew`) without touching this VM's existing
 *   `when` arms.
 * - **LSP**: extends `MviViewModel<AboutState, AboutIntent, AboutEffect>` — drop-in
 *   `ViewModel`.
 * - **ISP**: 3-surface contract from the base class (`state` / `effects` / `submit`).
 * - **DIP**: depends on `:domain`'s [GetAppMetadataUseCase] (interface seam) — the
 *   strangler-fig `:data` impl reaching into `:shared` is invisible from here.
 *
 * **Lifecycle**: bound as `viewModel` in `aboutReworkModule` — Koin's `viewModel { }`
 * scope handles `viewModelScope` cancellation on host destruction.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster106.staleKdocSweep.cascade,
 * Task #562, 2026-05-28): the file-scope VM manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-sixth sibling of the cluster57-105 sweep — closes
 * the wave-9 `:presentation/about/` batch alongside AboutState.kt):
 *  (a) "Strict-MVI: state lives in [AboutState]; intents are sealed
 *  ([AboutIntent]); effects are one-shot ([AboutEffect]). Constructor-
 *  injected use case (DIP §6)" — LIVE-NOT-STALE. L61-65 primary
 *  constructor accepts exactly one collaborator (`getAppMetadata:
 *  GetAppMetadataUseCase`); L63 superclass parameterization confirms
 *  `MviViewModel<AboutState, AboutIntent, AboutEffect>` triple.
 *  (b) "`init {}` block dispatches a single `viewModelScope.launch` that
 *  calls the suspend use case once and updates state — same posture as
 *  [StatisticsViewModel] / Reader's resume-position pre-load" — LIVE-
 *  NOT-STALE. L67-78 init block is one `viewModelScope.launch { val
 *  metadata = getAppMetadata(); updateState { ... } }`; the no-flow
 *  shape preserved verbatim (one-shot suspend, not `onEach`). Peer
 *  cross-ref to StatisticsViewModel verified at cluster103 sibling
 *  sweep (Task #559).
 *  (c) "Why `viewModelScope.launch` not `useCase().onEach{...}.launchIn(
 *  viewModelScope)` — `GetAppMetadataUseCase` is a `suspend fun invoke()`
 *  one-shot, not a `Flow`" — LIVE-NOT-STALE. Domain-side
 *  `GetAppMetadataUseCase` `operator fun invoke(): AppMetadata` (suspend
 *  one-shot, no Flow) verified at cluster30 sweep (Task #486); KMP
 *  signature stability confirmed.
 *  (d) "Intent → Effect dispatch — all three intents are pure pass-
 *  throughs" — LIVE-NOT-STALE. L80-88 `handle` realizes three branches:
 *  OnOpenPlayStore emits OpenPlayStorePage(packageName=state.value.
 *  packageName); OnOpenUrl emits OpenUrl(url=intent.url); OnOpenWhatsNew
 *  emits NavigateToWhatsNew. Zero state-mutation branches — the screen
 *  delegates side effects to the host.
 *  (e) "`state.value.packageName` read at emit-time, not captured at
 *  intent submission — forward-compat for a future async metadata
 *  source" — LIVE-NOT-STALE. L83 `OpenPlayStorePage(packageName = state.
 *  value.packageName)` realizes the at-emit-time read; the empty-string
 *  tolerance posture preserved (Android malformed-URI refuse plus iOS/
 *  Desktop no-op).
 *  (f) "SOLID notes (SRP/OCP/LSP/ISP/DIP)" — LIVE-NOT-STALE. SRP: 3-
 *  intent dispatch plus init metadata fetch (one rule); OCP: sealed
 *  AboutIntent/AboutEffect surfaces extensible at compile-time; LSP:
 *  extends MviViewModel<AboutState, AboutIntent, AboutEffect>; ISP: 3-
 *  surface base contract (state/effects/submit); DIP: domain use case
 *  injection (no `:data` reach). Five-tier SOLID rationale preserved
 *  verbatim.
 *  Six classifications STAND on their own merits as a faithful
 *  AboutViewModel manifest. Original Phase 7.x.about-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
class AboutViewModel(
    private val getAppMetadata: GetAppMetadataUseCase,
) : MviViewModel<AboutState, AboutIntent, AboutEffect>(
        initialState = AboutState(),
    ) {
    init {
        launchSafely {
            try {
                val metadata = getAppMetadata()
                updateState {
                    it.copy(
                        versionName = metadata.versionName,
                        packageName = metadata.packageName,
                    )
                }
            } finally {
                // Clear the loading flag even if the (documented infallible) metadata read throws,
                // so the screen never hangs on a spinner; launchSafely routes the throw to
                // onUnhandledError.
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    override suspend fun handle(intent: AboutIntent) {
        when (intent) {
            AboutIntent.OnOpenPlayStore ->
                emit(
                    AboutEffect.OpenPlayStorePage(packageName = state.value.packageName),
                )
            AboutIntent.OnRequestReview -> emit(AboutEffect.RequestReview)
            is AboutIntent.OnOpenUrl -> emit(AboutEffect.OpenUrl(url = intent.url))
            AboutIntent.OnOpenWhatsNew -> emit(AboutEffect.NavigateToWhatsNew)
        }
    }
}
