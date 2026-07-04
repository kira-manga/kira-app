package me.manga.kira.presentation.history

import me.manga.kira.domain.model.history.HistoryEntry
import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [HistoryViewModel] for the view to perform once and forget.
 *
 * Phase 7.x.history rework. Strict MVI: effects carry only the trigger payload (target identity,
 * full entry for the reader's nav arg shape) — never rendering data. Recurrent UI elements
 * (loaders, empty-state messages) live in [HistoryState].
 *
 * Same shape posture as [me.manga.kira.presentation.library.LibraryEffect]: navigation
 * effects are surfaced to the route adapter, not to the screen composable directly. The route
 * adapter collects the [me.manga.kira.presentation.mvi.MviViewModel.effects] flow via a
 * `LaunchedEffect` and translates each effect into a `navController.navigate(...)` call.
 *
 * No `ShowError` effect today — a rare upstream/delete throw is handled non-visually instead: the
 * collector `.catch` (#17) just clears the spinner and the deletes run through `launchSafely` (#29)
 * which logs rather than surfacing an error. If a future composition wants to surface a failure
 * (e.g., a sync step), an `MviEffect.ShowError(error: AppError)` variant slots in here without
 * rewiring.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster102.staleKdocSweep.cascade,
 * Task #558, 2026-05-28): the file-scope effect-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-second sibling of the cluster57-101 sweep —
 * opens the wave-7 `:presentation/history/` batch):
 *  (a) "Strict MVI: effects carry only the trigger payload (target
 *  identity, full entry for the reader's nav arg shape) — never
 *  rendering data" — LIVE-NOT-STALE. L29 `NavigateToDetails` carries
 *  the `(api, mangaUrl)` identity pair; L38 `NavigateToReader`
 *  carries the full `HistoryEntry` domain object. Rendering data
 *  remains in HistoryState (items, isLoading, isEmpty convenience).
 *  (b) "Same shape posture as [LibraryEffect]: navigation effects are
 *  surfaced to the route adapter ... a `LaunchedEffect` and translates
 *  each effect into a `navController.navigate(...)` call" — LIVE-NOT-
 *  STALE. LibraryEffect cross-ref verified at presentation/library/
 *  LibraryEffect.kt; both surfaces use the route-adapter LaunchedEffect-
 *  collects-effects-flow pattern (the post-Phase 7.x.history.swap
 *  Task #288 HistoryScreenRoute.kt LIVE realization confirms this).
 *  (c) "No `ShowError` effect today ... If a future composition adds
 *  a fallible upstream ... an `MviEffect.ShowError(error: AppError)`
 *  variant slots in here without rewiring" — MIXED-with-partial-
 *  fulfillment. The sealed hierarchy at L23-39 enumerates exactly two
 *  variants today (LIVE-NOT-STALE). The ShowError extension is
 *  FORECAST-NOT-YET-FULFILLED — no fallible upstream composition has
 *  landed (HistoryViewModel.kt L65 has no `.catch {}`, L74-79 deletes
 *  have no `.onFailure`); the forecast preserves the OCP-compatible
 *  extension path.
 *  (d) NavigateToReader KDoc at L31-37 "matching the legacy
 *  `HistoryScreenRoute.kt:51` posture verbatim" — MIXED-with-STALE-
 *  SYMBOL-REFERENCE. The target nav route `Screen.ChapterImagesFragment`
 *  STAYS LIVE at Screen.kt:74 (the legacy reader nav has NOT been
 *  swapped — Screen.ChapterImagesRework exists alongside per cluster10
 *  audit trail). But the line-number cross-ref points to a pre-Phase
 *  7.x.history.swap (Task #288) file state; the swap rewrote
 *  HistoryScreenRoute.kt in place, so current L51 documents legacy-
 *  parity from the rework adapter's perspective rather than the
 *  original legacy adapter at that line. The behavioural-parity claim
 *  itself holds (field-for-field mapping matches the now-retired
 *  legacy posture); only the line-number reference is fragile.
 *  Two LIVE-NOT-STALE classifications plus one MIXED-with-partial-
 *  fulfillment classification plus one MIXED-with-STALE-SYMBOL-
 *  REFERENCE classification STAND on their own merits as a faithful
 *  History-effect-surface manifest. The NavigateToDetails KDoc at
 *  L25-29 STANDS as LIVE-NOT-STALE on its own merits (`Screen.Manga-
 *  Details` route preserved per Phase 9.x.mangadetails.swap ADR-8,
 *  Task #429). Original Phase 7.x.history-era prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
sealed interface HistoryEffect : MviEffect {

    /**
     * View should navigate to the manga details screen. Carries the full identity the History row
     * already holds (title/cover/language) so the route can open the full-tuple rework details route
     * (`Screen.MangaDetailsRework`) — binding library membership / title / cover up-front from Room
     * instead of the URL-only route, which flashed an empty placeholder + forced a network fetch for
     * saved manga. Rating/genres aren't part of History data, so the route passes them as
     * `null`/empty (Details re-fetches them).
     */
    data class NavigateToDetails(
        val api: String,
        val language: String,
        val title: String,
        val mangaUrl: String,
        val coverUrl: String,
    ) : HistoryEffect

    /**
     * View should navigate to the chapter reader. Carries the full [HistoryEntry] because the
     * legacy `Screen.ChapterImagesFragment` route takes the full identity tuple (api,
     * language, mangaId, chapterId, mangaTitle, mangaUrl, mangaImageUrl, chapterUrl,
     * chapterTitle, localImagePaths, isDownloaded). The entry's `id` doubles as the
     * `chapterId` arg (matching the legacy `HistoryScreenRoute.kt:51` posture verbatim).
     */
    data class NavigateToReader(val entry: HistoryEntry) : HistoryEffect
}
