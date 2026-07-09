package me.manga.kira.presentation.details

import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.presentation.mvi.MviIntent

/**
 * User actions submitted from the Details screen.
 *
 * Sealed so the reducer's `when` is exhaustive (OCP — compile-time enforcement that new
 * actions can't sneak in without the ViewModel handling them).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster33.staleKdocSweep.cascade,
 * Task #489, 2026-05-28): two stale citations appear in this file's
 * member-list rationale below:
 *  - Lines 34-36 ([OnEnterByUrl] KDoc, "same observable behaviour as
 *    the legacy screen, which also rendered a placeholder until its
 *    own fetch resolved"). STALE-SYMBOL-REFERENCE — Phase 9.x.
 *    mangadetails.retire (§430, Slice 5 of the Phase 7.x.details.
 *    parity campaign — see Task #430) DELETED the legacy `:shared/
 *    .../features/details/ui/screens/MangaDetailsScreen.kt` along
 *    with its `DetailsContent.kt` + `HeaderSection.kt` + `ChapterItem.
 *    kt` sibling components + `MangaDerailsViewModel.kt` orphan-
 *    retire chain. A recursive search of the legacy details folder
 *    for a `MangaDetailsScreen.kt` with the cited
 *    placeholder-until-fetch-resolves behaviour returns NO MATCHES.
 *  - Lines 65-66 ([OnDownloadClick] KDoc, "the legacy 'bookmark
 *    first' coupling was a side effect of legacy hosting Downloads
 *    inside the Library tab, not an intentional product rule").
 *    STALE-SYMBOL-REFERENCE — Phase 9.x.library.retire (§347)
 *    DELETED the legacy `:shared/.../features/library/ui/screens/
 *    LibraryScreen.kt` along with its parallel debug route as a
 *    cascade-orphan retire chain. A recursive search of the legacy
 *    library folder for a `LibraryScreen.kt` with the cited
 *    Downloads-tab-hosting quirk returns NO MATCHES.
 *  Sibling cite at Lines 25-27 ([OnEnterByUrl] KDoc, "legacy
 *  `Screen.MangaDetails(mangaUrl, api)` shape ... the four legacy
 *  caller nav sites (Home, Library, History, Updates) stay
 *  untouched") is CLASSIFIED LIVE — the cite-target `Screen.
 *  MangaDetails` route key survives in `Screen.kt` post-§430 retire
 *  per ADR-8 (the route key is kept; the legacy adapter retires but
 *  the key continues serving the rework adapter to avoid rewriting
 *  the 4 caller nav sites). The "legacy" framing in the prose is
 *  the pre-rework naming convention but the route-key symbol is NOT
 *  retired. HOWEVER — the rework `:ui` `DetailsScreen` (different
 *  package: `me.manga.kira.ui.details.DetailsScreen`) is LIVE
 *  as the canonical Details surface backed by [DetailsState] +
 *  [DetailsViewModel] + this [DetailsIntent] sealed interface, and
 *  both architectural rationales — (a) the
 *  placeholder-until-fetch-resolves observable behaviour, and
 *  (b) the no-`isInLibrary`-gate-on-Downloads-button posture
 *  (per ADR-4) — STAND on their own merits past the §430 + §347
 *  fulfilled landings as LIVE rework realizations: the
 *  [OnEnterByUrl] reducer continues to render the URL host
 *  placeholder until the fetch lands; the [OnDownloadClick]
 *  IconButton continues to dispatch unconditionally with no
 *  `isInLibrary` gate. The [DetailsIntent] sealed interface remains
 *  LIVE as the canonical Details-screen intent ADT consumed by
 *  [DetailsViewModel] + the rework `:ui` `DetailsScreen`. Original
 *  §253-era prose preserved verbatim per the audit-trail-
 *  preservation convention — the citations are historical record
 *  of the design lineage including the placeholder-until-fetch and
 *  Downloads-tab-quirk rationales that were subsequently fulfilled
 *  (legacy details + library chains retired) across §430 + §347.
 */
sealed interface DetailsIntent : MviIntent {

    /**
     * Screen first becomes visible (or a configuration change re-attached the screen to a fresh
     * host). Carries the [Manga] identity that this screen renders. Idempotent on re-entry for
     * the same manga: subsequent emissions with the same identity are no-ops in the reducer so
     * a config change doesn't trigger a redundant network fetch.
     */
    data class OnEnter(val manga: Manga) : DetailsIntent

    /**
     * Screen first becomes visible from a *URL-only* navigation site — the legacy
     * `Screen.MangaDetails(mangaUrl, api)` shape, which carries only two of the seven identity
     * fields the rework `Manga` model holds. Used by the route-swap adapter that flips legacy
     * `Screen.MangaDetails` to the rework adapter post Slice 4 — the four legacy caller nav sites
     * (Home, Library, History, Updates) stay untouched.
     *
     * The reducer constructs a *tentative* [Manga] with the [api] + [mangaUrl] known fields and
     * sentinel placeholders for the rest (`language=""`, `title=""`, `coverUrl=""`, `rating=null`,
     * `genres=emptyList()`), kicks off the existing fetch path, and enriches `state.manga` from
     * the fetched [me.manga.kira.domain.model.MangaDetails] on success. The screen's top-bar
     * title falls back to the URL host (or the mangaUrl itself) until the fetch lands — same
     * observable behaviour as the legacy screen, which also rendered a placeholder until its own
     * fetch resolved.
     *
     * Re-entry guard is keyed on `(api, mangaUrl)` rather than `(api, language, title)` because
     * the language/title are not yet known at OnEnterByUrl-time. Two OnEnterByUrl emissions with
     * the same URL → no-op; an OnEnterByUrl followed by a fetch that enriches the state, then a
     * subsequent OnEnterByUrl with the same URL → still no-op (the enriched state's manga.url
     * matches).
     *
     * Phase 9.x.mangadetails.swap §253 / ADR-6 (Option B — VM-local args-shape resolver vs
     * rewriting all 4 caller nav sites).
     */
    data class OnEnterByUrl(val api: String, val mangaUrl: String) : DetailsIntent

    /** User tapped retry on the error state. Re-runs the fetch for the in-state manga. */
    data object OnRetry : DetailsIntent

    /** User tapped a chapter row. View navigates to the Reader via the emitted Effect. */
    data class OnChapterClick(val chapter: Chapter) : DetailsIntent

    /**
     * User tapped a chapter row's read/unread toggle (RemoveRedEye in native parity) — GAP-LIB-02.
     * Flips the chapter's `isRead` flag in the local library DB. No-op for chapters with no saved
     * row (not-in-library). The reactive saved-details flow re-emits the new read state so the row
     * re-renders dimmed/undimmed; no manual state mutation. Gated on `state.isInLibrary` in the VM.
     */
    data class OnToggleChapterRead(val chapter: Chapter) : DetailsIntent

    /**
     * User tapped a chapter row's download button — GAP-LIB-03. Enqueues that single chapter for
     * offline download. No-op for chapters with no saved row. Gated on `state.isInLibrary`.
     */
    data class OnDownloadChapter(val chapter: Chapter) : DetailsIntent

    /**
     * User tapped a chapter row's bookmark toggle (native `LibraryChapterItem` BookmarkBorder ↔
     * BookmarkRemove, wired to `toggleChapterBookmark(chapterId)`). Flips the chapter's
     * `isBookmarked` flag in the local library DB, keyed by `url`. No-op for a chapter with no saved
     * row (not-in-library). Gated on `state.isInLibrary` in the VM; the reactive saved-details flow
     * re-emits so the row re-renders.
     */
    data class OnToggleChapterBookmark(val chapter: Chapter) : DetailsIntent

    /**
     * User tapped a chapter row's cancel affordance while a download is active — GAP-LIB-03.
     * Cancels the queued/in-flight download for that chapter.
     */
    data class OnCancelChapterDownload(val chapter: Chapter) : DetailsIntent

    /**
     * User tapped a chapter row's trash button — "delete chapter from the database" (user-requested).
     * Deletes the chapter's `saved_chapters` record AND its download (on-disk files + chapter_downloads
     * row), so nothing is orphaned. Gated on `state.isInLibrary` (a non-library manga has no DB row);
     * the reactive saved-details flow re-emits without the chapter so it drops out of the list. For a
     * source-backed manga a later refresh may re-discover and re-add it.
     *
     * Additive KMP affordance beyond native (native deletes only downloads, via multi-select /
     * delete-all). INTENTIONALLY-DIFFERENT — do not "fix" toward native.
     */
    data class OnDeleteChapter(val chapter: Chapter) : DetailsIntent

    /**
     * User long-pressed a chapter row — GAP-LIB-10. Enters multi-select mode and toggles the
     * chapter's membership in the selection set. Subsequent taps in selection mode route through
     * [OnSelectionToggle].
     */
    data class OnChapterLongClick(val chapter: Chapter) : DetailsIntent

    /** Toggle a chapter's membership in the multi-select set (tap while in selection mode). */
    data class OnSelectionToggle(val chapter: Chapter) : DetailsIntent

    /** Exit multi-select mode and clear the selection (Cancel action / system back). */
    data object OnSelectionClear : DetailsIntent

    /** Multi-select "mark read" — marks every selected chapter read, then clears the selection. */
    data object OnMarkSelectedRead : DetailsIntent

    /** Multi-select "download" — enqueues every selected chapter, then clears the selection. */
    data object OnDownloadSelected : DetailsIntent

    /**
     * Multi-select "bookmark all" — native `ChapterSelectionActionsRow` bulk bookmark
     * (ChapterSelectionActionsRow.kt:74-76 → `onBookmarkAll`). Toggles the bookmark flag on every
     * selected chapter, then clears the selection. Gated on `state.isInLibrary` in the VM.
     */
    data object OnBookmarkSelected : DetailsIntent

    /**
     * Multi-select "delete downloaded" — native `ChapterSelectionActionsRow` delete action, shown
     * only when every selected chapter is downloaded (ChapterSelectionActionsRow.kt:82-87 →
     * `onDeleteAll`). Deletes the on-disk download for each selected chapter, then clears the
     * selection. The `:ui` bar only shows the action when all selected chapters are downloaded;
     * the VM resolves each url → Room id and deletes.
     */
    data object OnDeleteSelectedDownloads : DetailsIntent

    /**
     * Multi-select "mark this and below as read" — native `ChapterSelectionActionsRow`
     * mark-down-read action, shown only when exactly ONE chapter is selected
     * (ChapterSelectionActionsRow.kt:77-81 → `onMarkAllDownRead`). Marks every chapter below the
     * selected one (in the displayed reading order, EXCLUSIVE of the tapped chapter — matching
     * native) as read, then clears the selection.
     */
    data object OnMarkSelectedDownRead : DetailsIntent

    // ---- Top-bar overflow (native MangaTopAppBar) --------------------------------------------

    /**
     * User tapped "Delete all downloaded chapters" in the top-bar overflow menu (native
     * `MangaTopAppBar` MoreVert → `onDeleteDownloads`, which deletes every downloaded chapter of the
     * manga). The VM deletes the on-disk download for every downloaded chapter of the fetched
     * details. Gated on `state.isInLibrary`.
     */
    data object OnDeleteAllDownloads : DetailsIntent

    /**
     * User tapped the cancel-all-downloads Stop button in the top bar (native `MangaTopAppBar`
     * Stop icon, shown only while `isDownloadingAll` → `cancelAllDownloads`). Cancels every active
     * download for this manga's chapters.
     */
    data object OnCancelAllDownloads : DetailsIntent

    /** User tapped the back affordance. View pops via the emitted Effect. */
    data object OnBackClick : DetailsIntent

    // ---- P0-ADULT hard-block gate (native parity, see AdultGateStep) -------------------------
    //
    // For an adult/+18 manga the screen shows a three-step dialog chain where EVERY path
    // back-navigates and the content (cover / chapters) is NEVER revealed — a Google Play policy
    // block, mirroring the native `DialogState` chain. The reducer advances the gate step and
    // emits [DetailsEffect.NavigateBack] on every dismiss path; no path clears the gate to
    // [AdultGateStep.None], so the body is never shown.

    /**
     * User tapped Continue on the [AdultGateStep.AdultWarning] "Content unavailable" dialog —
     * advances the gate to [AdultGateStep.MStep1]. (Native wires the AdultWarning dialog's own
     * buttons to back-navigation; this intent exists for the documented chain advance and for any
     * UI variant that surfaces a Continue affordance on the warning step.)
     */
    data object OnAdultWarningContinue : DetailsIntent

    /**
     * User tapped Continue on the [AdultGateStep.MStep1] meme dialog — advances the gate to
     * [AdultGateStep.MStep2] (native: `MConfirmationDialog(imgs1, showContinue = true).onConfirm`).
     */
    data object OnAdultStep1Continue : DetailsIntent

    /**
     * User dismissed / closed the [AdultGateStep.MStep2] meme dialog (which has no Continue
     * button) — back-navigates (native: `MConfirmationDialog(imgs2, showContinue = false)`, both
     * confirm and dismiss call `onBackClick()`). The reducer emits [DetailsEffect.NavigateBack].
     */
    data object OnAdultStep2Dismiss : DetailsIntent

    /**
     * User dismissed any gate step via Close / Cancel / outside-tap / system back — back-navigates
     * immediately (native: AdultWarning `onDismiss`, MStep1 `onDismiss`). The reducer emits
     * [DetailsEffect.NavigateBack]. The content is never revealed.
     */
    data object OnAdultGateBack : DetailsIntent

    /**
     * User tapped the Downloads action in the top bar.
     *
     * Routes to the rework Downloads screen via [me.manga.kira.presentation.details.DetailsEffect.NavigateToDownloads].
     * No payload — the destination doesn't depend on the active manga (Downloads lists all
     * pending/active/completed jobs across the user's library). The `:ui` IconButton dispatches
     * unconditionally (no `isInLibrary` gate per ADR-4: the rework's Downloads screen renders its
     * own empty-state, and the legacy "bookmark first" coupling was a side effect of legacy
     * hosting Downloads inside the Library tab, not an intentional product rule).
     *
     * Phase 7.x.details.downloads §253 / ADR-3 (button placement) + ADR-4 (no isInLibrary gate).
     */
    data object OnDownloadClick : DetailsIntent

    /**
     * feature/backup — user tapped "Export manga" in the top-bar overflow (only rendered for an
     * in-library manga). VM re-checks membership and emits
     * [DetailsEffect.NavigateToBackupExport] with this manga's identity key.
     */
    data object OnExportManga : DetailsIntent

    /**
     * User tapped the header "Download all" action — legacy parity with the `HeaderSection`
     * `action_download_all` `ActionButton`, which enqueued every chapter for offline download
     * (gated on the manga being saved to the library first).
     *
     * The VM enqueues every chapter of the fetched [me.manga.kira.domain.model.MangaDetails]
     * via [me.manga.kira.domain.usecase.downloads.EnqueueAllChaptersDownloadUseCase], which
     * composes the same per-chapter enqueue path the rework Updates download button uses
     * ([me.manga.kira.domain.usecase.downloads.EnqueueDownloadUseCase], Tasks #299/#300) with a
     * `url` → Room-`chapterId` resolution step (the pure-domain
     * [me.manga.kira.domain.model.Chapter] is `url`-keyed and carries no surrogate id; the
     * download subsystem keys on the `Long` id). Chapters with no in-library row are skipped;
     * already-downloaded chapters are re-enqueued idempotently — same posture as the single-enqueue
     * path. The header button is gated on `state.isInLibrary` in `:ui` to mirror the legacy
     * "bookmark first" precondition, so in practice every chapter resolves.
     *
     * No payload — the VM derives the chapter set from `state.details` (the same field the fetch
     * populated), matching the no-payload precedent of [OnDownloadClick] / [OnToggleInLibrary] /
     * [OnOpenInWebView]. A use-case-level failure surfaces via the existing
     * [DetailsEffect.ShowError] snackbar.
     */
    data object OnDownloadAllClick : DetailsIntent

    // ---- Chapter filter / sort (native library_details, folded into Details) -----------------

    /**
     * User picked a chapter filter chip in the filter/sort bottom sheet — native
     * `LibraryDetailsViewModel.setFilter`. Sets [DetailsState.chapterFilter]; the rendered list is
     * re-derived via [DetailsState.displayChapters].
     */
    data class OnSetChapterFilter(val filter: ChapterFilterType) : DetailsIntent

    /**
     * User picked a chapter sort chip in the filter/sort bottom sheet — native
     * `LibraryDetailsViewModel.setSortType`. Sets [DetailsState.chapterSort].
     */
    data class OnSetChapterSort(val sort: ChapterSortType) : DetailsIntent

    /**
     * User toggled the chapter sort direction — the inline newest/oldest icon button above the
     * list, or the Ascending/Descending switch in the sort tab (native
     * `LibraryDetailsViewModel.toggleSort`). Flips [DetailsState.sortAscending].
     */
    data object OnToggleSortDirection : DetailsIntent

    /**
     * User tapped the bookmark (♥/♡) action in the top bar — or confirmed the first-time-add
     * dialog the `:ui` layer raises on the not-in-library tap path.
     *
     * No payload: the VM derives the identity from `state.manga`, the same field already populated
     * by the preceding [OnEnter]. This keeps the intent surface minimal (ISP) and aligns with the
     * `OnRetry` / `OnBackClick` precedent — every state-derived action carries no payload.
     *
     * Reactive: the VM does NOT manually flip `state.isInLibrary` on success; the
     * `ObserveInLibraryUseCase` flow (subscribed in [me.manga.kira.presentation.details.DetailsViewModel])
     * re-emits the post-write membership snapshot and the reducer absorbs it. Same posture the
     * Library slice already uses for `LibraryIntent.OnToggleInLibrary`.
     *
     * Phase 7.x.details.bookmark §253 / ADR-1.
     */
    data object OnToggleInLibrary : DetailsIntent

    /**
     * User tapped the WebView action — either the top-bar ↗ glyph (success state) or the
     * "Open in WebView" button on the error pane (error state). Both surfaces dispatch the same
     * intent because they carry the same payload (the active manga's `url` + `api`) and route to
     * the same effect.
     *
     * No payload: the VM derives `(url, api)` from `state.manga`, the same field populated by the
     * preceding [OnEnter]. Same posture as [OnRetry], [OnBackClick], [OnDownloadClick],
     * [OnToggleInLibrary] — every state-derived action carries no payload (ISP, SRP).
     *
     * Routes to [me.manga.kira.presentation.details.DetailsEffect.NavigateToWebView] carrying
     * the URL + API; `:composeApp` translates the effect into `safeNavigate(Screen.WebView(...))`.
     * `:ui` never names `Screen.WebView` — the callback is generic `(url, api) -> Unit` so the
     * screen stays nav-host-agnostic per the campaign clean-architecture guardrail.
     *
     * Phase 7.x.details.webview §253 / ADR-5 (one intent, both buttons — same payload, same effect).
     */
    data object OnOpenInWebView : DetailsIntent
}
