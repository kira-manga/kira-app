package me.manga.kira.presentation.history

import me.manga.kira.domain.model.history.HistoryEntry
import me.manga.kira.presentation.mvi.MviIntent

/**
 * User actions submitted from the History screen.
 *
 * Phase 7.x.history rework. Sealed so the [HistoryViewModel.handle] `when` is exhaustive; adding
 * a new action requires adding a new subclass (OCP — compile-time enforcement that the reducer
 * handles every case).
 *
 * Same shape posture as [me.manga.kira.presentation.library.LibraryIntent]: two mutating
 * intents (delete entry, delete all) + two navigation intents (manga click, chapter click). The
 * navigation intents are handled by emitting one-shot [HistoryEffect]s — the screen's route
 * adapter forwards those to the legacy nav graph. Click-to-effect (rather than direct screen
 * callbacks) keeps `:ui` decoupled from "what the click does" (cf. LibraryIntent KDoc).
 *
 * Contract §6 OCP: adding e.g. an `OnEnterIncognitoFilter` variant is an append here; the VM's
 * exhaustive `when` flags the missing branch at compile time.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster102.staleKdocSweep.cascade,
 * Task #558, 2026-05-28): the file-scope intent-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-second sibling of the cluster57-101 sweep —
 * sibling of cluster102 HistoryEffect.kt):
 *  (a) "Phase 7.x.history rework. Sealed so the [HistoryViewModel.
 *  handle] `when` is exhaustive" — LIVE-NOT-STALE. HistoryViewModel.kt
 *  L72-87 reducer realization: exhaustive `when (intent)` over four
 *  branches matching the four declared intents at L29 / L36 / L42 /
 *  L49.
 *  (b) "Same shape posture as [LibraryIntent]: two mutating intents
 *  (delete entry, delete all) plus two navigation intents (manga
 *  click, chapter click)" — LIVE-NOT-STALE. L29 `OnDeleteEntry`, L36
 *  `OnDeleteAll` (mutating); L42 `OnMangaClick`, L49 `OnChapterClick`
 *  (navigation). Exactly the forecasted 2-plus-2 split. LibraryIntent
 *  parity citation verified.
 *  (c) "Click-to-effect (rather than direct screen callbacks) keeps
 *  `:ui` decoupled from 'what the click does' (cf. LibraryIntent
 *  KDoc)" — LIVE-NOT-STALE. HistoryViewModel.kt L80-86 OnMangaClick
 *  plus OnChapterClick branches emit HistoryEffect.NavigateToDetails
 *  plus HistoryEffect.NavigateToReader respectively; no
 *  navController-typed parameter crosses the `:ui` boundary.
 *  (d) "Contract §6 OCP: adding e.g. an `OnEnterIncognitoFilter`
 *  variant is an append here; the VM's exhaustive `when` flags the
 *  missing branch at compile time" — REGISTERED-BUT-DORMANT.
 *  Recursive Grep for `OnEnterIncognitoFilter` matches ZERO live
 *  references; the example is an OCP illustration, not a planned
 *  slice. The compile-time-exhaustiveness guarantee holds in
 *  HistoryViewModel reducer (L72-87 lacks a fallthrough branch).
 *  The four per-intent KDocs at L24-49 (OnDeleteEntry, OnDeleteAll,
 *  OnMangaClick, OnChapterClick) STAND as LIVE-NOT-STALE on their own
 *  merits per recursive symbol verification against HistoryViewModel.
 *  kt reducer branches and HistoryEffect.kt navigation targets.
 *  Three LIVE-NOT-STALE classifications plus one REGISTERED-BUT-
 *  DORMANT classification STAND on their own merits as a faithful
 *  History-intent-surface manifest. Original Phase 7.x.history-era
 *  prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
sealed interface HistoryIntent : MviIntent {

    /**
     * User tapped the per-row delete button. The VM invokes
     * [me.manga.kira.domain.usecase.history.DeleteHistoryEntryUseCase] in a coroutine; the
     * upstream `observeHistory()` flow re-emits with the entry removed.
     */
    data class OnDeleteEntry(val entry: HistoryEntry) : HistoryIntent

    /**
     * User tapped the "Clear all" top-bar action. The VM invokes
     * [me.manga.kira.domain.usecase.history.DeleteAllHistoryUseCase]; the upstream flow
     * re-emits an empty list.
     */
    data object OnDeleteAll : HistoryIntent

    /**
     * User tapped the cover thumbnail. The VM emits
     * [HistoryEffect.NavigateToDetails] with the entry's `(api, mangaUrl)` identity pair.
     */
    data class OnMangaClick(val entry: HistoryEntry) : HistoryIntent

    /**
     * User tapped the row body. The VM emits
     * [HistoryEffect.NavigateToReader] carrying the entry; the route adapter constructs the
     * full legacy `Screen.ChapterImagesFragment` argument shape from the entry's fields.
     */
    data class OnChapterClick(val entry: HistoryEntry) : HistoryIntent
}
