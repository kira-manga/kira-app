package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.manga.kira.core.logging.FlowLog
import me.manga.kira.data.local.dao.HistoryDao
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.mapper.toDomain
import me.manga.kira.data.mapper.toEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.history.HistoryEntry
import me.manga.kira.domain.repository.HistoryRepository

/**
 * [HistoryRepository] implementation backed directly by the Room [HistoryDao].
 *
 * Phase 7.x.history rework. Translates between the rework `:domain` model ([HistoryEntry]) and the
 * Room entity ([HistoryItemD]) via the mapper file `HistoryMappers.kt`, then forwards each call to
 * the [HistoryDao]. The DAO is the cell of truth for the `history_items` Room queries + transaction
 * boundaries.
 *
 * **SRP (contract §6)**: owns ONE rule — "translate between rework [HistoryEntry] and the
 * [HistoryItemD] Room entity, then forward the call to [HistoryDao]". Query semantics
 * (`@Query("SELECT * FROM history_items ORDER BY lastReadDate DESC")`), insert conflict policy
 * (`OnConflictStrategy.REPLACE`), and the type converter for
 * `kotlinx.datetime.LocalDateTime ↔ Long epoch-millis` all live in the DAO. Duplicating any of
 * those rules here would create a second source of truth and risk drift.
 *
 * **DIP (contract §6)**: depends on the [HistoryDao] Room interface (not a concrete impl). Koin
 * binds the DAO via `MangaDatabase.historyDao()`. The [HistoryRepository] interface in `:domain`
 * is unaffected by the persistence vendor.
 *
 * **Flow mapping semantics** — `historyDao.getAllHistory()` is the upstream Room flow that emits a
 * `List<HistoryItemD>` on each `history_items` table write. `map { it.map { entity -> entity.
 * toDomain() } }` is a cold flow operator — each downstream subscriber gets its own per-element
 * mapping pass. The mapping is allocation-bounded (one [HistoryEntry] per entity) and CPU-trivial
 * (14-field copy with no parsing or validation), so the per-emission cost is dominated by the
 * Room query itself. No threading change vs the upstream.
 *
 * **Why `deleteEntry` round-trips through `toEntity()`**: `HistoryDao.deleteHistory(historyItemD:
 * HistoryItemD)` is entity-based (the Room `@Delete` annotation matches by primary key).
 * Reconstructing the entity from the [HistoryEntry] is field-copy-cheap (no DB lookup, no IO) —
 * see [HistoryEntry.toEntity] mapper.
 *
 * **Lifecycle**: `single` in Koin (per [HistoryRepository] KDoc). A `factory` would resubscribe
 * `getAllHistory()` on each resolution, which is wasteful for a read-mostly surface shared across
 * the app's lifetime.
 *
 * **Threading**: no explicit dispatcher pinning. The [HistoryDao] Room methods emit / suspend on
 * the IO context (per the KMP Room `flowOn(Dispatchers.IO)`-redundancy note); the rework's
 * `map`/`toDomain`/`toEntity` operators are pure transforms on whatever dispatcher the upstream
 * emits on.
 */
class HistoryRepositoryImpl(
    private val historyDao: HistoryDao,
) : HistoryRepository {

    override fun observeHistory(): Flow<List<HistoryEntry>> =
        // distinctUntilChanged (2026-07 audit): Room re-emits on any observed-table write; dedupe
        // structurally-equal snapshots before the per-row domain mapping (same family as
        // LibraryRepositoryImpl.observeLibrary).
        historyDao.getAllHistory().distinctUntilChanged().map { rows -> rows.map { it.toDomain() } }

    override suspend fun deleteEntry(entry: HistoryEntry) {
        historyDao.deleteHistory(entry.toEntity())
    }

    override suspend fun deleteAll() {
        historyDao.deleteAllHistory()
    }

    /**
     * Reader-convergence R3a. Maps the rework [Manga] + [Chapter] to the [HistoryItemD] Room entity
     * and forwards to `historyDao.insertOrUpdateHistory(...)`, an **upsert keyed by `mangaUrl`**
     * (verified at `HistoryDao.kt`: it fetches the existing row via
     * `getHistoryItemByMangaUrl(mangaUrl)`, then updates-or-inserts). Because the DAO is the cell
     * of truth, the rework History screen — which observes the same `history_items` table via
     * [observeHistory] — picks up the new/updated row automatically through Room invalidation.
     *
     * Field mapping (mirrors the legacy reader's `ChapterImagesScreenRoute.initHistoryItem`):
     *  - `mangaTitle` ← [Manga.title], `mangaUrl` ← [Manga.url], `mangaImageUrl` ← [Manga.coverUrl]
     *  - `api` ← [Manga.api], `language` ← [Manga.language]
     *  - `chapterUrl` ← [Chapter.url]; `chapterTitle` ← [Chapter.number] (the legacy reader stored
     *    the chapter *number* string in `chapterTitle`, not the chapter name — preserved for parity)
     *  - `isDownloaded` ← [Chapter.isDownloaded]
     *  - `mangaId` ← `0L`: the rework [Manga] has no surrogate Room id (it is identity-keyed by
     *    api+language+title). The legacy upsert keys on `mangaUrl`, NOT `mangaId`, so `0L` does not
     *    collide or mis-route — `insertOrUpdateHistory` finds the prior row by `mangaUrl` and
     *    preserves its real id on update; on first insert the `@PrimaryKey(autoGenerate = true)`
     *    column ignores the `0L` default and assigns the next id.
     *  - `lastReadDate` defaults to `Clock.System.now()` (the entity default), `lastReadPage` /
     *    `totalPages` default to `0` — same as the legacy reader's insert-on-open (page progress
     *    is persisted separately by the rework `ReadProgressRepository`).
     */
    override suspend fun record(manga: Manga, chapter: Chapter) {
        FlowLog.log("History", "record", "manga=${manga.title} chapter=${chapter.url}")
        historyDao.insertOrUpdateHistory(
            HistoryItemD(
                api = manga.api,
                language = manga.language,
                mangaId = 0L,
                mangaUrl = manga.url,
                mangaTitle = manga.title,
                mangaImageUrl = manga.coverUrl,
                chapterUrl = chapter.url,
                chapterTitle = chapter.number,
                isDownloaded = chapter.isDownloaded,
            ),
        )
    }
}
