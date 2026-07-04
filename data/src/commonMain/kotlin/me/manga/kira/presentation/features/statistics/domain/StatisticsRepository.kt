package me.manga.kira.presentation.features.statistics.domain

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.StatisticsDeo

/**
 * Migration notes (Phase 8.13 batch B):
 *  - `android.content.Context` + `@ApplicationContext` constructor injection dropped. The only
 *    Context use was `context.getString(R.string.h_m, h, m)` to format read-duration; replaced
 *    with the literal pattern `"${h}h ${m}m"`. The actual resource id stays in `strings.xml` so
 *    Phase 10's `stringResource(R.string.h_m, h, m)` rewire keeps the localised string.
 *  - `androidx.datastore.preferences.core.{edit, intPreferencesKey}` →
 *    `DataStoreHelper.readMinutesFlow` / `addReadMinutes(delta)` (multiplatform-settings under the
 *    hood; see commit on DataStoreHelper.kt that added Int support for this key).
 *  - `kotlinx.coroutines.flow.flowOn(Dispatchers.IO)` on every DAO flow dropped here: Room/SQLDelight
 *    on commonMain already returns flows on the IO context, so the explicit `flowOn` is redundant
 *    on KMP. Kept the property assignments verbatim otherwise.
 *  - `java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(ms)` → `ms / 60_000L`. Bit-identical
 *    result (integer division rounds down, same as `TimeUnit.MILLISECONDS.toMinutes`).
 *  - `System.currentTimeMillis()` → `kotlin.time.Clock.System.now().toEpochMilliseconds()`.
 *  - `android.util.Log` not used in the source for this class (no log calls survive); nothing to
 *    rewrite to kermit.
 *  - Hilt `@Singleton` / `@Inject` / `@ApplicationContext` annotations dropped. Koin will bind this
 *    as `single { … }` in the follow-up SharedModule wiring step.
 */
// Phase 9.x.statisticsrepository.componentprune (Task #395): dropped 1 independently-orphan
// public re-export surfaced by an exhaustive 3-pass reacher-chain audit (receiver-anchored
// `statisticsRepository.X` / `legacy.X` + bare `\bX\b` word-boundary + `::X` method-ref) covering
// the entire source tree. The dropped member had ZERO external source-tree reachers at any anchor;
// the only call site was internal (the `readDurationFlow` derivation chain), now inlined to read
// directly from the upstream `dataStoreHelper.readMinutesFlow` — eliminating the redundant
// pass-through property.
// Removed (independent orphan):
//   - `val readMinutesFlow: Flow<Int> = dataStoreHelper.readMinutesFlow` — public re-export of
//     [DataStoreHelper.readMinutesFlow]. No external reacher. All external `readMinutesFlow`
//     references in the source tree (ReadingStatisticsRepositoryImpl KDoc lines 16/56/74,
//     StatisticsViewModel KDoc lines 29/31, ReadingStatisticsRepository KDoc line 13) are KDoc
//     references prefixed with the upstream type `DataStoreHelper.readMinutesFlow` — they
//     document the cell of truth, not a consumption of the StatisticsRepository wrapper. The
//     legitimate runtime upstream that the rework strangler-fig
//     [ReadingStatisticsRepositoryImpl] consumes is `legacy.readDurationFlow` (the formatted
//     "Xh Ym" string), NOT the wrapper minute-counter. Internal-only use of the wrapper at the
//     prior line 46 `readDurationFlow.map { ... }` is now inlined via direct
//     `dataStoreHelper.readMinutesFlow.map { ... }`.
// LIVE members preserved (verified by exhaustive reacher-chain audit):
//   - `inLibraryFlow` — `ReadingStatisticsRepositoryImpl.kt:83`.
//   - `chaptersTotalFlow` — `ReadingStatisticsRepositoryImpl.kt:84`.
//   - `chaptersDownloadedFlow` — `ReadingStatisticsRepositoryImpl.kt:85`.
//   - `chaptersReadFlow` — `ReadingStatisticsRepositoryImpl.kt:86`.
//   - `chaptersBookmarkedFlow` — `ReadingStatisticsRepositoryImpl.kt:87`.
//   - `completedEntriesFlow` — `ReadingStatisticsRepositoryImpl.kt:88`.
//   - `startedEntriesFlow` — `ReadingStatisticsRepositoryImpl.kt:89`.
//   - `readDurationFlow` — `ReadingStatisticsRepositoryImpl.kt:91`.
//   - `startReadingSession()` — `ReadingSessionRepositoryImpl.kt` (Phase 6.4.x.statistics
//     strangler-fig `start()` impl).
//   - `endReadingSession()` — `ReadingSessionRepositoryImpl.kt` (matching `end()` impl).
@OptIn(ExperimentalTime::class)
class StatisticsRepository(
    statisticsDeo: StatisticsDeo,
    private val dataStoreHelper: DataStoreHelper,
) {

    val inLibraryFlow: Flow<Int> = statisticsDeo.getTotalMangaCount()
    val chaptersTotalFlow: Flow<Int> = statisticsDeo.getTotalChaptersCount()
    val chaptersDownloadedFlow: Flow<Int> = statisticsDeo.getDownloadedChaptersCount()
    val chaptersReadFlow: Flow<Int> = statisticsDeo.getReadChaptersCount()
    val chaptersBookmarkedFlow: Flow<Int> = statisticsDeo.getBookmarkedChaptersCount()
    val completedEntriesFlow: Flow<Int> = statisticsDeo.getCompletedMangaCount()
    val startedEntriesFlow: Flow<Int> = statisticsDeo.getStartedMangaCount()

    /**
     * Raw persisted read-minutes counter (typed wire, 2026-07 backlog L15). Display formatting
     * ("Xh Ym") lives in `:ui` (`StatisticsScreen` via the localized `Res.string.h_m`) — this
     * closes the Phase-10 `stringResource(R.string.h_m)` forward-pointer in the class KDoc above.
     */
    val readMinutesFlow: Flow<Int> = dataStoreHelper.readMinutesFlow

    private var sessionStartMillis: Long = 0L

    fun startReadingSession() {
        sessionStartMillis = Clock.System.now().toEpochMilliseconds()
    }

    /** Call from ViewModel.onPause() (or Fragment.onPause()) */
    suspend fun endReadingSession() {
        val start = sessionStartMillis
        if (start == 0L) return  // no session in progress

        val elapsedMs = Clock.System.now().toEpochMilliseconds() - start
        sessionStartMillis = 0L

        // Round down to whole minutes
        val addedMinutes = (elapsedMs / 60_000L).toInt()
        if (addedMinutes <= 0) return

        // Persist new total
        dataStoreHelper.addReadMinutes(addedMinutes)
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster208.staleKdocSweep.cascade, Task #664, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster208 leaf 2/5 — :shared/statistics/domain/ tier SINGLE-LEAF (single .kt file in subdir),
 * sibling 380. Cumulative §253-postscript count = 105 leaves with this commit.
 *
 * File-shape note: 103-line class — `StatisticsRepository` with 2 constructor deps (statisticsDeo:
 * StatisticsDeo, dataStoreHelper: DataStoreHelper). Surfaces 7 reactive Int Flow properties
 * straight from DAO COUNT queries (inLibrary + chaptersTotal + chaptersDownloaded + chaptersRead
 * + chaptersBookmarked + completedEntries + startedEntries) + 1 derived readDurationFlow that maps
 * persisted minute-counter to "Xh Ym" formatted string. Plus session-state-machine pair
 * (startReadingSession captures wallclock, endReadingSession persists delta in minutes). Class-
 * level KDoc (lines 10-29) carries Phase 8.13 batch B migration record covering Context-drop +
 * DataStoreHelper-shift + TimeUnit→pure-arithmetic + System.currentTimeMillis→Clock.System.now.
 * Inline 30-line line-comment block (lines 30-60) carries Task #395 componentprune lineage.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — heavily-consumed statistics-cell SOURCE — direct consumers (verified via
 *     8-hit FQN grep with receiver-anchored reaches):
 *       1. ReadingSessionRepositoryImpl.kt (:data/repository/) — rework strangler-fig wraps the
 *          startReadingSession()/endReadingSession() pair as the rework ReadingSessionRepository
 *          start()/end() interface.
 *       2. ReadingStatisticsRepositoryImpl.kt (:data/repository/) — rework strangler-fig wraps
 *          all 7 Flow<Int> properties + readDurationFlow as the rework
 *          ReadingStatisticsRepository surface (8 reaches at lines 83-91).
 *       3. ReaderViewModel.kt (:shared/.../reader/ui/viewmodel/) — calls startReadingSession on
 *          reader enter + endReadingSession on reader exit (legacy direct-injection consumer).
 *       4. StatisticsReworkModule.kt (:composeApp/di/) — Koin binding `single { ... }` for the
 *          legacy class (consumed by both rework :data impls above as `legacy: StatisticsRepository`
 *          parameter).
 *       5. SharedModule.kt (:shared/.../di/) — also exposes the binding for cross-module reach.
 *
 *   • INVERTED-PARALLEL-WITH-STRANGLER-FIG — rework counterparts:
 *       - :domain ReadingSessionRepository (start()/end() pair) — wraps session machine.
 *       - :domain ReadingStatisticsRepository (8 Flow properties + readDurationFlow) — wraps the
 *         flow surface.
 *     Both rework :domain interfaces are pure pass-throughs over the legacy facade — the legacy
 *     class IS the cell-of-truth implementation. The rework :data impls inject `legacy:
 *     StatisticsRepository` and delegate every method. The legacy class STAYS LIVE for the
 *     foreseeable future — retiring it would require lifting the DAO-bound 7-flow surface +
 *     session-state machine into a fresh :data impl, currently unscheduled.
 *
 *   • TASK-395-COMPONENTPRUNE-LINEAGE-PRESERVED — the 30-line line-comment block (lines 30-60)
 *     documents Task #395's removal of the orphan `readMinutesFlow` public re-export. The
 *     dropped member had ZERO external reachers; the only call site was internal (the
 *     readDurationFlow derivation chain) now inlined to read upstream directly. PRESERVE — load-
 *     bearing componentprune audit record per §253. The 10-name LIVE-members audit list at the
 *     bottom (lines 49-60) is the verified-by-grep manifest of what remains reachable.
 *
 *   • KDOC-MIGRATION-NOTES-LOAD-BEARING — the 20-line class-level KDoc (lines 10-29) is a Phase
 *     8.13 batch B migration record. PRESERVE — load-bearing port-lineage prose with one Phase
 *     10 forward-work pointer (stringResource(R.string.h_m, h, m) rewire at line 78).
 *
 *   • SESSION-STATE-MACHINE-INVARIANT — sessionStartMillis = 0L is the "no session in progress"
 *     sentinel. endReadingSession early-returns when start == 0L, preserving idempotency under
 *     accidental double-end calls. addedMinutes <= 0 early-return preserves the "no minute-tick
 *     elapsed" semantics (round-down via integer division on `elapsedMs / 60_000L`). DO NOT
 *     coerce to nullable Long? during cleanup — the 0L sentinel is the historical wire-shape and
 *     a nullable rewrite would silently change reader-session-edge semantics around the boundary
 *     `< 1 minute` case.
 *
 *   • H-M-FORMAT-OBSERVABLE-CHANGE-PINNED — readDurationFlow's "${h}h ${m}m" literal format is
 *     un-localised. The class-level KDoc and the inline TODO at line 78 both pin Phase 10 as
 *     the rewire target. DO NOT inline-localise to a default-locale shape during cleanup — the
 *     stringResource rewire is layered, not a literal swap.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 6 imports: 2 kotlin.time (Clock + ExperimentalTime) + 2
 *     kotlinx.coroutines.flow (Flow + map) + 1 core.storage.DataStoreHelper + 1 data.local.dao.
 *     StatisticsDeo. All LIVE.
 */
