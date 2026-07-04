package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.repository.ReadingSessionRepository

/**
 * Mark the end of a reading session and persist the elapsed time.
 *
 * Phase 6.4.x.statistics foundation. The Reader VM invokes this when the screen leaves the
 * foreground (the rework's MVI equivalent of the legacy `ReaderViewModel.onScreenPause()`).
 * Counterpart to [StartReadingSessionUseCase].
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [ReadingSessionRepository.end]". The minute
 * conversion (legacy: integer division by `60_000L`), the persisted counter update, and the
 * "drop sessions shorter than one minute" rule all live in the repository impl.
 *
 * `suspend` shape: matches [ReadingSessionRepository.end] — the impl writes to persistent storage
 * via `DataStoreHelper.addReadMinutes(delta)`, which is a suspending settings call.
 *
 * Idempotence contract: invocation when no session is in progress is a no-op (the repository
 * guards with `if (start == 0L) return`). This is load-bearing for the UI hook — the Reader's
 * `DisposableEffect.onDispose` always fires even when the resume callback never landed (e.g.,
 * Compose tears the composition down before the host reaches `onResume`); the Pause use case
 * tolerating the no-op case prevents that path from corrupting the counter.
 *
 * Why a use case at all (single-line `suspend` delegate): same rationale as
 * [SetReadingModeUseCase] / [StartReadingSessionUseCase] — stable presentation-layer dependency,
 * test seam, parity with the established slice pattern.
 *
 * Constructor-injected [ReadingSessionRepository] per contract §6 DIP — same singleton impl as
 * [StartReadingSessionUseCase] (the begin/end pair shares per-session state, so both use cases
 * must resolve to the same instance).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster125.staleKdocSweep.cascade,
 * Task #581, 2026-05-28): classified as follows after recursive symbol
 * verification (seventy-ninth sibling of the cluster57-124 sweep —
 * second file of the wave-22 `:domain/usecase/reader/` 5-file batch
 * alongside StartReadingSession plus LoadPagePosition plus SavePage-
 * Position plus ListChapters):
 *  (a) "Phase 6.x.statistics foundation Reader-VM-screen-pause-invocation
 *  + counterpart-to-Start pair" — LIVE-NOT-STALE. ReaderViewModel.kt
 *  L13 import, L231 ctor `private val endReadingSession: EndReading-
 *  SessionUseCase`, L298 invocation `ReaderIntent.OnScreenPaused ->
 *  endReadingSession()` inside the intent dispatcher; intra-cluster125
 *  sibling cross-ref to StartReadingSessionUseCase (78th sibling, just-
 *  swept) confirms the begin/end pairing posture from the End side.
 *  (b) "Idempotence contract no-op-when-no-session + load-bearing for
 *  DisposableEffect.onDispose path" — LIVE-NOT-STALE. ReadingSession-
 *  Repository.end guards with `if (start == 0L) return`; this is the
 *  invariant that lets the rework :ui Reader's tear-down-without-prior-
 *  resume safely fire end() without corrupting the counter.
 *  (c) "§6 SRP single-rule-delegate + suspend-shape + why-use-case-at-
 *  all single-line-suspend-delegate" — LIVE-NOT-STALE. L36-38 single-
 *  line repository.end() pass-through preserved; suspend invoke shape
 *  matches suspend ReadingSessionRepository.end (the impl writes to
 *  persistent storage via DataStoreHelper.addReadMinutes(delta), a
 *  suspending settings call). The minute-conversion (legacy: integer
 *  division by 60_000L), persisted-counter-update, and drop-sessions-
 *  shorter-than-one-minute rule all live in :data per the original
 *  prose's invariant — strangler-fig posture upheld.
 *  (d) "§6 DIP + same-singleton-impl as Start (per-session state shared)
 *  + Koin factory lifecycle" — LIVE-NOT-STALE. ReaderReworkModule.kt
 *  L121 `factory { EndReadingSessionUseCase(get()) }` realization;
 *  same ReadingSessionRepository single-binding as StartReadingSession-
 *  UseCase's L120 factory injects. Four classifications STAND on their
 *  own merits. Original Phase 6.4.x.statistics-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
class EndReadingSessionUseCase(
    private val repository: ReadingSessionRepository,
) {
    suspend operator fun invoke() {
        repository.end()
    }
}
