package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.data.mapper.toDomain
import me.manga.kira.data.mapper.toEntity
import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.domain.repository.UpdatesRepository

/**
 * [UpdatesRepository] cell-of-truth implementation over the legacy `:shared` Room DAOs
 * [NotificationDao] (the `notifications` table) and [LibraryDeo] (the cross-table
 * mark-as-read transaction).
 *
 * Phase 7.x.updates rework + RS-2 (Task #738). Translates between the rework `:domain` model
 * ([UpdateEntry]) and the legacy Room entity (`ChapterNotification`) via the mapper file
 * `UpdateMappers.kt`, then calls the Room DAO directly. RS-2 re-pointed this impl straight at the
 * two DAOs the retired legacy `:shared` `NotificationRepository` wrapper had forwarded to —
 * removing the intermediate wrapper while preserving behavior exactly. The Room DAOs remain the
 * cell of truth for the queries + transaction boundaries — same posture as
 * [HistoryRepositoryImpl] (Phase 7.x.history), [ReadingStatisticsRepositoryImpl] (Phase
 * 7.x.statistics) and [ReadingSessionRepositoryImpl] (Phase 6.4.x.statistics).
 *
 * **SRP (contract §6)**: owns ONE rule — "translate between rework [UpdateEntry] and the
 * `ChapterNotification` Room entity, then issue the matching Room DAO call". The query semantics
 * themselves (the DAO's `SELECT * FROM notifications ORDER BY notificationDate DESC` for
 * observeUpdates, `UPDATE notifications SET isRead = 1` for markAllAsRead, `DELETE FROM
 * notifications` for deleteAll, the cross-table `LibraryDeo.markChapterAndNotificationRead` for
 * markAsRead) all live in the Room DAOs. This impl owns no business logic directly — it maps the
 * raw `notificationDate DESC` DAO rows to [UpdateEntry] and issues the matching DAO call; the
 * recency-bucketing + within-bucket sort is owned by the presentation layer
 * (`UpdatesState.groupedVisibleItems`), the single authoritative grouping.
 *
 * **DIP (contract §6)**: depends on the [NotificationDao] / [LibraryDeo] interfaces from
 * `:shared` because they are the only vendor for the `notifications` table reads/writes today.
 * The dependency is structurally at the strangler boundary — the rework `:data` layer reaches
 * into the `:shared` Room DAOs for persistence that hasn't been ported to a rework DAO yet. The
 * [UpdatesRepository] interface in `:domain` is unaffected either way.
 *
 * **Why `observeUpdates` emits a flat list** — the rework's contract emits `List<UpdateEntry>` in
 * the raw `notificationDate DESC` DAO order. The recency-bucketing into Today / Yesterday / Last
 * Week / Older tiers (and the within-bucket chapter-number-DESC sort) is derived once,
 * authoritatively, by the presentation layer (`UpdatesState.groupedVisibleItems`), and the `:ui`
 * layer maps each bucket to its localized header. This impl no longer pre-buckets the flat list —
 * the grouping now lives in exactly one place rather than being duplicated here and re-derived in
 * the composable (xcut-dup-11).
 *
 * **Why `markAsRead` forwards `entry.chapterId` (not `entry.id`)** — the mark-read path calls
 * `LibraryDeo.markChapterAndNotificationRead(chapterId)`, a `@Transaction` that runs
 * `UPDATE notifications SET isRead = NOT isRead WHERE chapterId = :chapterId` + the
 * corresponding `saved_chapters` flip (cross-table consistency between the notification's
 * read-flag and the underlying chapter's read-flag). The key is the chapter id, NOT the
 * notification's primary key — exactly what the retired wrapper's `markAsRead(id)` forwarded.
 * Forwarding `entry.id` here would target the wrong `chapterId` column and silently no-op (or
 * worse, flip the wrong row if chapterId/id happen to collide). This cross-table invariant is
 * the single behavior RS-2 had to preserve EXACTLY.
 *
 * **Why `deleteEntry` round-trips through `toEntity()`**: `NotificationDao.deleteNotification`
 * is entity-based (the Room `@Delete` annotation matches by primary key). Reconstructing the
 * entity from the [UpdateEntry] is field-copy-cheap (no DB lookup, no IO) — see
 * [UpdateEntry.toEntity] mapper. Same posture as [HistoryRepositoryImpl.deleteEntry].
 *
 * **Lifecycle**: `single` in Koin (per [UpdatesRepository] KDoc). The upstream [NotificationDao]
 * / [LibraryDeo] are themselves `single` (declared per-platform by `PlatformModule`); a
 * `factory` here would resubscribe `getAllNotifications()` on each resolution, which is wasteful
 * for a read-mostly surface shared across the app's lifetime.
 *
 * **Threading**: no explicit dispatcher pinning. The `NotificationDao` Room methods
 * emit / suspend on the IO context; the rework's `map`/`toDomain`/`toEntity` operators are pure
 * transforms on whatever dispatcher the upstream emits on.
 *
 * **Audit-trail postscript** (Phase 9.x.updates.staleKdocSweep.cascade,
 * Task #456, 2026-05-28): the "Why `markAsRead` forwards `entry.chapterId`
 * (not `entry.id`)" paragraph above references "The legacy [UpdatesScreen.kt]
 * verifies this at every call site" — the bracketed `[UpdatesScreen.kt]`
 * is a KDoc link reference to the §310-retired legacy
 * `composeApp/.../UpdatesScreen.kt`. The legacy screen was retired in
 * Phase 9.aa.updates.legacy_retire (§310 sweep, commit `8e99e4b`
 * "delete unreachable legacy UpdatesScreen + UpdateItem +
 * NotificationsUiState"); verified by a filesystem check returning zero
 * hits for that path. The chapterId-vs-id distinction rationale stands on
 * its own merits — the `LibraryDeo.markChapterAndNotificationRead` cross-
 * table UPDATE keys on chapterId, NOT on the notification's primary key,
 * and that legacy DAO invariant is independent of which legacy screen
 * originally consumed the facade. Original §253-era prose preserved
 * verbatim per the audit-trail-preservation convention — the citation is
 * historical record of the design lineage; the rework `markAsRead`
 * forwards `entry.chapterId` correctly through the legacy retire.
 *
 * **Audit-trail postscript follow-on** (Phase 9.x.cluster24.staleKdocSweep.cascade,
 * Task #480, 2026-05-28): the prior §456 postscript covered only the
 * L60 `[UpdatesScreen.kt]` KDoc-link reference. A separate stale
 * forecast at L28-29 ("Duplicating any of those rules here would
 * create a second source of truth and risk drift before the Phase 9.x
 * route-swap retires the legacy `Screen.Updates` binding") was not
 * addressed by §456. FACTUALLY INVERTED — Phase 7.x.updates.swap
 * (§289) re-pointed `Screen.Updates`'s rendering adapter to the rework
 * `UpdatesScreen` already (7.x-prefixed, earlier than the §253-era
 * forecast predicted); Phase 9.aa.updates.legacy_retire (§310, commit
 * `8e99e4b`) deleted the orphan legacy `:shared` `UpdatesScreen.kt` UI
 * + `UpdateItem` + `NotificationsUiState`. HOWEVER — the legacy
 * `:shared` [LegacyNotificationRepository] facade + `NotificationDao`
 * Room queries + `ChapterNotification` Room entity + the cross-table
 * `LibraryDeo.markChapterAndNotificationRead` invariant STILL EXIST as
 * the cell of truth that this impl delegates to via `legacy = get()`
 * (verified at the constructor signature below — `private val legacy:
 * LegacyNotificationRepository`). The "Phase 9.x route-swap retires
 * the legacy `Screen.Updates` binding" forecast happened as a §289
 * 7.x-prefixed swap (earlier than predicted) followed by §310 9.x
 * retire. The strangler-fig backbone holds; only the legacy
 * consumer-side surfaces were retired across §§289 + 310. Mirror of
 * §§475-479 cluster-tier partially-fulfilled-inversion precedent +
 * §456 same-file precedent. The SRP / DIP / import-alias /
 * observeUpdates-flatMap / markAsRead-chapterId / deleteEntry-round-trip /
 * lifecycle / threading sub-sections all stand on their own merits
 * past the §§289 + 310 fulfilled landings.
 */
class UpdatesRepositoryImpl(
    private val notificationDao: NotificationDao,
    private val libraryDeo: LibraryDeo,
) : UpdatesRepository {

    // Emits the raw `notificationDate DESC` DAO rows mapped to domain; the recency-bucketing +
    // within-bucket chapter-number sort is owned authoritatively by the presentation layer
    // (`UpdatesState.groupedVisibleItems`), so this no longer pre-buckets the flat list (de-dup of
    // the formerly hand-aligned twin grouping — xcut-dup-11).
    override fun observeUpdates(): Flow<List<UpdateEntry>> =
        // distinctUntilChanged (2026-07 audit): dedupe structurally-equal Room re-emissions before
        // the per-row domain mapping (same family as LibraryRepositoryImpl.observeLibrary).
        notificationDao.getAllNotifications().distinctUntilChanged().map { notifications ->
            notifications.map { it.toDomain() }
        }

    override suspend fun markAsRead(entry: UpdateEntry) {
        libraryDeo.markChapterAndNotificationRead(entry.chapterId)
    }

    override suspend fun markAllAsRead() {
        notificationDao.markAllAsRead()
    }

    override suspend fun deleteEntry(entry: UpdateEntry) {
        notificationDao.deleteNotification(entry.toEntity())
    }

    override suspend fun restoreEntry(entry: UpdateEntry) {
        // Re-insert the same row (REPLACE on conflict). toEntity() preserves the primary key (the
        // delete path above relies on it), so the restored row keeps its id, date, and position.
        notificationDao.insertNotificationsList(listOf(entry.toEntity()))
    }

    override suspend fun deleteAll() {
        notificationDao.deleteAllNotifications()
    }
}
