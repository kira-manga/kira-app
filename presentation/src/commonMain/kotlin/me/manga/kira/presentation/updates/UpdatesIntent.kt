package me.manga.kira.presentation.updates

import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.presentation.mvi.MviIntent

/**
 * User actions submitted from the Updates screen.
 *
 * Phase 7.x.updates rework. Sealed so the [UpdatesViewModel.handle] `when` is exhaustive;
 * adding a new action requires adding a new subclass (OCP — compile-time enforcement that the
 * reducer handles every case).
 *
 * Ten variants — more than History because the Updates screen exposes per-entry and bulk
 * read-state toggles in addition to delete actions and navigation clicks, three for the
 * immediate-delete + undo-snackbar pathway (Phase 7.x.updates.undosnackbar), and a per-row
 * download action (Phase 7.x.updates.downloadbutton.wire). The navigation intents are handled
 * by emitting one-shot [UpdatesEffect]s — the screen's route adapter forwards those to the
 * legacy nav graph. Click-to-effect (rather than direct screen callbacks) keeps `:ui` decoupled
 * from "what the click does" (cf. [HistoryIntent] KDoc).
 *
 * Contract §6 OCP: adding e.g. an `OnMarkAsUnread` variant or an `OnEnterUnreadFilter` is an
 * append here; the VM's exhaustive `when` flags the missing branch at compile time. The
 * undo-snackbar trio ([OnRequestDelete] / [OnUndoDelete] / [OnConfirmDelete]) was added as an
 * append in Phase 7.x.updates.undosnackbar — the original [OnDeleteEntry] stays in place as
 * an immediate-delete pathway for non-undo callers (e.g., a future "delete read items" bulk
 * action) so the slice was a strict OCP extension, not a modification.
 *
 * **Phase 7.x.updates.downloadbutton.wire (added)**: [OnDownloadClick] enqueues a fresh
 * download for the row's chapter through
 * [me.manga.kira.domain.usecase.downloads.EnqueueDownloadUseCase]. The legacy Updates
 * screen exposes the same per-row affordance (a downloads icon button that toggles between
 * "download" and "downloaded" states based on `entry.isDownloaded`). The intent is
 * dispatched only when the entry is NOT yet downloaded (UI rule — gated at the call site,
 * the use case itself does not validate).
 *
 * **Audit-trail postscript** (Phase 9.x.updates.staleKdocSweep.cascade,
 * Task #456, 2026-05-28): the [OnRequestDelete] KDoc below (lines 72-73)
 * cites the §310-retired legacy `composeApp/.../UpdatesScreen.kt` swipe-
 * to-dismiss handler, including the parenthetical "the legacy screen + VM
 * were retired in §144/§145; see audit log" — the §144/§145 phase-number
 * shorthand resolves to Phase 9.aa.updates.legacy_retire (§310 sweep,
 * commit `8e99e4b` "delete unreachable legacy UpdatesScreen + UpdateItem
 * + NotificationsUiState"); verified by a filesystem check returning zero
 * hits for that path. The staged-mutation rationale stands on its own
 * merits — the rework routes deletion through the MVI surface so all
 * mutations flow through the reducer, and this design choice is documented
 * inline above independent of which legacy file originally implemented the
 * parity precedent. Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citation is historical record
 * of the design lineage; the intent hierarchy continues to drive the VM's
 * exhaustive `when` correctly through the legacy retire.
 */
sealed interface UpdatesIntent : MviIntent {

    /**
     * User tapped the per-row "Mark read" button. The VM invokes
     * [me.manga.kira.domain.usecase.updates.MarkUpdateAsReadUseCase] in a coroutine; the
     * upstream `observeUpdates()` flow then re-emits the entry. The underlying DAO query is a
     * toggle (`UPDATE notifications SET isRead = NOT isRead`), which the `:ui` swipe path
     * deliberately exploits, so this flips the entry's read state rather than forcing `true`.
     */
    data class OnMarkAsRead(val entry: UpdateEntry) : UpdatesIntent

    /**
     * User tapped the "Mark all read" top-bar action. The VM invokes
     * [me.manga.kira.domain.usecase.updates.MarkAllUpdatesAsReadUseCase]; the upstream
     * flow re-emits with every entry's `isRead = true`.
     */
    data object OnMarkAllAsRead : UpdatesIntent

    /**
     * User tapped the per-row "Delete" button — immediate-delete pathway (no undo). The VM
     * invokes [me.manga.kira.domain.usecase.updates.DeleteUpdateEntryUseCase] in a
     * coroutine; the upstream `observeUpdates()` flow re-emits with the entry removed.
     *
     * **Kept for OCP parity** with the original Phase 7.x.updates surface. The user-facing
     * "Delete" button on the rework screen now dispatches [OnRequestDelete] instead (Phase
     * 7.x.updates.undosnackbar) so that the undo-snackbar pathway is the default. This
     * variant stays in the sealed hierarchy for a hypothetical future caller (e.g., a bulk
     * "delete all read" action that doesn't need per-entry undo) — removing it would be a
     * modification rather than an extension.
     */
    data class OnDeleteEntry(val entry: UpdateEntry) : UpdatesIntent

    /**
     * User tapped the per-row "Delete" button — immediate-delete + undo pathway (native
     * `deleteWithUndo` parity). The VM deletes the row right away via
     * [me.manga.kira.domain.usecase.updates.DeleteUpdateEntryUseCase], optimistically hides it
     * by staging the entry's id in [UpdatesState.pendingDeleteIds] (so the screen filters it from
     * [UpdatesState.visibleItems] before the upstream re-emits), and emits
     * [UpdatesEffect.ShowUndoSnackbar] carrying the entry. The screen shows a Material 3 Snackbar
     * with an "Undo" action; on action-performed the screen dispatches [OnUndoDelete] (which
     * re-inserts the row), on dismissal [OnConfirmDelete] (which just drops the optimistic-hide
     * id — the delete is already final).
     *
     * Same delete-then-undo pattern as the legacy `composeApp/.../UpdatesScreen.kt` swipe-
     * to-dismiss handler (the legacy screen + VM were retired in §144/§145; see audit log).
     * The legacy reached into `NotificationRepository.deleteNotificationWithUndo` directly via
     * a VM bridge; the rework routes the mutation through the MVI surface so all writes flow
     * through the [UpdatesViewModel.handle] reducer.
     */
    data class OnRequestDelete(val entry: UpdateEntry) : UpdatesIntent

    /**
     * User tapped the "Undo" action on the snackbar. The VM removes the entry's id from
     * [UpdatesState.pendingDeleteIds] so it reappears in [UpdatesState.visibleItems], and
     * re-inserts the row (the `OnRequestDelete` handler already deleted it) via
     * [me.manga.kira.domain.usecase.updates.RestoreUpdateEntryUseCase] — the carried [entry]
     * preserves the id/date/position so the restored row lands where it was.
     */
    data class OnUndoDelete(val entry: UpdateEntry) : UpdatesIntent

    /**
     * The snackbar timed out / was dismissed without "Undo". The delete was already applied in
     * the [OnRequestDelete] handler, so there is no DB write here: the VM only removes the entry's
     * id from [UpdatesState.pendingDeleteIds] so the optimistic-hide set doesn't linger (the
     * upstream `observeUpdates()` flow has already re-emitted without the row).
     */
    data class OnConfirmDelete(val entry: UpdateEntry) : UpdatesIntent

    /**
     * User tapped the "Clear all" top-bar action. The VM invokes
     * [me.manga.kira.domain.usecase.updates.DeleteAllUpdatesUseCase]; the upstream flow
     * re-emits an empty list.
     */
    data object OnDeleteAll : UpdatesIntent

    /**
     * User tapped "Retry" on the error surface. The upstream `observeUpdates()` flow is a cold
     * Room flow whose `.catch {}` TERMINATES collection on an upstream throw — so once
     * [UpdatesState.loadError] is set the collector is dead and no re-emit can clear it. This
     * intent re-subscribes: the VM cancels the (dead) observe job and relaunches a fresh
     * collector, resetting to the loading state first. Without it the feed stays dead until the
     * user leaves and re-enters the screen.
     */
    data object OnRetry : UpdatesIntent

    /**
     * User tapped the per-row "Download" button on an entry whose
     * [me.manga.kira.domain.model.updates.UpdateEntry.isDownloaded] is false.
     *
     * Phase 7.x.updates.downloadbutton.wire. The VM invokes
     * [me.manga.kira.domain.usecase.downloads.EnqueueDownloadUseCase] with the entry's
     * [me.manga.kira.domain.model.updates.UpdateEntry.chapterId],
     * [me.manga.kira.domain.model.updates.UpdateEntry.mangaTitle], and
     * [me.manga.kira.domain.model.updates.UpdateEntry.api]. On failure the VM emits
     * [UpdatesEffect.ShowError] carrying the throwable's message so the screen can surface
     * a Snackbar.
     *
     * **UI gating** — the rework `:ui` only renders the "Download" affordance for rows whose
     * `entry.isDownloaded == false`. Already-downloaded rows render a disabled "Downloaded"
     * label instead (parity with the legacy DownloadDone icon). The use case itself does NOT
     * validate the gating — the legacy `enqueueChapterDownload` happily re-enqueues a
     * chapter, but the UI rule keeps the affordance honest.
     */
    data class OnDownloadClick(val entry: UpdateEntry) : UpdatesIntent

    /**
     * User tapped the cover thumbnail. The VM emits
     * [UpdatesEffect.NavigateToDetails] carrying the entry's `api`, `language`, `title` (mangaTitle),
     * `mangaUrl`, and `coverUrl` (mangaImageUrl) fields.
     */
    data class OnMangaClick(val entry: UpdateEntry) : UpdatesIntent

    /**
     * User tapped the row body. The VM marks the chapter read (when the entry is not already
     * read) via [me.manga.kira.domain.usecase.updates.MarkUpdateAsReadUseCase], then emits
     * [UpdatesEffect.NavigateToReader] carrying the entry; the route adapter constructs the
     * full legacy `Screen.ChapterImagesFragment` argument shape from the entry's fields.
     *
     * Native parity (notifications `UpdatesScreen.kt:217-220`): the row-body tap wires
     * `onNotificationClick = { markAsRead(chapterId); navigate }` — opening a chapter from the
     * feed clears its unread state (dot disappears, opacity dims, the cross-table
     * `saved_chapters.isRead` flips) before navigating. The mark is gated on `!entry.isRead`
     * because the underlying DAO query is a toggle (`UPDATE notifications SET isRead = NOT
     * isRead`), so re-marking an already-read row would flip it back to unread.
     */
    data class OnChapterClick(val entry: UpdateEntry) : UpdatesIntent
}
