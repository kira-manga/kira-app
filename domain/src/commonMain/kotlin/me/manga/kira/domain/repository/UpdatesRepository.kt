package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.updates.UpdateEntry

/**
 * Reactive chapter-updates access — observe the list, toggle read-state, delete entries.
 *
 * Phase 7.x.updates rework. The `:data` impl strangler-fig delegates to the legacy `:shared`
 * `NotificationRepository` (which wraps `NotificationDao` + the `notifications` Room table). The
 * legacy facade remains the cell of truth for INSERTs — those are owned by the Android-only
 * `LibraryRefreshWorker` + `ChapterNotificationHelper` which write new rows when the worker
 * detects fresh chapters per source. Those write paths are outside the rework's read+mutate
 * boundary; Phase 9.x route-swap retires the legacy `Screen.Updates` binding but the underlying
 * legacy repository stays until the worker itself gets rewritten.
 *
 * Contract §6 SRP: owns ONE rule — "expose chapter-updates as a read + mutate surface for the
 * Updates screen". Inserts (`ChapterNotificationHelper.queueNotification`) are intentionally NOT
 * on this interface (ISP) — that surface belongs to the worker flow, not the screen.
 *
 * Contract §6 ISP: six methods covering the exact action set the Updates screen surfaces: one
 * read flow, two read-state mutators (per-entry + bulk), two delete actions (per-entry + bulk),
 * and [restoreEntry] which backs the delete snackbar's "Undo". No `getById`, no `getByManga` —
 * the screen already holds the [UpdateEntry] at click time.
 *
 * Contract §6 DIP: consumers (the 5 use cases — `ObserveUpdatesUseCase`,
 * `MarkUpdateAsReadUseCase`, `MarkAllUpdatesAsReadUseCase`, `DeleteUpdateEntryUseCase`,
 * `DeleteAllUpdatesUseCase`, and through them the rework `UpdatesViewModel`) depend on this
 * interface, never on the legacy `NotificationRepository` or the underlying DAO. Koin binds the
 * impl at the composition root in `updatesReworkModule`.
 *
 * Lifecycle expectation: the impl is bound as a `single` (matching the upstream legacy
 * `NotificationRepository`'s `single` lifecycle from `SharedModule`). A `factory` would
 * resubscribe the upstream `getAllNotifications()` flow on each resolution — wasteful for a
 * read-mostly surface shared across the app's lifetime.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster26.staleKdocSweep.cascade,
 * Task #482, 2026-05-28): one fulfilled-forecast citation appears
 * above:
 *  - Lines 14-15 ("Phase 9.x route-swap retires the legacy
 *    `Screen.Updates` binding but the underlying legacy repository
 *    stays until the worker itself gets rewritten").
 *    PARTIALLY-FULFILLED-INVERSION — Phase 7.x.updates.swap (§289)
 *    re-pointed `Screen.Updates`'s rendering adapter to the rework
 *    `UpdatesScreen` (7.x-prefixed, earlier than the §253-era forecast
 *    predicted); Phase 9.aa.updates.legacy_retire (§310, commit
 *    `8e99e4b`) deleted the orphan legacy `:shared` `UpdatesScreen.kt`
 *    UI + `UpdateItem` + `NotificationsUiState`. HOWEVER — the legacy
 *    `:shared` `NotificationRepository` facade + `NotificationDao` +
 *    `ChapterNotification` Room entity + the Android-only
 *    `LibraryRefreshWorker` + `ChapterNotificationHelper` INSERT path
 *    STILL EXIST as the cell of truth that the rework `:data` impl
 *    delegates to via constructor injection (verified at
 *    `UpdatesRepositoryImpl`'s `private val legacy:
 *    LegacyNotificationRepository`). The forecast resolved exactly as
 *    predicted — the route binding was retired but "the underlying
 *    legacy repository stays until the worker itself gets rewritten"
 *    matches the present state. The `LibraryRefreshWorker` rewrite
 *    has not occurred (Android-only `WorkManager` integration outside
 *    the rework's read+mutate boundary). Mirror of `UpdatesRepository
 *    Impl`'s §456 + §480 follow-on postscripts (which cover the same
 *    §§289 + 310 fulfillment from the `:data` impl angle). The SRP /
 *    ISP / DIP / lifecycle sub-sections all stand on their own merits
 *    past the §§289 + 310 fulfilled landings. The UpdatesRepository
 *    interface remains LIVE as the canonical rework read+mutate
 *    surface. Original §253-era prose preserved verbatim per the
 *    audit-trail-preservation convention — the citation is historical
 *    record of the design lineage including the deferred-route-swap
 *    forecast that was subsequently fulfilled across §§289 + 310.
 */
interface UpdatesRepository {

    /**
     * Reactive list of all chapter-update entries, sorted by [UpdateEntry.notificationDate]
     * descending then by chapter number descending (matches the legacy
     * `NotificationRepository.getGroupedNotifications`'s internal sort within each date bucket).
     * Emits an updated list on every Room write to the `notifications` table.
     *
     * The flat-list shape (NOT the legacy's pre-grouped `List<Pair<String, List<Entry>>>`) lets
     * the `:ui` regroup using the same date-label formatter the History screen uses, so both
     * list screens converge on one idiom (§83.3 in [ARCHITECTURE.md]).
     */
    fun observeUpdates(): Flow<List<UpdateEntry>>

    /**
     * Mark a single update entry as read. Fire-and-forget — the upstream [observeUpdates] flow
     * re-emits once the Room transaction commits.
     *
     * **Not idempotent — this is a TOGGLE** (native parity): the underlying legacy
     * `LibraryDeo.markChapterAndNotificationRead` runs `UPDATE … SET isRead = NOT isRead` on both
     * the notification and its `saved_chapters` row, so calling this on an already-read entry flips
     * it back to unread. Callers must guard with `!entry.isRead` before invoking (the only caller,
     * `UpdatesViewModel`, does). The legacy `:shared` DAO is the cell of truth and stays a verbatim
     * toggle for parity; this contract does not promise a set-to-true semantic.
     *
     * Takes the full [UpdateEntry] (not just an id) for ISP symmetry with [deleteEntry] and to
     * future-proof against the legacy method's id-only signature (which forces the impl to
     * unwrap `entry.id`).
     */
    suspend fun markAsRead(entry: UpdateEntry)

    /**
     * Mark every update entry as read. Fire-and-forget — the upstream [observeUpdates] flow
     * re-emits with every entry's `isRead = true` once the Room bulk-update transaction commits.
     */
    suspend fun markAllAsRead()

    /**
     * Delete a single update entry. Fire-and-forget — the upstream [observeUpdates] flow
     * re-emits with the entry removed once the Room transaction commits.
     *
     * Takes the full [UpdateEntry] (not just an id) because the underlying legacy
     * `NotificationRepository.delete(notification: ChapterNotification)` is entity-based, and
     * the mapper round-trip ([UpdateEntry] → `ChapterNotification`) is cheap (no DB lookup,
     * just field copies). Same posture as [HistoryRepository.deleteEntry].
     */
    suspend fun deleteEntry(entry: UpdateEntry)

    /**
     * Re-insert a previously [deleted][deleteEntry] update entry (the Undo path). Round-trips the
     * full [UpdateEntry] back to its `ChapterNotification` row, preserving the primary key so the
     * restored row keeps its original id and list position. Enables the snackbar "Undo" to be
     * authoritative: the delete is applied immediately on request, and Undo restores it — no fixed
     * auto-finalize timer that could race an accessibility-extended snackbar.
     */
    suspend fun restoreEntry(entry: UpdateEntry)

    /**
     * Clear all update entries. Fire-and-forget — the upstream [observeUpdates] flow re-emits
     * with an empty list once the Room `DELETE FROM notifications` transaction commits.
     */
    suspend fun deleteAll()
}
