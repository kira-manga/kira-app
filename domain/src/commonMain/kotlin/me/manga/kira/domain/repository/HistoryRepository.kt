package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.history.HistoryEntry

/**
 * Reactive reading-history access — observe the list, delete a single entry, clear all, and
 * record a row on chapter open.
 *
 * Phase 7.x.history rework. The `:data` impl strangler-fig delegates to the legacy `:shared`
 * `HistoryRepository` (which wraps `HistoryDao` + the `history_items` Room table). The legacy
 * facade remains the cell of truth for per-manga queries — owned by the per-manga-detail flows
 * which haven't been reworked yet. Phase 9.x route-swap retires the legacy `Screen.History`
 * binding; the legacy repository itself may stay as the underlying facade until the per-manga
 * slices fold in.
 *
 * Contract §6 SRP: owns ONE rule — "expose reading-history as a read + delete + record surface".
 * `update` / per-manga queries are intentionally NOT on this interface (ISP) — the History screen
 * never needs them; the [record] verb is the rework Reader's insert path (it landed on this
 * interface in Reader-convergence R3a, replacing the legacy-VM insert).
 *
 * Contract §6 ISP: four methods — one read (observe), two delete (single + all), one record. No
 * `getById`, no `getByManga` — the History screen renders the full list (one delete-button per
 * row) and a single clear-all action; per-entry lookup is not needed because the screen already
 * holds the [HistoryEntry] at click time.
 *
 * Contract §6 DIP: consumers (`ObserveHistoryUseCase`, `DeleteHistoryEntryUseCase`,
 * `DeleteAllHistoryUseCase`, and through them the rework `HistoryViewModel`) depend on this
 * interface, never on the legacy `HistoryRepository` or the underlying DAO. Koin binds the impl
 * at the composition root in `historyReworkModule`.
 *
 * Lifecycle expectation: the impl is bound as a `single` (matching the upstream legacy
 * `HistoryRepository`'s `single` lifecycle from `SharedModule`). A `factory` would resubscribe
 * the upstream `getAllHistory()` flow on each resolution — wasteful for a read-mostly surface
 * shared across the app's lifetime.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster26.staleKdocSweep.cascade,
 * Task #482, 2026-05-28): one fulfilled-forecast citation appears
 * above:
 *  - Lines 12-14 ("Phase 9.x route-swap retires the legacy
 *    `Screen.History` binding; the legacy repository itself may stay as
 *    the underlying facade until the per-manga and Reader slices fold
 *    in"). PARTIALLY-FULFILLED-INVERSION — Phase 7.x.history.swap
 *    (§288) re-pointed `Screen.History`'s rendering adapter to the
 *    rework `HistoryScreen` (7.x-prefixed, earlier than the §253-era
 *    forecast predicted); Phase 9.x.history.legacyui.retire (§357
 *    sweep, "drop 2-file unreachable legacy HistoryScreen +
 *    HistoryItem") deleted the orphan legacy `:shared` HistoryScreen
 *    UI + `HistoryItem` row component. HOWEVER — the legacy `:shared`
 *    `HistoryRepository` facade + `HistoryDao` + `history_items` Room
 *    table STILL EXIST as the cell of truth that the rework `:data`
 *    impl delegates to via constructor injection (the rework's
 *    read+delete surface forwards to the legacy facade per the
 *    strangler-fig posture). The forecast resolved to the "legacy
 *    repository itself may stay as the underlying facade" branch —
 *    the per-manga insert path (Reader's `lastReadDate` write on
 *    chapter open) + per-manga `getByManga` queries are owned by the
 *    Reader and per-manga-detail flows which remain on the legacy
 *    facade per the strangler-fig boundary. Only the consumer-side
 *    History screen + row component were retired across §§288 + 357;
 *    the legacy facade remains the rework's read+delete backbone.
 *    The SRP / ISP / DIP / lifecycle sub-sections all stand on their
 *    own merits past the §§288 + 357 fulfilled landings. The
 *    HistoryRepository interface remains LIVE as the canonical
 *    rework read+delete surface. Original §253-era prose preserved
 *    verbatim per the audit-trail-preservation convention — the
 *    citation is historical record of the design lineage including
 *    the deferred-route-swap forecast that was subsequently fulfilled
 *    across §§288 + 357.
 */
interface HistoryRepository {

    /**
     * Reactive list of all history entries, sorted by `lastReadDate` descending. Emits an updated
     * list on every Room write to the `history_items` table.
     */
    fun observeHistory(): Flow<List<HistoryEntry>>

    /**
     * Delete a single history entry. Fire-and-forget — the upstream [observeHistory] flow re-emits
     * with the entry removed once the Room transaction commits.
     *
     * Takes the full [HistoryEntry] (not just an id) because the underlying legacy
     * `HistoryRepository.deleteHistory(historyItemD: HistoryItemD)` is entity-based, and the
     * mapper round-trip (`HistoryEntry → HistoryItemD`) is cheap (no DB lookup, just field
     * copies). The alternative (`deleteEntry(id: Long)` → fetch entity by id → delete) would
     * cost an extra DAO round-trip per delete with no payoff.
     */
    suspend fun deleteEntry(entry: HistoryEntry)

    /**
     * Clear all history entries. Fire-and-forget — the upstream [observeHistory] flow re-emits
     * with an empty list once the Room `DELETE FROM history_items` transaction commits.
     */
    suspend fun deleteAll()

    /**
     * Record a reading-history row for [chapter] of [manga] — the rework Reader's "record on
     * chapter open" verb (Reader-convergence R3a). The `:data` impl strangler-fig maps the
     * rework [me.manga.kira.domain.model.Manga] + [me.manga.kira.domain.model.Chapter]
     * to the legacy `HistoryItemD` and forwards to the legacy facade's `insertHistory`, which
     * upserts keyed by `mangaUrl` (one row per manga, last-read chapter overwrites). The legacy
     * `HistoryDao` stays the cell of truth so the rework History screen — which observes the
     * same `history_items` table via [observeHistory] — re-emits automatically.
     *
     * **Incognito is NOT gated here.** The interface stays mechanical (it always records); the
     * incognito decision lives in `RecordHistoryUseCase`, which no-ops when incognito is ON.
     * Keeping the gate out of the repository preserves the legacy posture (the legacy
     * `HistoryViewModel` gated at the call site, not in the store) and keeps this surface a pure
     * write.
     *
     * Fire-and-forget — the upstream [observeHistory] flow re-emits with the upserted row once
     * the Room transaction commits.
     */
    suspend fun record(manga: Manga, chapter: Chapter)
}
