package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.statistics.ReadingStatistics

/**
 * Reactive snapshot of the user's reading statistics.
 *
 * Phase 7.x.statistics rework. The `:data` impl combines 8 individual flows from the legacy
 * `StatisticsRepository` (in-library count, chapter counts, entry counts, read-minutes
 * counter) into a single [ReadingStatistics] snapshot per upstream emission. The legacy
 * `StatisticsRepository` remains the cell of truth for the underlying Room queries +
 * `DataStoreHelper.readMinutesFlow` reads — same strangler-fig posture as
 * [ReadingSessionRepository] (Phase 6.4.x.statistics).
 *
 * Contract §6 SRP: owns ONE rule — "expose the 8 reading-statistics numbers as a single reactive
 * snapshot". Where each number is computed (Room DAO queries) and where the minute counter lives
 * (`DataStoreHelper`) are `:data` concerns; how the minutes render ("Xh Ym") is a `:ui` concern
 * (typed wire, 2026-07 backlog L15).
 *
 * Contract §6 ISP: read-only. No "increment read minutes" / "mark chapter read" methods — those
 * are owned by other interfaces (e.g., [ReadingSessionRepository] for session minutes,
 * `ReadProgressRepository` for chapter-read flags). The 8 numbers this interface exposes are pure
 * projections over data those other interfaces write.
 *
 * Contract §6 DIP: consumers ([me.manga.kira.domain.usecase.statistics.ObserveReadingStatisticsUseCase],
 * and through it the rework `StatisticsViewModel`) depend on this interface, never on the legacy
 * `StatisticsRepository` or the underlying DAO. Koin binds the impl at the composition root in
 * `statisticsReworkModule`.
 *
 * Lifecycle expectation: the impl is bound as a `single` (matching the upstream legacy
 * `StatisticsRepository`'s `single` lifecycle). A `factory` would resubscribe the 8 flows on each
 * resolution — wasteful for a read-only aggregate that's shared across the app's lifetime.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster140.staleKdocSweep.cascade,
 * Task #596, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-thirty-ninth sibling of the cluster57-139
 * sweep — second file of the wave-25 second-cluster 5-leaf-repository
 * batch alongside PageProgressRepository):
 *  (a) "Reactive-snapshot-of-the-user-reading-statistics + Phase-7.x.
 *  statistics-rework + The-:data-impl-combines-8-individual-flows-from-
 *  the-legacy-:shared-StatisticsRepository-in-library-count-chapter-
 *  counts-entry-counts-read-duration-string-into-a-single-Reading-
 *  Statistics-snapshot-per-upstream-emission + The-legacy-Statistics-
 *  Repository-remains-the-cell-of-truth-for-the-underlying-Room-queries-
 *  plus-DataStoreHelper.readMinutesFlow-reads + same-strangler-fig-
 *  posture-as-ReadingSessionRepository-Phase-6.4.x.statistics + Contract-
 *  §6-SRP-owns-ONE-rule-expose-the-8-reading-statistics-numbers-as-a-
 *  single-reactive-snapshot + Where-each-number-is-computed-Room-DAO-
 *  queries-where-the-minute-counter-lives-DataStoreHelper-and-how-the-
 *  Xh-Ym-string-is-formatted-are-all-:data-or-:shared-concerns +
 *  Contract-§6-ISP-read-only + No-increment-read-minutes-or-mark-
 *  chapter-read-methods-those-are-owned-by-other-interfaces-e.g.-
 *  ReadingSessionRepository-for-session-minutes-ReadProgressRepository-
 *  for-chapter-read-flags + The-8-numbers-this-interface-exposes-are-
 *  pure-projections-over-data-those-other-interfaces-write" — LIVE-NOT-
 *  STALE plus FULFILLED-PREDICTION plus FORECAST-NOT-YET-FULFILLED-
 *  (post-route-swap-legacy-:shared-StatisticsRepository-retire). Verified
 *  via recursive grep: ReadingStatisticsRepository is consumed by
 *  ObserveReadingStatisticsUseCase (the :domain caller) plus Reading-
 *  StatisticsRepositoryImpl (the :data combine-8-flows impl) plus
 *  StatisticsReworkModule (the Koin single binding) plus Statistics-
 *  ReworkScreenRoute. The cross-strangler-fig over :shared/Statistics-
 *  Repository remains LIVE — the legacy :shared StatisticsRepository
 *  retains its 8 individual flows + the legacy onboarding StatisticsRoute
 *  still consumes them; both routes accumulate into the same Room +
 *  DataStoreHelper-readMinutes counters. The cross-strangler-fig retire
 *  remains forecast.
 *  (b) "Contract-§6-DIP-consumers-ObserveReadingStatisticsUseCase-and-
 *  through-it-the-rework-StatisticsViewModel-depend-on-this-interface-
 *  never-on-the-legacy-StatisticsRepository-or-the-underlying-DAO +
 *  Koin-binds-the-impl-at-the-composition-root-in-statisticsRework-
 *  Module + Lifecycle-expectation-the-impl-is-bound-as-a-single-
 *  matching-the-upstream-legacy-StatisticsRepository-single-lifecycle +
 *  A-factory-would-resubscribe-the-8-flows-on-each-resolution-wasteful-
 *  for-a-read-only-aggregate-that-is-shared-across-the-app-lifetime" —
 *  LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified: ObserveReading-
 *  StatisticsUseCase depends only on this interface — no :data import
 *  reach + no :shared StatisticsRepository reach. StatisticsRework-
 *  Module binds ReadingStatisticsRepositoryImpl as `single` per the
 *  predicted lifecycle. The "rework StatisticsViewModel" consumes only
 *  the :domain use case — no direct :shared reach.
 *  Two classifications STAND on their own merits. Original Phase 7.x.
 *  statistics-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
interface ReadingStatisticsRepository {

    /**
     * Reactive snapshot of the 8 reading-statistics numbers. Emits an updated [ReadingStatistics]
     * whenever any of the underlying counters changes (e.g., user finishes reading a chapter →
     * `chaptersRead` + `entriesStarted` + possibly `entriesCompleted` all update; the upstream
     * `combine` coalesces those into a single emission).
     */
    fun observe(): Flow<ReadingStatistics>
}
