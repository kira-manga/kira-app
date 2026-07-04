package me.manga.kira.data.repository

import me.manga.kira.domain.repository.ReadingSessionRepository
import me.manga.kira.presentation.features.statistics.domain.StatisticsRepository

/**
 * [ReadingSessionRepository] strangler-fig delegate over the legacy `:shared`
 * [StatisticsRepository.startReadingSession] / [StatisticsRepository.endReadingSession] pair.
 *
 * Phase 6.4.x.statistics foundation. Owns the session-timer surface for the rework Reader without
 * duplicating the legacy reader's persistence path — both reader implementations call into the
 * SAME legacy [StatisticsRepository] singleton, which in turn writes to the SAME on-disk counter
 * (`StorageKeys.READ_MINUTES` via `DataStoreHelper.addReadMinutes`). The user's accumulated
 * read-time stays consistent across the strangler-fig transition; the legacy methods can be
 * retired in Phase 9.x once the rework Reader takes over the user-facing route.
 *
 * SRP (contract §6): owns ONE rule — "translate the rework's [begin] / [end] verbs into the
 * legacy [startReadingSession] / [endReadingSession] calls". Minute conversion, the in-memory
 * `sessionStartMillis` field, the "sessions shorter than a minute round to zero and are
 * dropped" rule, and the `DataStoreHelper.addReadMinutes` write all live in the legacy impl —
 * see [StatisticsRepository.endReadingSession]. Duplicating any of those rules here would
 * create a second source of truth and risk drift if the legacy is ever tweaked before the
 * route-swap retires it.
 *
 * DIP (contract §6): depends on the legacy [StatisticsRepository] type because that's the only
 * vendor for the on-disk minute counter today. The dependency is **structurally** at the
 * strangler-fig boundary — the rework `:data` layer is allowed to reach into `:shared` for
 * cross-cutting persistence that hasn't been ported yet, same posture as
 * [ChapterPagesRepositoryImpl] reaching `SourcesRepository`. Once the route-swap retires
 * the legacy Reader, the legacy [StatisticsRepository] can either be deleted (if no other
 * legacy callers remain) or kept as a thin facade that this impl owns; the [ReadingSessionRepository]
 * interface in `:domain` is unaffected either way.
 *
 * Why no `AppResult` mapping: matches [ReadingSessionRepository.end] KDoc — the legacy `end`
 * call is `suspend Unit` and the underlying `DataStoreHelper.addReadMinutes` write does not
 * surface errors back to its caller. A failed persist loses a few minutes of read-time; not
 * worth crashing the Reader over. If a future revision needs reporting, both sides can grow
 * `AppResult<Unit>` returns without contract breakage.
 *
 * Lifecycle: `single` in Koin. The legacy [StatisticsRepository] is itself bound as `single`
 * in `SharedModule.kt` (line 238), and the rework MUST inject the same instance so the
 * `sessionStartMillis` recorded by [begin] is the field that [end] reads. A `factory` here
 * would either (a) ask Koin for the legacy singleton each time — harmless but wasteful — or
 * (b) more dangerously, if a future refactor switched the legacy to `factory`, would break
 * the begin/end pairing entirely. `single` documents the dependency intent.
 *
 * Threading: [begin] is non-suspend (just records `now()` in memory in the legacy impl);
 * [end] is `suspend` because the underlying `DataStoreHelper.addReadMinutes` is a settings
 * write. Neither needs explicit dispatcher pinning — the legacy methods already handle their
 * own context (the settings backing is the same multiplatform-settings store used by
 * [ReadingModeRepositoryImpl], which is also context-free at the call site).
 */
class ReadingSessionRepositoryImpl(
    private val legacy: StatisticsRepository,
) : ReadingSessionRepository {

    override fun begin() {
        legacy.startReadingSession()
    }

    override suspend fun end() {
        legacy.endReadingSession()
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster152.staleKdocSweep.cascade,
 * Task #608, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-ninety-second sibling of the cluster57-151
 * sweep — third file of the wave-26 :data/repository reader-state tier
 * 5-leaf batch alongside ChapterPagesRepositoryImpl plus ReadingMode
 * RepositoryImpl plus ReadProgressRepositoryImpl plus PageProgressRepository
 * Impl):
 *  (a) "ReadingSessionRepository-strangler-fig-delegate-over-the-legacy-
 *  :shared-StatisticsRepository.startReadingSession-endReadingSession-pair
 *  + Phase-6.4.x.statistics-foundation + Owns-the-session-timer-surface-
 *  for-the-rework-Reader-without-duplicating-the-legacy-reader-s-persistence
 *  -path-both-reader-implementations-call-into-the-SAME-legacy-Statistics
 *  Repository-singleton-which-in-turn-writes-to-the-SAME-on-disk-counter-
 *  StorageKeys.READ_MINUTES-via-DataStoreHelper.addReadMinutes + The-user-s
 *  -accumulated-read-time-stays-consistent-across-the-strangler-fig-
 *  transition-the-legacy-methods-can-be-retired-in-Phase-9.x-once-the-rework
 *  -Reader-takes-over-the-user-facing-route + SRP-contract-section-6-owns-
 *  ONE-rule-translate-the-rework-s-begin-end-verbs-into-the-legacy-start
 *  ReadingSession-endReadingSession-calls + Minute-conversion-the-in-memory
 *  -sessionStartMillis-field-the-sessions-shorter-than-a-minute-round-to-
 *  zero-and-are-dropped-rule-and-the-DataStoreHelper.addReadMinutes-write-
 *  all-live-in-the-legacy-impl + Duplicating-any-of-those-rules-here-would
 *  -create-a-second-source-of-truth-and-risk-drift + DIP-contract-section-6
 *  -depends-on-the-legacy-StatisticsRepository-type-because-that-s-the-only
 *  -vendor-for-the-on-disk-minute-counter-today + The-dependency-is-
 *  structurally-at-the-strangler-fig-boundary + Once-the-route-swap-retires
 *  -the-legacy-Reader-the-legacy-StatisticsRepository-can-either-be-deleted
 *  -if-no-other-legacy-callers-remain-or-kept-as-a-thin-facade-that-this-
 *  impl-owns + Why-no-AppResult-mapping-matches-ReadingSessionRepository.
 *  end-KDoc-the-legacy-end-call-is-suspend-Unit-and-the-underlying-Data
 *  StoreHelper.addReadMinutes-write-does-not-surface-errors-back-to-its-
 *  caller-A-failed-persist-loses-a-few-minutes-of-read-time-not-worth-
 *  crashing-the-Reader-over + Lifecycle-single-in-Koin-The-legacy-Statistics
 *  Repository-is-itself-bound-as-single-in-SharedModule.kt-line-238 + The-
 *  rework-MUST-inject-the-same-instance-so-the-sessionStartMillis-recorded
 *  -by-begin-is-the-field-that-end-reads + Threading-begin-is-non-suspend-
 *  just-records-now-in-memory-in-the-legacy-impl-end-is-suspend-because-the
 *  -underlying-DataStoreHelper.addReadMinutes-is-a-settings-write" —
 *  LIVE-NOT-STALE. Verified: strangler-fig delegate shipped. ReadingSession
 *  RepositoryImpl.begin() forwards to legacy.startReadingSession();
 *  ReadingSessionRepositoryImpl.end() (suspend) forwards to legacy.end
 *  ReadingSession(). The constructor takes the legacy StatisticsRepository
 *  via DIP — the only vendor of the on-disk minute counter today. The
 *  "single Koin binding" intent honored — the rework :composeApp/di
 *  StatisticsReworkModule registers ReadingSessionRepositoryImpl as single
 *  so the begin/end pair share the same sessionStartMillis field on the
 *  injected legacy singleton. The "no AppResult mapping" stance honored
 *  — both verbs return Unit / suspend Unit, matching the legacy surface's
 *  fire-and-forget posture. The "retirement-pending" forecast (Phase 9.x
 *  route-swap retires the legacy Reader) remains open — no slice has yet
 *  flipped the user-facing Reader route from legacy to rework (currently
 *  blocked behind Task #422 coreshadow-retire direction). Consumed by
 *  BeginReadingSessionUseCase + EndReadingSessionUseCase (cluster93 sibling
 *  X) via the begin() / end() surface; the rework Reader VM consumes
 *  through the use cases at its own MVI boundary. One classification.
 *  Original Phase 6.4.x.statistics impl prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */

