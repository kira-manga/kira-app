package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.repository.ReadingSessionRepository

/**
 * Mark the start of a reading session.
 *
 * Phase 6.4.x.statistics foundation. The Reader VM invokes this when the screen becomes active
 * (the rework's MVI equivalent of the legacy `ReaderViewModel.onScreenResume()`). Counterpart to
 * [EndReadingSessionUseCase]; both are constructor-injected into the Reader VM so the VM's
 * constructor type-signature reveals exactly which capabilities the VM consumes.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [ReadingSessionRepository.begin]". Where the
 * session start time lives (in-memory in the impl), how it interacts with the elapsed-minutes
 * counter, and what happens on session collision (overwrite, per [ReadingSessionRepository]'s
 * idempotence contract) are all `:data` concerns.
 *
 * Why a use case at all when this is a single-line non-suspend delegate: same rationale as
 * [SetReadingModeUseCase] — stable presentation-layer dependency, test seam, parity with the
 * established "one verb = one use case" shape across the rework. The two session use cases
 * (this + [EndReadingSessionUseCase]) are deliberately split rather than rolled into a single
 * repository injection so the VM's surface reveals both intents at a glance.
 *
 * Why `operator fun invoke` is not `suspend`: the underlying `ReadingSessionRepository.begin` is
 * non-suspend because the only side effect is recording `now()` in memory. Matches the legacy
 * `StatisticsRepository.startReadingSession` shape (also non-suspend).
 *
 * Constructor-injected [ReadingSessionRepository] per contract §6 DIP — Koin binds the impl as a
 * `single` in `readerReworkModule` (single, because the impl holds the per-session start time;
 * a `factory` would forget the start on every resolution and break the begin/end pairing).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster125.staleKdocSweep.cascade,
 * Task #581, 2026-05-28): classified as follows after recursive symbol
 * verification (seventy-eighth sibling of the cluster57-124 sweep —
 * opens wave-22 `:domain/usecase/reader/` 5-file batch alongside End-
 * ReadingSession plus LoadPagePosition plus SavePagePosition plus
 * ListChapters; closing 3-file batch (FetchChapterPages plus Observe-
 * ReadingMode plus SetReadingMode) deferred to cluster126 follow-up
 * per ≤5-file-cap-with-followup convention upheld for the third
 * consecutive wave after wave-20 downloads/ 5+1 split and wave-21
 * complaint/ 5+4 split):
 *  (a) "Phase 6.x.statistics foundation Reader-VM-screen-resumed-invocation
 *  + counterpart-to-End pair" — LIVE-NOT-STALE. ReaderViewModel.kt L20
 *  import, L230 ctor `private val startReadingSession: StartReadingSession-
 *  UseCase`, L297 invocation `ReaderIntent.OnScreenResumed -> start-
 *  ReadingSession()` inside the intent dispatcher; ReaderViewModel L97
 *  KDoc references the session-timer pair framing. Counterpart Reader-
 *  Intent.OnScreenPaused -> endReadingSession() at L298 confirms the
 *  begin/end pairing in the VM's intent dispatcher.
 *  (b) "§6 SRP single-rule-delegate + non-suspend-shape + why-use-case-
 *  at-all single-line-non-suspend-delegate" — LIVE-NOT-STALE. Single-
 *  line repository.begin() pass-through preserved at L35-37; non-suspend
 *  invoke shape matches non-suspend ReadingSessionRepository.begin (the
 *  legacy StatisticsRepository.startReadingSession was also non-suspend,
 *  in-memory now() recording only). One-verb-one-use-case-shape parity
 *  with SetReadingModeUseCase (cluster126 forthcoming peer) and
 *  EndReadingSessionUseCase (intra-cluster125 79th sibling) upheld.
 *  (c) "§6 DIP constructor-injection + Koin single-binding-rationale
 *  (single because impl holds per-session start time; factory would
 *  forget the start on every resolution and break the begin/end
 *  pairing)" — LIVE-NOT-STALE. ReaderReworkModule.kt L120 `factory {
 *  StartReadingSessionUseCase(get()) }` realization — the use case
 *  itself is a stateless factory, but the underlying ReadingSession-
 *  Repository it injects is the `single` that holds the per-session
 *  start time; the factory-on-use-case + single-on-repository split
 *  preserves the begin/end pairing while keeping the use case
 *  type-resolution cheap. Three classifications STAND on their own
 *  merits. Original Phase 6.4.x.statistics-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
class StartReadingSessionUseCase(
    private val repository: ReadingSessionRepository,
) {
    operator fun invoke() {
        repository.begin()
    }
}
