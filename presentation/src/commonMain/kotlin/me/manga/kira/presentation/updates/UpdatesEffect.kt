package me.manga.kira.presentation.updates

import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [UpdatesViewModel] for the view to perform once and forget.
 *
 * Phase 7.x.updates rework. Strict MVI: effects carry only the trigger payload (target
 * identity, full entry for the reader's nav arg shape) — never rendering data. Recurrent UI
 * elements (loaders, empty-state messages) live in [UpdatesState].
 *
 * Same shape posture as [me.manga.kira.presentation.history.HistoryEffect]: navigation
 * effects are surfaced to the route adapter, not to the screen composable directly. The route
 * adapter collects the [me.manga.kira.presentation.mvi.MviViewModel.effects] flow via a
 * `LaunchedEffect` and translates each effect into a `navController.navigate(...)` call.
 *
 * **Phase 7.x.updates.undosnackbar (added)**: [ShowUndoSnackbar] now lifts the legacy's
 * delete-with-undo UX onto the rework. The screen-side reception of this effect surfaces a
 * Material 3 `SnackbarHost` message; on Undo-action-performed the screen submits
 * [me.manga.kira.presentation.updates.UpdatesIntent.OnUndoDelete], on dismissal it submits
 * [me.manga.kira.presentation.updates.UpdatesIntent.OnConfirmDelete].
 *
 * **Phase 7.x.updates.downloadbutton.wire (added)**: [ShowError] surfaces enqueue failures
 * from [me.manga.kira.domain.usecase.downloads.EnqueueDownloadUseCase] (missing
 * `saved_chapters` row, WorkManager rejection on Android, etc.). The Updates screen's
 * existing `SnackbarHostState` (provisioned for [ShowUndoSnackbar]) handles this effect by
 * calling `showSnackbar(message)` with the supplied error text. The original "no `ShowError`
 * effect today" KDoc rationale (`UPDATE` / `DELETE` SQL is structurally infallible) is now
 * superseded — enqueue is the first fallible mutation on this surface.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster108.staleKdocSweep.cascade,
 * Task #564, 2026-05-28): the file-scope effect-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-eighth sibling of the cluster57-107 sweep — opens
 * the wave-9 `:presentation/updates/` batch alongside UpdatesState.kt
 * plus UpdatesViewModel.kt):
 *  (a) "Foundation slice landed NavigateToDetails plus NavigateToReader;
 *  Phase 7.x.updates.undosnackbar adds ShowUndoSnackbar; Phase 7.x.
 *  updates.downloadbutton.wire adds ShowError" — LIVE-NOT-STALE. L32-85
 *  sealed interface declares exactly four variants (NavigateToDetails
 *  plus NavigateToReader plus ShowUndoSnackbar plus ShowError); the
 *  three-phase append posture preserved verbatim — no rewrites to the
 *  foundation declarations when the undosnackbar plus downloadbutton.
 *  wire slices landed.
 *  (b) "Strict MVI — effects carry only trigger payload (target
 *  identity, full entry for the reader's nav arg shape) — never
 *  rendering data" — LIVE-NOT-STALE. L38 NavigateToDetails(api,
 *  mangaUrl) — minimum identity tuple; L48 NavigateToReader(entry:
 *  UpdateEntry) — full entry payload for the reader's wide nav arg
 *  shape; L65 ShowUndoSnackbar(entry: UpdateEntry) — entry payload so
 *  the screen can dispatch the follow-up intents; L84 ShowError(message:
 *  String) — single-string payload, no rendering data.
 *  (c) "Same shape posture as HistoryEffect — nav effects surfaced to
 *  the route adapter not the screen composable directly" — LIVE-NOT-
 *  STALE. HistoryEffect navigation posture verified at cluster104 sweep
 *  (Task #560); peer cross-ref preserved verbatim.
 *  (d) "ShowUndoSnackbar carries full UpdateEntry so the screen has
 *  identity for the follow-up intent dispatch; entry still present in
 *  upstream observeUpdates() flow at the moment this effect fires" —
 *  LIVE-NOT-STALE. UpdatesViewModel.kt L113-116 OnRequestDelete handler
 *  realizes the order: updateState pendingDeleteIds first, then emit(
 *  ShowUndoSnackbar(intent.entry)); the deleteUpdateEntry use case is
 *  NOT called here — only OnConfirmDelete (L120-123) makes that call.
 *  (e) "ShowError supersedes the original `no ShowError today` rationale
 *  — enqueue is the first fallible mutation on this surface" — LIVE-
 *  NOT-STALE. UpdatesViewModel.kt L102-112 OnDownloadClick handler
 *  realizes `enqueueDownload(...).onFailure { emit(UpdatesEffect.
 *  ShowError(...)) }` — the only onFailure-emit-ShowError site on this
 *  VM. Original rationale that UPDATE/DELETE SQL is structurally
 *  infallible still applies to the four pre-existing mutating intents
 *  (OnMarkAsRead, OnMarkAllAsRead, OnDeleteEntry, OnDeleteAll) — none
 *  of those handlers attach `.onFailure`.
 *  Five classifications STAND on their own merits as a faithful
 *  UpdatesEffect surface manifest. Original Phase 7.x.updates-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
sealed interface UpdatesEffect : MviEffect {

    /**
     * View should navigate to the manga details screen. Carries the full identity the Updates row
     * already holds (title/cover/language) so the route can open the full-tuple rework details route
     * (`Screen.MangaDetailsRework`) — binding library membership / title / cover up-front from Room
     * instead of the URL-only route, which flashed an empty placeholder + forced a network fetch for
     * saved manga. Rating/genres aren't part of Updates data, so the route passes them as
     * `null`/empty (Details re-fetches them).
     */
    data class NavigateToDetails(
        val api: String,
        val language: String,
        val title: String,
        val mangaUrl: String,
        val coverUrl: String,
    ) : UpdatesEffect

    /**
     * View should navigate to the chapter reader. Carries the full [UpdateEntry] because the
     * legacy `Screen.ChapterImagesFragment` route takes the full identity tuple (api,
     * language, mangaId, chapterId, mangaTitle, mangaUrl, mangaImageUrl, chapterUrl,
     * chapterNumber, localImagePaths, isDownloaded). The entry's `chapterId` field IS the
     * chapterId nav arg directly (the legacy `ChapterNotification` stores it as `chapterId`,
     * no History-style "id doubles as chapterId" quirk).
     */
    data class NavigateToReader(val entry: UpdateEntry) : UpdatesEffect

    /**
     * View should surface a Material 3 `SnackbarHost` message announcing the staged delete,
     * with an "Undo" action and `withDismissAction = true`. On action-performed the screen
     * submits [me.manga.kira.presentation.updates.UpdatesIntent.OnUndoDelete] carrying
     * [entry]; on dismissal it submits
     * [me.manga.kira.presentation.updates.UpdatesIntent.OnConfirmDelete] carrying the
     * same entry. Phase 7.x.updates.undosnackbar.
     *
     * Carries the full [UpdateEntry] so the screen has access to the entry's identity for
     * the follow-up intent dispatch — the VM doesn't need to look it up again. Since the
     * immediate-delete parity change, the delete is applied right away in the same
     * `OnRequestDelete` handler that emits this effect (`DeleteUpdateEntryUseCase`), so the row
     * is gone from the upstream `observeUpdates()` flow; the carried [UpdateEntry] is the payload
     * `RestoreUpdateEntryUseCase` re-inserts on Undo. [UpdatesState.pendingDeleteIds] optimistic-
     * hides the row so [UpdatesState.visibleItems] doesn't flash it before the flow re-emits.
     */
    data class ShowUndoSnackbar(val entry: UpdateEntry) : UpdatesEffect

    /**
     * View should surface a Material 3 `SnackbarHost` message announcing that a chapter download
     * could not be enqueued. Phase 7.x.updates.downloadbutton.wire.
     *
     * Emitted by [UpdatesViewModel] when
     * [me.manga.kira.domain.usecase.downloads.EnqueueDownloadUseCase] returns
     * [Result.failure] — possible causes include the `saved_chapters` row being missing
     * (race vs. a concurrent delete), the legacy `enqueueChapterDownload` rejecting the
     * call (WorkManager queue full on Android, connectivity error on iOS/Desktop), or a
     * Room write failure. Per the MVI contract this effect carries no i18n text: the
     * underlying throwable is logged in the VM and `:ui` resolves a localized message.
     */
    data object ShowDownloadEnqueueFailed : UpdatesEffect

    /**
     * View should surface a Material 3 `SnackbarHost` message announcing that an Undo of a
     * just-deleted update could not be completed (the row stays deleted).
     *
     * Emitted by [UpdatesViewModel] when
     * [me.manga.kira.domain.usecase.updates.RestoreUpdateEntryUseCase] throws on the
     * `OnUndoDelete` path (Room re-insert failure, disk full). Without this the user who tapped
     * Undo would get no feedback while the entry is permanently lost — the delete-with-undo
     * flow's worst silent failure, since the action's whole purpose is recovering data the app
     * just destroyed. Per the MVI contract this effect carries no i18n text: `:ui` resolves a
     * localized message.
     */
    data object ShowUndoFailed : UpdatesEffect
}
