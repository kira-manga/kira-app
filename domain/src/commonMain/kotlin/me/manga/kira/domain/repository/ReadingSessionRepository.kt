package me.manga.kira.domain.repository

/**
 * Reading-session timer for the Reader screen.
 *
 * Contract §6 SRP: owns ONE rule — "bracket a reading session" (begin / end) so the persistence
 * layer can record elapsed minutes. Where the elapsed milliseconds are converted to minutes, where
 * the running total is kept, and which on-disk key it uses are all `:data` concerns.
 *
 * Why "begin / end" rather than a single `suspend fun trackSession(block: suspend () -> Unit)`:
 *  - The two events map onto two distinct screen-lifecycle moments (resume + pause / dispose).
 *    Wrapping them in a block-receiving function forces the VM to construct an artificial scope
 *    around the user's reading time — awkward in MVI where intents are atomic actions.
 *  - The legacy reader already uses `start` / `end` symmetric calls
 *    ([me.manga.kira.presentation.features.statistics.domain.StatisticsRepository.startReadingSession]
 *    / `endReadingSession`); preserving that posture lets the `:data` impl be a thin strangler-fig
 *    delegate while the user-facing route swap is pending.
 *  - The end call is `suspend` (matches legacy) because it writes to persistent storage; the begin
 *    call is non-suspend (matches legacy) because it just records `now()` in memory.
 *
 * Cross-strangler-fig persistence: the `:data` impl delegates to the legacy
 * `StatisticsRepository.startReadingSession` / `endReadingSession` pair so both readers (legacy +
 * rework) accumulate minutes into the same on-disk counter (`StorageKeys.READ_MINUTES`). When the
 * user-facing route swap promotes the rework Reader (Phase 9.x), the legacy methods can be retired;
 * until then this duplicated entry point is the strangler-fig boundary.
 *
 * Sequential-call contract:
 *  - [begin] is idempotent. Calling [begin] twice without an intervening [end] discards the
 *    earlier start time (the legacy impl does the same — `sessionStartMillis = now()` is an
 *    unconditional overwrite). Practically harmless: the only way to trigger this is a buggy
 *    consumer calling `OnScreenResumed` twice without a `OnScreenPaused`.
 *  - [end] is safe to call when no session is in progress. Legacy guards with
 *    `if (sessionStartMillis == 0L) return`; the impl preserves this. Practically: the Reader's
 *    `DisposableEffect.onDispose` always fires even if the resume callback never landed (e.g., a
 *    config change destroys the host before resume), so [end] tolerates the no-op case.
 *  - Sessions shorter than 60 seconds round down to zero minutes and are NOT persisted (legacy
 *    parity). The VM doesn't need to know this — it just emits Resume/Pause intents.
 *
 * No `AppResult` wrapper: both methods are write-only and the underlying persistence calls don't
 * surface errors back to the caller in any actionable way — the legacy `StatisticsRepository.endReadingSession`
 * is `suspend Unit`, and the rework matches. A failed write loses a few minutes of read-time;
 * worth not crashing the Reader over. If a future revision needs reporting, both methods can grow
 * `AppResult<Unit>` return types without contract breakage (additive change for callers that
 * currently ignore the return).
 *
 * DIP (contract §6): consumers ([me.manga.kira.domain.usecase.reader.StartReadingSessionUseCase]
 * / [me.manga.kira.domain.usecase.reader.EndReadingSessionUseCase], and through them the Reader
 * VM) depend on this interface, never on the legacy `StatisticsRepository` or the `:platform`
 * settings facade. Koin binds the impl at the composition root in `readerReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster139.staleKdocSweep.cascade,
 * Task #595, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-thirty-sixth sibling of the cluster57-138
 * sweep — fourth file of the wave-25 first-cluster 5-leaf-repository
 * batch alongside MangaDetailsRepository plus ChapterPagesRepository
 * plus ReadingModeRepository):
 *  (a) "Reading-session-timer-for-the-Reader-screen + Contract-§6-SRP-
 *  owns-ONE-rule-bracket-a-reading-session-begin-end + Where-the-
 *  elapsed-milliseconds-are-converted-to-minutes-where-the-running-
 *  total-is-kept-and-which-on-disk-key-it-uses-are-all-:data-concerns
 *  + Why-begin-end-rather-than-a-single-suspend-fun-trackSession-block
 *  + The-two-events-map-onto-two-distinct-screen-lifecycle-moments-
 *  resume-plus-pause-or-dispose + The-legacy-reader-already-uses-start-
 *  end-symmetric-calls + preserving-that-posture-lets-the-:data-impl-
 *  be-a-thin-strangler-fig-delegate-while-the-user-facing-route-swap-
 *  is-pending + The-end-call-is-suspend-matches-legacy-because-it-
 *  writes-to-persistent-storage + the-begin-call-is-non-suspend-
 *  matches-legacy-because-it-just-records-now-in-memory" — LIVE-NOT-
 *  STALE plus FULFILLED-PREDICTION plus FORECAST-NOT-YET-FULFILLED-
 *  (legacy-StatisticsRepository.start-end-ReadingSession-retire-post-
 *  route-swap). Verified via recursive grep: ReadingSessionRepository
 *  is consumed by StartReadingSessionUseCase plus EndReadingSession-
 *  UseCase plus ReaderIntent plus ReaderScreen plus ReaderReworkModule
 *  plus ReadingSessionRepositoryImpl. The interface declares exactly
 *  two methods — `fun begin()` (non-suspend, matches legacy) plus
 *  `suspend fun end()` (suspend, matches legacy). The asymmetric
 *  suspend/non-suspend posture stays locked.
 *  (b) "Cross-strangler-fig-persistence-the-:data-impl-delegates-to-
 *  the-legacy-StatisticsRepository.startReadingSession-endReading-
 *  Session-pair-so-both-readers-legacy-plus-rework-accumulate-minutes-
 *  into-the-same-on-disk-counter + StorageKeys.READ_MINUTES + When-the-
 *  user-facing-route-swap-promotes-the-rework-Reader-Phase-9.x-the-
 *  legacy-methods-can-be-retired-until-then-this-duplicated-entry-
 *  point-is-the-strangler-fig-boundary + Sequential-call-contract +
 *  begin-is-idempotent + Calling-begin-twice-without-an-intervening-
 *  end-discards-the-earlier-start-time + end-is-safe-to-call-when-no-
 *  session-is-in-progress + Legacy-guards-with-if-sessionStartMillis-
 *  equals-0L-return-the-impl-preserves-this + Sessions-shorter-than-
 *  60-seconds-round-down-to-zero-minutes-and-are-NOT-persisted-legacy-
 *  parity + No-AppResult-wrapper-both-methods-are-write-only-and-the-
 *  underlying-persistence-calls-do-not-surface-errors-back-to-the-
 *  caller-in-any-actionable-way + A-failed-write-loses-a-few-minutes-
 *  of-read-time-worth-not-crashing-the-Reader-over" — LIVE-NOT-STALE
 *  plus FULFILLED-PREDICTION. Verified: ReadingSessionRepositoryImpl
 *  delegates to the legacy `:shared` StatisticsRepository per its
 *  cluster23 §479 postscript — same on-disk `read_minutes` counter,
 *  no duplicate-write hazard. The DIP boundary holds: ReaderViewModel
 *  imports only StartReadingSessionUseCase + EndReadingSessionUseCase
 *  — neither legacy StatisticsRepository nor :platform SettingsFactory
 *  is reachable from :presentation.
 *  Two classifications STAND on their own merits. Original Phase
 *  6.4.x.statistics-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
interface ReadingSessionRepository {

    /**
     * Mark the start of a reading session. Records `now()` in memory; no I/O. Idempotent in the
     * sense that a second [begin] before [end] overwrites the start time with the later value.
     */
    fun begin()

    /**
     * Mark the end of a reading session and persist the elapsed time. Safe to call when no session
     * is in progress (no-ops). Sessions shorter than one minute are dropped.
     */
    suspend fun end()
}
