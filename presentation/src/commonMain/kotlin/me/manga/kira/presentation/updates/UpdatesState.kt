package me.manga.kira.presentation.updates

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.todayIn
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.presentation.mvi.MviState

/**
 * Updates screen MVI state.
 *
 * Phase 7.x.updates rework. Holds the chapter-updates snapshot rendered by the screen plus an
 * [isLoading] flag covering the gap between subscription and the first
 * `List<UpdateEntry>` emission. No `error` field — the Updates upstream is Room's
 * `getGroupedNotifications()` flow which does not throw at the observe site (matches the
 * [me.manga.kira.presentation.history.HistoryState] no-`error` posture); mutations
 * (markAsRead, markAllAsRead, deleteEntry, deleteAll) are suspend functions invoked from
 * `viewModelScope.launch` whose runtime-failure modes are vanishingly small for `UPDATE`/
 * `DELETE` SQL.
 *
 * The state is **flow-driven**: the VM's `init {}` collector projects each upstream
 * `List<UpdateEntry>` snapshot into a fresh [items] list. Mutations propagate naturally — the
 * legacy `NotificationDao` re-emits the table on every write, so the screen is reactive without
 * needing an `OnRefresh` intent or imperative state mutation in the reducer.
 *
 * Contract §6 SRP: one rule — "what the Updates screen renders right now". No business logic,
 * no derivation that lives in the use case or repository.
 *
 * Contract §17: no `Any`, no `!!`. `items: List<UpdateEntry>` is read-only (the public interface
 * — the underlying list might be a `MutableList` but consumers can never call `add` etc.).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster108.staleKdocSweep.cascade,
 * Task #564, 2026-05-28): the file-scope state-shape manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-eighth sibling of the cluster57-107 sweep — opens
 * the wave-9 `:presentation/updates/` batch alongside UpdatesEffect.kt
 * plus UpdatesViewModel.kt):
 *  (a) "Foundation slice landed 2 fields (isLoading plus items); Phase
 *  7.x.updates.undosnackbar extension adds pendingDeleteIds" — LIVE-NOT-
 *  STALE. L29-44 data class shape verbatim: 3 val-only properties (is-
 *  Loading: Boolean = true plus items: List<UpdateEntry> = emptyList()
 *  plus pendingDeleteIds: Set<Long> = emptySet()); foundation-to-undo-
 *  snackbar append posture preserved verbatim — no rewrites to the
 *  foundation fields when the undosnackbar slice landed.
 *  (b) "Flow-driven projection — VM's init {} collector projects each
 *  upstream List<UpdateEntry> snapshot into a fresh items list" — LIVE-
 *  NOT-STALE. UpdatesViewModel.kt L73-79 init block hosts the single
 *  observeUpdates() collector with `updateState { it.copy(isLoading =
 *  false, items = snapshot) }` realization.
 *  (c) "No `error` field — Updates upstream is Room's getGroupedNotific-
 *  ations() flow which does not throw at the observe site (matches
 *  HistoryState no-error posture)" — LIVE-NOT-STALE. L29-44 declares
 *  zero error fields; HistoryState no-error posture verified at cluster-
 *  104 sibling sweep (Task #560). The Phase 7.x.updates.downloadbutton.
 *  wire extension surfaces enqueue failures as transient ShowError
 *  EFFECTS not persistent state — preserving the no-error-field posture.
 *  (d) "visibleItems derived getter filters items by pendingDeleteIds so
 *  soft-deleted entries are visually hidden during the undo-snackbar
 *  window; underlying items field stays as the upstream observeUpdates()
 *  projection (no destructive filter)" — LIVE-NOT-STALE. L53-55
 *  realization: `if (pendingDeleteIds.isEmpty()) items else items.
 *  filterNot { it.id in pendingDeleteIds }` — early-out empty-set fast
 *  path plus filterNot containment check.
 *  (e) "isEmpty plus hasUnreadItems convenience getters use visibleItems
 *  (not items) so the empty-state placeholder plus mark-all-read action
 *  disable correctly mid-undo-snackbar" — LIVE-NOT-STALE. L62 isEmpty
 *  realization: `!isLoading && visibleItems.isEmpty()`; L71 hasUnread-
 *  Items realization: `visibleItems.any { !it.isRead }` — both correctly
 *  delegate to the filtered projection. Computed-not-stored field
 *  posture preserved (no redundant copy in UpdatesViewModel.handle).
 *  Five classifications STAND on their own merits as a faithful
 *  UpdatesState manifest. Original Phase 7.x.updates-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
data class UpdatesState(
    val isLoading: Boolean = true,
    val items: List<UpdateEntry> = emptyList(),
    /**
     * IDs of entries optimistically hidden after an immediate delete (Phase 7.x.updates.undosnackbar).
     * The delete lands in Room as soon as the user taps "Delete", so the upstream
     * `observeUpdates()` flow stops emitting these entries in [items] once it re-emits — this set
     * only covers the request→Room-re-emit window, hiding the row instantly so there's no flash.
     * An id is added on
     * [me.manga.kira.presentation.updates.UpdatesIntent.OnRequestDelete] and removed when either
     * [me.manga.kira.presentation.updates.UpdatesIntent.OnUndoDelete] (which re-inserts the row
     * via [me.manga.kira.domain.usecase.updates.RestoreUpdateEntryUseCase]) or
     * [me.manga.kira.presentation.updates.UpdatesIntent.OnConfirmDelete] arrives. The
     * screen renders [visibleItems] (which filters this set out) so the row "disappears"
     * during the snackbar-visible window.
     *
     * Defaults to the empty set — most renders pass this field unchanged.
     */
    val pendingDeleteIds: Set<Long> = emptySet(),
    /**
     * Live snapshot of the chapter-download queue + history, projected from
     * [me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase] (GAP-UPD-03).
     *
     * Native parity: the legacy Updates row derived its per-row trailing affordance from the
     * download manager's `queuedChapterIds` + `runningChapter`, showing a spinner while a row's
     * chapter was queued/running (primary-tinted when running, onPrimary when merely queued)
     * and a download/download-done icon otherwise (old `UpdateItem.kt:113-137`). The rework
     * surfaces the same signal by joining this list against each row's `chapterId` via
     * [downloadStatusFor].
     *
     * Defaults to empty — most renders pass this field unchanged. The VM's `init {}` collector
     * keeps it in sync with the upstream Room downloads flow.
     */
    val downloads: List<DownloadedChapter> = emptyList(),
    /**
     * Inline error surfaced when the upstream `observeUpdates()` flow throws (parity fix —
     * updates-refresh "error message rendering"). Native's `NotificationsViewModel`
     * (NotificationsViewModel.kt:40-49) wraps the grouped-notifications flow in `.catch {}` and
     * projects the failure into an inline `colorScheme.error` Text (UpdatesScreen.kt:98-106).
     *
     * Typed [AppError] (not a raw `String`) per the MVI contract — `:ui` translates it to a
     * localized message at the call site, so the inline error renders correctly on all 10 locales
     * instead of leaking the untranslated exception text the previous `String?` field carried.
     * `null` in the happy path. Room's observe site rarely throws, but a mapping failure in
     * `toDomain` or any future fallible upstream would otherwise crash the collector — this field
     * lets the VM's `.catch {}` surface the failure instead of propagating uncaught.
     */
    val loadError: AppError? = null,
) : MviState {

    /**
     * Lazily-built index of [downloads] keyed by `chapterId` so [downloadStatusFor] is O(1) per
     * row instead of scanning the (ever-growing) queue+history projection on every visible row of
     * every recomposition. `data class` `copy()` makes a fresh instance, so this re-computes only
     * when the state actually changes.
     */
    private val downloadsByChapterId: Map<Long, DownloadedChapter> by lazy {
        // Keep the first occurrence per chapterId to match the previous firstOrNull semantics.
        downloads.asReversed().associateBy { it.chapterId }
    }

    /**
     * Resolves the per-row download affordance state for [chapterId] by joining [downloads]
     * (the live download queue/history) against the row's chapter id. Mirrors the native
     * `UpdateItem` derivation:
     *  - a [DownloadState.RUNNING] / [DownloadState.COMPRESSING] download → [RowDownloadStatus.RUNNING]
     *    (the row whose chapter is actively downloading — native tinted its spinner `primary`);
     *  - a [DownloadState.QUEUED] download → [RowDownloadStatus.QUEUED] (native tinted `onPrimary`);
     *  - a [DownloadState.SUCCESS] download OR the entry's own `isDownloaded` flag →
     *    [RowDownloadStatus.DONE];
     *  - otherwise [RowDownloadStatus.IDLE] (show the download affordance).
     *
     * [entryIsDownloaded] is the row's persisted `isDownloaded` flag (the downloads table may
     * have no row once the CBZ is archived + the queue entry cleared, so the persisted flag is
     * the authoritative "done" signal in that case).
     */
    fun downloadStatusFor(chapterId: Long, entryIsDownloaded: Boolean): RowDownloadStatus {
        val download = downloadsByChapterId[chapterId]
        return when (download?.state) {
            // DOWNLOADED (iOS background: pages on disk, finalization pending) is still "finishing" —
            // show the same in-progress spinner as RUNNING/COMPRESSING.
            DownloadState.RUNNING, DownloadState.COMPRESSING, DownloadState.DOWNLOADED -> RowDownloadStatus.RUNNING
            DownloadState.QUEUED -> RowDownloadStatus.QUEUED
            DownloadState.SUCCESS -> RowDownloadStatus.DONE
            DownloadState.FAILED -> if (entryIsDownloaded) RowDownloadStatus.DONE else RowDownloadStatus.IDLE
            null -> if (entryIsDownloaded) RowDownloadStatus.DONE else RowDownloadStatus.IDLE
        }
    }

    /**
     * Items the screen should render. Filters [items] by [pendingDeleteIds] so that an
     * already-deleted entry is optimistically hidden during the request→Room-re-emit window
     * (and the rest of the undo-snackbar window). Once `observeUpdates()` re-emits without the
     * deleted row, [items] no longer contains it; restoring the entry re-inserts the Room row
     * (see [pendingDeleteIds]), it is not recovered by merely dropping its id from the set.
     */
    val visibleItems: List<UpdateEntry> get() =
        if (pendingDeleteIds.isEmpty()) items
        else items.filterNot { it.id in pendingDeleteIds }

    /**
     * Convenience: true when the snapshot is empty and we're not still loading. Uses
     * [visibleItems] (not [items]) so the empty-state placeholder shows correctly when the
     * last visible entry is staged for delete.
     */
    val isEmpty: Boolean get() = !isLoading && visibleItems.isEmpty()

    /**
     * Convenience: whether the "Mark all read" top-bar action should be enabled. Off when no
     * items are present or when every item is already read. Uses [visibleItems] so the
     * action correctly disables when the last unread row is mid-undo-snackbar. Computed
     * (no field) so the value stays in sync with [items] + [pendingDeleteIds] without a
     * redundant copy in [UpdatesViewModel.handle].
     */
    val hasUnreadItems: Boolean get() = visibleItems.any { !it.isRead }

    /**
     * The single authoritative recency-bucketing of [visibleItems] (consolidates xcut-dup-11): the
     * `:ui` layer renders these display-ready groups directly instead of re-deriving the buckets in
     * the composable. Each [UpdateEntry] is assigned to a [RecencyBucket] by its
     * [UpdateEntry.notificationDate] relative to the current system date, then sorted WITHIN each
     * bucket descending by chapter number (`chapterNumber.toDoubleOrNull() ?: 0.0`) — the exact rule
     * the legacy `NotificationRepository.groupByDate` applied. Empty buckets are dropped; iteration
     * order is the fixed TODAY → YESTERDAY → LAST_WEEK → OLDER [RecencyBucket.entries] order.
     *
     * Boundary semantics follow the "nothing silently vanishes" rule: future-dated rows clamp into
     * TODAY and OLDER is the complement (`days >= 7`), so every entry lands in exactly one bucket
     * (the prior `:ui` regroup dropped the exactly-7-days-ago row; this keeps it under OLDER).
     */
    @OptIn(ExperimentalTime::class)
    val groupedVisibleItems: List<Pair<RecencyBucket, List<UpdateEntry>>>
        get() {
            val today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
            val byBucket = visibleItems.groupBy { RecencyBucket.of(it.notificationDate, today) }
            return RecencyBucket.entries.mapNotNull { bucket ->
                val entries = byBucket[bucket]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                bucket to entries.sortedByDescending { it.chapterNumber.toDoubleOrNull() ?: 0.0 }
            }
        }
}

/**
 * The per-row download affordance state on the Updates screen (GAP-UPD-03).
 *
 * Derived (never stored) by [UpdatesState.downloadStatusFor] from the live download queue. The
 * `:ui` layer branches on this to render the native-parity trailing affordance:
 *  - [IDLE] → a download icon button (enqueues on tap);
 *  - [QUEUED] → a spinner tinted `onPrimary` (queued but not yet the running chapter);
 *  - [RUNNING] → a spinner tinted `primary` (this is the actively-downloading chapter);
 *  - [DONE] → a disabled download-done icon.
 *
 * Pure presentation ADT — keeps the download-state derivation in MVI (state owns logic) so the
 * `:ui` row stays callback-only.
 */
enum class RowDownloadStatus {
    IDLE,
    QUEUED,
    RUNNING,
    DONE,
}

/**
 * Native-parity recency tiers for the Updates list group headers (GAP-UPD-07), owned by the
 * presentation layer so the bucketing lives in exactly one place ([UpdatesState.groupedVisibleItems])
 * — the `:ui` composable maps each bucket to its localized header string and renders, never
 * re-deriving the buckets itself.
 */
enum class RecencyBucket {
    TODAY,
    YESTERDAY,
    LAST_WEEK,
    OLDER,
    ;

    companion object {
        /**
         * Assigns [date] to a tier by its distance (in days) to [today]: today-or-future → [TODAY],
         * one day ago → [YESTERDAY], two-to-six days ago → [LAST_WEEK], seven-or-more → [OLDER].
         * Every date maps to exactly one bucket (no row is silently dropped).
         */
        fun of(date: LocalDate, today: LocalDate): RecencyBucket {
            val days = date.daysUntil(today)
            return when {
                days <= 0 -> TODAY
                days == 1 -> YESTERDAY
                days in 2..6 -> LAST_WEEK
                else -> OLDER
            }
        }
    }
}
