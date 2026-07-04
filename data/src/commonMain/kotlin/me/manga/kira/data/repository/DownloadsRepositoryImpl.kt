package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.manga.kira.data.mapper.toDomain
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.repository.DownloadsRepository
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository

/**
 * [DownloadsRepository] strangler-fig delegate over the legacy `:shared`
 * [DownloadRepository.observeAllDownloads] flow.
 *
 * Phase 7.x.downloads.foundation rework (data layer). Maps each
 * upstream `List<ChapterDownloadEntity>` emission to a
 * `List<DownloadedChapter>` via the per-entity mapper in
 * `DownloadsMappers.kt`. The legacy `DownloadRepository` (Android:
 * WorkManager-backed `DownloadRepositoryImpl`; iOS + Desktop:
 * `CoroutineDownloadRepositoryImpl`, Phase 8.13 batch C + Phase 14.x)
 * remains the cell of truth — Room is the source of records, this
 * impl is a pure projection.
 *
 * **SRP (contract §6)**: owns ONE rule — "project the legacy
 * `observeAllDownloads()` emissions into the rework `:domain` shape".
 * No filtering, no sorting, no bucket partitioning — those are
 * `:presentation` concerns. Same posture as [HistoryRepositoryImpl]
 * and [ReadingStatisticsRepositoryImpl]: thin strangler-fig wrapper,
 * the bulk of the logic lives in the legacy facade until Phase 9.x
 * route-swap retires it.
 *
 * **DIP (contract §6)**: depends on the legacy [DownloadRepository]
 * interface because that's the only vendor for the download-queue
 * flow today. The dependency is structurally at the strangler-fig
 * boundary — the rework `:data` layer is allowed to reach into
 * `:shared` for cross-cutting persistence that hasn't been ported yet,
 * same posture as [ReadingStatisticsRepositoryImpl] and
 * [ReadingSessionRepositoryImpl] reaching their respective legacy
 * classes.
 *
 * **Why `.map { list -> list.map { it.toDomain() } }` and not a
 * dedicated `toDomain(list: List<ChapterDownloadEntity>)`**: the inner
 * `.map { it.toDomain() }` is one line and reads obviously. A dedicated
 * list-mapper would be a near-empty wrapper around `List.map` — net
 * indirection without information gain. The inline form keeps the
 * mapping rule colocated with the flow operation.
 *
 * **Empty-list emissions are valid**: when the legacy Room query
 * returns no rows (no downloads queued or finished), the upstream
 * emits an empty `List<ChapterDownloadEntity>` which this impl projects
 * to an empty `List<DownloadedChapter>`. The `:presentation` layer
 * handles the empty case in its state-projection (initial-load
 * placeholder); this layer doesn't differentiate "loading" vs "empty"
 * because the flow itself doesn't carry a loading sentinel.
 *
 * **Initial-emission gap**: between subscription and the first
 * emission, the rework VM holds a default `DownloadsState(isLoading =
 * true, ...)`. Once the first list emission lands (which may be empty
 * — that's fine, Room's `Flow<List<...>>` emits the current contents
 * on subscription including the empty case), the VM flips
 * `isLoading = false`.
 *
 * **Lifecycle**: `single` in Koin (per [DownloadsRepository] KDoc).
 * The upstream legacy `DownloadRepository` is itself bound as `single`
 * for the same reason — the WorkManager observer / Room query observer
 * are expensive to spin up per subscriber.
 *
 * **Threading**: no explicit dispatcher pinning. The legacy
 * `observeAllDownloads()` already emits on the IO context (Room's
 * `Flow<List<...>>` is dispatcher-aware). `.map { ... }` is a pure
 * transform on whatever dispatcher the upstream emits on.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster23.staleKdocSweep.cascade,
 * Task #479, 2026-05-28): one fulfilled-forecast citation appears
 * above:
 *  - Lines 28-29 ("thin strangler-fig wrapper, the bulk of the
 *    logic lives in the legacy facade until Phase 9.x route-swap
 *    retires it"). PARTIALLY-FULFILLED-INVERSION — Phase
 *    7.x.downloads.swap (§295) re-pointed the Settings Downloads
 *    row + `Screen.DownloadsScreen` to the rework `DownloadsScreen`
 *    already (7.x-prefixed, earlier than predicted); Phase
 *    9.x.downloads.legacyui.retire (§352) deleted the legacy
 *    `:shared` `DownloadsScreen.kt` UI; Phase
 *    9.x.downloadvmv2.retire (§439) deleted the cascade-orphan
 *    legacy download VM; Phase 9.x.downloadrepository.componentprune
 *    .cascade (§440) + 9.x.chapterdownloaddao.componentprune.cascade
 *    (§441) pruned orphan members. HOWEVER — the legacy
 *    [DownloadRepository] interface + its WorkManager-backed
 *    Android `DownloadRepositoryImpl` + iOS/Desktop
 *    `CoroutineDownloadRepositoryImpl` + the underlying
 *    `ChapterDownloadDao` Room entity STILL EXIST as the cell of
 *    truth that this impl delegates to via `legacy = get()`
 *    (verified at the constructor signature below — `private val
 *    legacy: DownloadRepository`). The "Phase 9.x route-swap retires
 *    it" forecast happened as a §295 7.x-prefixed swap (earlier than
 *    predicted) followed by §§352 + 439 + 440 + 441 9.x retires.
 *    Only consumer-side surfaces (legacy UI + cascade-orphan VM +
 *    orphan members) were retired; the underlying WorkManager-backed
 *    transport remains LIVE as the rework's download-queue backbone.
 *    Mirror of §477 downloads cluster + §475-478 partially-
 *    fulfilled-inversion precedent.
 * The SRP / DIP / inline-list-map-rationale / empty-list-emissions
 * / initial-emission-gap / lifecycle / threading sub-sections all
 * stand on their own merits past the §§295 + 352 + 439 + 440 + 441
 * fulfilled landings. The DownloadsRepositoryImpl remains LIVE as
 * the canonical strangler-fig delegate for the rework downloads
 * read-side surface. Original §253-era prose preserved verbatim per
 * the audit-trail-preservation convention — the citation is
 * historical record of the design lineage including the
 * deferred-route-swap forecast that was subsequently fulfilled
 * across §§295 + 352 + 439 + 440 + 441.
 */
class DownloadsRepositoryImpl(
    private val legacy: DownloadRepository,
) : DownloadsRepository {

    override fun observeAll(): Flow<List<DownloadedChapter>> =
        // distinctUntilChanged (2026-07 audit): dedupe structurally-equal Room re-emissions before
        // the per-row domain mapping (same family as LibraryRepositoryImpl.observeLibrary).
        legacy.observeAllDownloads().distinctUntilChanged().map { list -> list.map { it.toDomain() } }
}
