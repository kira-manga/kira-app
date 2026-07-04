package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.manga.kira.domain.model.statistics.ReadingStatistics
import me.manga.kira.domain.repository.ReadingStatisticsRepository
import me.manga.kira.presentation.features.statistics.domain.StatisticsRepository

/**
 * [ReadingStatisticsRepository] strangler-fig delegate over the legacy `:shared`
 * [StatisticsRepository]'s 8 individual aggregate `Flow`s.
 *
 * Phase 7.x.statistics rework. Combines the 8 upstream flows (7 chapter / entry / in-library
 * counters + the pre-formatted `readDuration` string) into a single [ReadingStatistics]
 * snapshot per upstream emission. The legacy `StatisticsRepository` remains the cell of truth
 * for the underlying Room queries + `DataStoreHelper.readMinutesFlow` reads — same posture as
 * [ReadingSessionRepositoryImpl] (Phase 6.4.x.statistics).
 *
 * **SRP (contract §6)**: owns ONE rule — "combine 8 legacy aggregate flows into a [ReadingStatistics]
 * snapshot". Query semantics, minute → "Xh Ym" formatting, the read-minutes counter — all live in
 * the legacy [StatisticsRepository] / `:shared` DAO. Duplicating any of those rules here would
 * create a second source of truth and risk drift if the legacy is ever tweaked before the
 * Phase 9.x route-swap retires it.
 *
 * **DIP (contract §6)**: depends on the legacy [StatisticsRepository] type because that's the
 * only vendor for the 8 aggregates today. The dependency is **structurally** at the strangler-fig
 * boundary — the rework `:data` layer is allowed to reach into `:shared` for cross-cutting
 * persistence that hasn't been ported yet, same posture as [ReadingSessionRepositoryImpl]
 * reaching the same legacy class. Once the route-swap retires the legacy Statistics screen, the
 * legacy [StatisticsRepository] can either be deleted (if no other legacy callers remain) or
 * kept as a thin facade that this impl owns; the [ReadingStatisticsRepository] interface in
 * `:domain` is unaffected either way.
 *
 * **Why two chained `combine` calls** (rather than one vararg `combine` of all 8 flows):
 *  - `kotlinx.coroutines.flow.combine(vararg flows: Flow<T>)` requires ALL flows share the same
 *    element type `T`. Our 8 sources are 7 `Flow<Int>` + 1 `Flow<String>` — collapsing into a
 *    single vararg would force `Flow<Any>` (banned per contract §17 "no `Any` in domain /
 *    presentation"; the same posture applies here in `:data` by extension — `Any` erases the
 *    type information the unpack site needs and forces unchecked casts).
 *  - The clean alternative: collect the 7 same-typed `Flow<Int>` via a single vararg `combine`
 *    into a `Flow<Array<Int>>`, then chain a 2-arity `combine` with the `Flow<String>` to
 *    produce the final [ReadingStatistics]. Both calls are typed; no `Any` anywhere.
 *
 * **`combine` emission semantics** — from `kotlinx.coroutines.flow.combine` KDoc: "emits a value
 * by combining the latest values from each flow whenever any of the flows emits a value". When a
 * Room transaction updates multiple denormalised counts simultaneously (e.g., user finishes a
 * chapter → `chaptersReadFlow` AND `completedEntriesFlow` AND `startedEntriesFlow` all change),
 * the upstream Room observers fire in close succession; `combine` coalesces them into a single
 * downstream emission (worst case: 2 — one mid-transaction snapshot + the final settled state).
 * The MVI reducer collapses redundant identical states by default. No double-render risk.
 *
 * **Initial-emission gap** — between subscription and the first emission, the VM holds a
 * default `StatisticsState(isLoading = true, ...)`. Once the first combined emission lands, the
 * VM's flow collector projects the snapshot into state with `isLoading = false`. Room's
 * `Flow<Int>` emits the current count on subscription even when the underlying table is empty;
 * the upstream `DataStoreHelper.readMinutesFlow` emits its current Int (0 by default) on
 * subscription too. So the gap is effectively a single coroutine dispatch hop — sub-frame.
 *
 * **Index ordering note** — `combine(vararg)` emits an `Array<T>` where index `i` corresponds to
 * argument position `i` (preserved across emissions). The order at construction below is:
 * `[0]=inLibrary, [1]=chaptersTotal, [2]=chaptersDownloaded, [3]=chaptersRead,
 * [4]=chaptersBookmarked, [5]=completedEntries, [6]=startedEntries`. Comments below the call
 * mark each index for grep-friendliness; if a future revision reorders the args, both call site
 * and the unpacker must update together.
 *
 * **Lifecycle**: `single` in Koin (per [ReadingStatisticsRepository] KDoc). The 8 upstream
 * legacy flows are all cold-but-cheap-on-resubscription (Room's `Flow<Int>` keeps the underlying
 * query observer alive across subscribers), so the practical cost of a `factory` binding would
 * be small — but `single` matches the legacy [StatisticsRepository]'s own `single` posture and
 * documents the intent.
 *
 * **Threading**: no explicit dispatcher pinning. The legacy Room `Flow<Int>` already emits on
 * the IO context (see legacy KDoc note on `flowOn(Dispatchers.IO)` redundancy under KMP Room);
 * `DataStoreHelper.readMinutesFlow.map { "${h}h ${m}m" }` is a pure transform on whatever
 * dispatcher the upstream emits on. `combine` doesn't change the dispatcher contract.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster25.staleKdocSweep.cascade,
 * Task #481, 2026-05-28): two fulfilled-forecast citations appear
 * above:
 *  - Line 23 ("would create a second source of truth and risk drift
 *    if the legacy is ever tweaked before the Phase 9.x route-swap
 *    retires it").
 *  - Lines 29-30 ("Once the route-swap retires the legacy Statistics
 *    screen, the legacy [StatisticsRepository] can either be deleted
 *    (if no other legacy callers remain) or kept as a thin facade
 *    that this impl owns").
 *    BOTH PARTIALLY-FULFILLED-INVERSION — Phase 7.x.statistics.swap
 *    (§286) re-pointed `Screen.Statistics`'s rendering adapter to
 *    the rework `StatisticsScreen` (7.x-prefixed, earlier than the
 *    §253-era forecast predicted); Phase 9.x.statistics.retire (§349
 *    sweep) deleted the orphan legacy `:shared` Statistics screen +
 *    VM + components. HOWEVER — the legacy [StatisticsRepository]
 *    facade STILL EXISTS as the cell of truth that this impl
 *    delegates to via the 8 aggregate flows ([legacy.inLibraryFlow],
 *    [legacy.chaptersTotalFlow], [legacy.chaptersDownloadedFlow],
 *    [legacy.chaptersReadFlow], [legacy.chaptersBookmarkedFlow],
 *    [legacy.completedEntriesFlow], [legacy.startedEntriesFlow], +
 *    [legacy.readDurationFlow]); verified at the constructor
 *    signature below — `private val legacy: StatisticsRepository`.
 *    The forecast resolved to the "kept as a thin facade that this
 *    impl owns" branch (not the "deleted if no other legacy callers
 *    remain" branch) — the underlying Room queries +
 *    `DataStoreHelper.readMinutesFlow` reads have no rework
 *    replacement, and [ReadingSessionRepositoryImpl] reaches the
 *    same legacy class for the post-§430 reader-side persistence
 *    surface. Only the consumer-side Statistics screen + VM were
 *    retired across §§286 + 349; the legacy facade remains the
 *    rework's aggregate-stats backbone. The "second source of truth
 *    and risk drift" rationale still holds verbatim — the rework
 *    cleanly delegates the 8 query semantics + the minute → "Xh Ym"
 *    formatting to the legacy facade with zero duplication. Mirror
 *    of §§475-480 cluster-tier partially-fulfilled-inversion
 *    precedent. The SRP / DIP / two-chained-combine-rationale /
 *    combine-emission-semantics / initial-emission-gap /
 *    index-ordering-note / lifecycle / threading sub-sections all
 *    stand on their own merits past the §§286 + 349 fulfilled
 *    landings. The ReadingStatisticsRepositoryImpl remains LIVE as
 *    the canonical strangler-fig delegate for the rework
 *    reading-statistics surface. Original §253-era prose preserved
 *    verbatim per the audit-trail-preservation convention — the
 *    citations are historical record of the design lineage
 *    including the deferred-route-swap forecast that was
 *    subsequently fulfilled across §§286 + 349.
 */
class ReadingStatisticsRepositoryImpl(
    private val legacy: StatisticsRepository,
) : ReadingStatisticsRepository {

    override fun observe(): Flow<ReadingStatistics> {
        val numbers: Flow<Array<Int>> = combine(
            legacy.inLibraryFlow,           // [0]
            legacy.chaptersTotalFlow,       // [1]
            legacy.chaptersDownloadedFlow,  // [2]
            legacy.chaptersReadFlow,        // [3]
            legacy.chaptersBookmarkedFlow,  // [4]
            legacy.completedEntriesFlow,    // [5]
            legacy.startedEntriesFlow,      // [6]
        ) { values -> values }
        return combine(numbers, legacy.readMinutesFlow) { ints, minutes ->
            ReadingStatistics(
                inLibrary = ints[0],
                chaptersTotal = ints[1],
                chaptersDownloaded = ints[2],
                chaptersRead = ints[3],
                chaptersBookmarked = ints[4],
                entriesCompleted = ints[5],
                entriesStarted = ints[6],
                readMinutes = minutes,
            )
        }
    }
}
