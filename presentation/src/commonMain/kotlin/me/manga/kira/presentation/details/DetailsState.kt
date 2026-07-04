package me.manga.kira.presentation.details

import me.manga.kira.core.error.AppError
import me.manga.kira.core.util.formatBytes
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.presentation.mvi.MviState

/**
 * Live per-chapter download status carried into the Details chapter row (PFIX-DLPROGRESS,
 * 2026-06-01). One value per chapter URL with an active download row, projected from
 * `ObserveDownloadsUseCase` joined to the displayed chapter list by Room id. The `:ui` chapter
 * row branches on [state] to render the native-parity affordance — determinate progress ring for
 * [DownloadState.RUNNING] (using [progress] 0-100), an indeterminate spinner for
 * [DownloadState.QUEUED], and a compressing spinner for [DownloadState.COMPRESSING] — and uses
 * [progress] only when running.
 *
 * Why a value type rather than the bare `Set<String>` membership it replaces: the old set carried
 * only "is this row active", so the row could show a cancel button but no live percent, and the
 * row's downloaded/idle/active branches were split across two race-prone reactive sources. Folding
 * state + progress into one map keyed by URL lets the row recompose on every DAO progress tick from
 * a single state snapshot (native `LibraryChapterItem` parity — it receives the live `runningChapter`
 * `ChapterDownloadEntity` with its `progress` and branches on `state`).
 *
 * Lives in `:presentation` (not `:domain`) deliberately: it is a presentation projection of the
 * already-reactive `DownloadedChapter` domain model, so it adds no `:domain` ripple. Only the two
 * fields the row renders are kept — the chapter URL is the map key, and the surrogate id / manga id
 * / error text the Downloads screen needs are not relevant to a Details row.
 */
data class ChapterDownloadProgress(
    val state: DownloadState,
    /** 0-100; meaningful only when [state] is [DownloadState.RUNNING] (native parity). */
    val progress: Int,
    /**
     * Final on-disk size in bytes; non-zero only once [state] is [DownloadState.SUCCESS] (native
     * size display). 0 while the download is still active. Default 0.
     */
    val sizeBytes: Long = 0,
    /** Room `saved_chapters.id` of this download row — needed to cancel/interrupt the worker. */
    val chapterId: Long = 0,
    /** Room `saved_manga.id` owning this chapter — needed by the running-cancel (file cleanup). */
    val mangaId: Long = 0,
) {
    /**
     * True while this download occupies an active queue slot (QUEUED / RUNNING / COMPRESSING) —
     * the same Active bucket native uses (DownloadState KDoc). A SUCCESS entry is NOT active (it is
     * the completed/downloaded state); a FAILED row is never placed in
     * [DetailsState.chapterDownloads] at all.
     */
    val isActive: Boolean
        get() = state == DownloadState.QUEUED ||
            state == DownloadState.RUNNING ||
            state == DownloadState.COMPRESSING ||
            // iOS background: pages on disk, finalization (CBZ) pending — still "finishing".
            state == DownloadState.DOWNLOADED

    /**
     * True once this chapter's download has completed. Carried in the map (rather than relying only
     * on the saved-details `Chapter.isDownloaded`) so the running→downloaded transition is atomic
     * from the single downloads flow — the `:ui` row flips from progress ring to the downloaded
     * mark in one state snapshot, with no leave/re-enter flash (restart of the old two-source race).
     */
    val isDownloaded: Boolean get() = state == DownloadState.SUCCESS

    /**
     * Human-readable on-disk size (e.g. `"15.2 MB"`) for the native size display, or `null` until
     * the download has completed with a known size. Computed here (in `:presentation`, via `:core`
     * `formatBytes`) so the `:ui` row can render it without depending on `:core`/`:platform`.
     */
    val sizeLabel: String?
        get() = if (state == DownloadState.SUCCESS && sizeBytes > 0L) formatBytes(sizeBytes) else null
}

/**
 * Immutable Details screen state.
 *
 * Strict MVI: every property is `val`. The reducer in [DetailsViewModel] is the only writer; the
 * view observes [me.manga.kira.presentation.mvi.MviViewModel.state] read-only.
 *
 * Shape design (vs. the legacy `StateFlow<State<MangaInfo>>` the source `MangaDerailsViewModel`
 * exposed):
 *  - **Loading is a `Boolean` flag, not a wrapping sealed case.** The legacy `State<MangaInfo>`
 *    sum type forced the screen to `when`-match on every render, which is fine for a sealed
 *    network outcome but awkward once the screen wants to show a half-loaded cover or a stale
 *    chapter list while a refresh is in flight. Splitting `isLoading`/`details`/`error` lets the
 *    screen render each component independently and lights the way for a future pull-to-refresh
 *    (no shape change required — flip [isLoading] back to `true` while keeping [details]).
 *  - **`manga: Manga?` is held in state.** The legacy VM stashed `currentUrl` as a private
 *    mutable field set by `initialize(...)`. The rework keeps the identity in state so a
 *    configuration-change re-emission of the StateFlow on a fresh host shows the same screen —
 *    no out-of-band `initialize` call needed. The host triggers the fetch by submitting
 *    [DetailsIntent.OnEnter] which carries the [Manga] explicitly.
 *  - **`error` is `AppError?` not `String?`.** The view translates the typed error to a
 *    localized message (the `:ui` Details screen lands in Phase 7.x); presentation never embeds
 *    user-visible text.
 *  - **`isAdult: Boolean` mirrors the legacy `isPlus18` gate.** The VM computes this from the
 *    `IsAdultContentUseCase` whenever the active [Manga] or [MangaDetails] changes (legacy
 *    behaviour: classify from the *fetched* `MangaInfo.genres`, not from the nav-arg manga).
 *    Default `false` is safe because the unknown-source contract on `AdultContentClassifier`
 *    also returns `false`; the view can render the chapter list normally until a fetch completes
 *    and the flag flips to `true` for an adult source. The actual UI gating (cover blur /
 *    content-warning chip / chapter-list gate) is a `:ui` surface slice that consumes this flag —
 *    out of scope for the VM-wiring step that introduced it.
 *  - **`isInLibrary: Boolean` mirrors the legacy `savedMangaTitles.contains(ApiTitle)` reactive
 *    gate.** Driven by the reactive `LibraryRepository.observeIsInLibrary` flow (Room `EXISTS`
 *    query) — the VM collects the flow keyed on the current manga identity and re-emits state on
 *    every cross-screen library write, so the bookmark heart stays in sync with toggles dispatched
 *    from Library / Home / Updates without any manual refresh path. Default `false` is safe: it
 *    matches the "not yet observed" boot state and the "manga not in library" steady state,
 *    so the UI renders the empty heart while the first emission lands (typically same frame).
 *    Phase 7.x.details.bookmark §253.
 *  - **`isTogglingBookmark: Boolean` gates the heart IconButton against double-tap races.** The
 *    `ToggleInLibraryUseCase` reads `repository.get(...)` THEN add/removes — non-atomic at the
 *    domain boundary. Without an explicit MVI gate, a rapid double-tap could fire two concurrent
 *    use-case calls before the reactive flow re-emission flips `isInLibrary`; the second call
 *    would then see the post-first-call state and toggle BACK, leaving the user where they
 *    started. The VM sets this flag `true` immediately before the use-case invocation and resets
 *    it in a `finally`, and drops a re-entrant intent synchronously (the handler's
 *    `isTogglingBookmark` early-return) before the first suspension. The guard is VM-side only —
 *    no `:ui` affordance currently binds its `enabled` parameter to `!isTogglingBookmark`. Per
 *    the §253 plan's edge-case decision: clean state-driven re-entry guarding beats relying on
 *    Room transactional semantics, which would leave a brief window during which a redundant
 *    second use-case call could fire — observable as an unexpected "toggle back" outcome.
 *    Phase 7.x.details.bookmark §253.
 *
 * Why no `selectedChapter` or read-progress fields here: chapter selection navigates to the
 * Reader feature via an effect ([DetailsEffect.NavigateToReader]); per-chapter read state lives
 * inside [MangaDetails.chapters] (each [me.manga.kira.domain.model.Chapter] already carries
 * `isDownloaded` / `isBookmarked`). When the Reader slice ships its own state, this screen will
 * observe a re-fetch / cache invalidation, not a cross-feature state coupling.
 *
 * Why no `showAddBookmarkAlert: Boolean` for the first-time-add confirm dialog: dialog-style
 * one-shot UI flags live in `:ui` `remember { mutableStateOf(false) }` per §48.6, mirroring the
 * existing `adultDialogVisible` precedent. MVI state holds cross-frame screen state; the dialog
 * is a within-frame UI affordance and doesn't need to survive process death.
 *
 * **Audit-trail postscript** (Phase 9.x.mangadetails.staleKdocSweep.cascade.followup,
 * Task #447, 2026-05-28): the "Shape design (vs. the legacy ... source `MangaDerailsViewModel`
 * exposed)" paragraph (lines 14-15 above) cites the legacy VM's `StateFlow<State<MangaInfo>>`
 * shape as the comparator that justified each of this state class's design choices
 * (`isLoading` Boolean flag, `manga: Manga?` held in state, `error: AppError?`, `isAdult`,
 * `isInLibrary`, `isTogglingBookmark`). The legacy `MangaDerailsViewModel` was retired in
 * Phase 9.x.mangadetails.retire (§430, Slice 5 of the Phase 7.x.details.parity campaign);
 * verified by Glob search for `MangaDerailsViewModel.kt` returning zero hits. The design
 * rules continue to apply to this state class's current shape — they're justified by their
 * own merits (independent rendering of each component, no out-of-band `initialize`, typed
 * errors, double-tap-race gate) regardless of which predecessor they were designed against.
 * Original §253-era prose preserved verbatim per §253 — the legacy comparison is historical
 * record of the design lineage.
 */
data class DetailsState(
    val isLoading: Boolean = false,
    val manga: Manga? = null,
    val details: MangaDetails? = null,
    val error: AppError? = null,
    val isAdult: Boolean = false,
    /**
     * P0-ADULT hard-block gate step (native parity). For an adult manga the VM drives this through
     * [AdultGateStep.AdultWarning] → [AdultGateStep.MStep1] → [AdultGateStep.MStep2], where EVERY
     * exit back-navigates and the cover/chapters are NEVER revealed (Google Play policy block,
     * mirrors the native `DialogState` chain). A non-adult manga keeps the default
     * [AdultGateStep.None] and renders normally. This lives in MVI state (not a `:ui` `remember`)
     * because it is a compliance-critical gate: it must survive configuration change / process
     * death so an adult manga can never momentarily render its content through a state-loss window.
     */
    val adultGateStep: AdultGateStep = AdultGateStep.None,
    val isInLibrary: Boolean = false,
    val isTogglingBookmark: Boolean = false,
    /**
     * #4: device reachability, projected from `ObserveConnectivityUseCase`. Defaults to `true`
     * (optimistic) so the download gates never block before the first connectivity emission — i.e.
     * absence of a signal = enqueue allowed = current behavior. The chapter/download-all handlers
     * read this to give immediate offline feedback instead of enqueuing a no-op.
     */
    val isOnline: Boolean = true,
    /**
     * PFIX-DLPROGRESS (2026-06-01) + restart of the completion-freeze fix (2026-06-02): live
     * per-chapter download status, keyed by chapter `url`, projected from `ObserveDownloadsUseCase`
     * joined to the displayed chapters DIRECTLY by `url` (`DownloadedChapter.url`) — no per-row
     * suspend id-resolve, so the map is rebuilt synchronously in one `updateState`.
     *
     * Holds an entry for each displayed chapter with an active (QUEUED / RUNNING / COMPRESSING) OR
     * completed (SUCCESS) download row; FAILED rows are excluded (the row then shows the idle
     * Download button to retry). Keeping the SUCCESS row here is what makes the running→downloaded
     * transition atomic: it arrives in the SAME single downloads-flow emission that dropped RUNNING,
     * so the `:ui` row flips from progress ring straight to the downloaded mark in one snapshot —
     * no dependency on the separately-delivered saved-details `Chapter.isDownloaded`, and therefore
     * no "downloading → downloaded only after leave/return" flash. The SUCCESS entry also carries
     * [ChapterDownloadProgress.sizeBytes] for the native size display.
     *
     * The `:ui` chapter row branches: active → determinate ring (RUNNING, using `progress`) or
     * spinner (QUEUED / COMPRESSING) + cancel; else downloaded (entry `isDownloaded` OR
     * `Chapter.isDownloaded`) → downloaded mark + size; else idle Download. Default empty.
     *
     * Replaces the prior boolean `downloadingChapterUrls: Set<String>` membership set, which threw
     * the `progress` away (no live percent). [downloadingChapterUrls] below is kept as a derived
     * view over this map (active entries only) so existing call sites (cancel-all,
     * selection-all-downloaded) are unaffected.
     */
    val chapterDownloads: Map<String, ChapterDownloadProgress> = emptyMap(),
    /**
     * GAP-LIB-10: set of selected chapter `url`s in multi-select mode. Empty = not in selection
     * mode. Long-press enters selection (adds the long-pressed chapter); taps toggle membership;
     * the selection action bar operates on this set. Cleared on Cancel / system back / after a
     * bulk action completes.
     */
    val selectedChapterUrls: Set<String> = emptySet(),
    /**
     * Chapter-list filter (native `LibraryDetailsViewModel._filterType`, default `ALL`). Drives the
     * filter chips in the chapter filter/sort bottom sheet and selects which chapters render via
     * [displayChapters]. Folded native library_details filter feature.
     */
    val chapterFilter: ChapterFilterType = ChapterFilterType.ALL,
    /**
     * Chapter-list sort key (native `LibraryDetailsViewModel._sortType`, default `ID`). Combined with
     * [sortAscending] for direction; selects the ordering applied by [displayChapters].
     */
    val chapterSort: ChapterSortType = ChapterSortType.ID,
    /**
     * Chapter sort direction. Native default is descending (`_sortAscending = false`,
     * LibraryDetailsViewModel.kt:67) so the newest chapter shows first.
     */
    val sortAscending: Boolean = false,
) : MviState {

    /**
     * Derived active-download URL set (PFIX-DLPROGRESS). The chapter `url`s whose [chapterDownloads]
     * entry is still active (QUEUED / RUNNING / COMPRESSING). Kept as a `get()` over the richer
     * [chapterDownloads] map so callers that only need the membership set — the top-bar
     * cancel-all-downloads action and [isDownloadingAny] / [isSelectionAllDownloaded] — continue to
     * work unchanged after the boolean-set → status-map migration.
     */
    val downloadingChapterUrls: Set<String> by lazy {
        chapterDownloads.filterValues { it.isActive }.keys
    }

    /** Convenience: true once a successful fetch has populated [details]. */
    val hasDetails: Boolean get() = details != null

    /** Convenience: true when we're loading the first fetch (no prior details yet). */
    val isInitialLoading: Boolean get() = isLoading && details == null

    /** Convenience: true when the chapter list is in multi-select mode (GAP-LIB-10). */
    val isInChapterSelectionMode: Boolean get() = selectedChapterUrls.isNotEmpty()

    /**
     * L-7: true while any of this manga's chapters has an active download. Drives the top-bar
     * cancel-all-downloads Stop button visibility (native `MangaTopAppBar` shows it only while
     * `isDownloadingAll`).
     */
    val isDownloadingAny: Boolean get() = downloadingChapterUrls.isNotEmpty()

    /**
     * L-4: true when every chapter in the current selection is downloaded — the condition under
     * which native's `ChapterSelectionActionsRow` shows the Delete action. False when the selection
     * is empty.
     */
    val isSelectionAllDownloaded: Boolean by lazy {
        if (selectedChapterUrls.isEmpty()) {
            false
        } else {
            val downloadedUrls = details?.chapters
                ?.filter { it.isDownloaded }
                ?.map { it.url }
                ?.toSet()
            downloadedUrls != null && selectedChapterUrls.all { it in downloadedUrls }
        }
    }

    /**
     * P0-ADULT: true while the hard-block adult gate is active (any step other than
     * [AdultGateStep.None]). The `:ui` layer uses this to keep the cover blurred and to suppress
     * the chapter body entirely for an adult manga — content is never revealed because every gate
     * exit back-navigates (the gate can never advance to [AdultGateStep.None]).
     */
    val isAdultGateActive: Boolean get() = adultGateStep != AdultGateStep.None

    /**
     * The chapter list as it should render: de-duped by `url`, then filtered by [chapterFilter] and
     * sorted by [chapterSort] honouring [sortAscending]. Native parity — the native chapter list was
     * a `combine(mangaId, sortAscending, filterType, sortType)` over the saved chapters
     * (LibraryDetailsViewModel.kt:91-122); here the same filter+sort is derived over the fetched
     * [MangaDetails.chapters]. Empty when there are no details yet.
     *
     * The de-dup mirrors the prior `:ui` `distinctBy(Chapter::url)` (some sources list a chapter
     * twice, which would crash the LazyColumn on a duplicate key); doing it here keeps the displayed
     * count, the resume target ([firstUnreadChapter]) and the rendered list in agreement.
     */
    val displayChapters: List<Chapter> by lazy {
        val base = details?.chapters?.distinctBy(Chapter::url)
        if (base == null) {
            emptyList()
        } else {
            val filtered = when (chapterFilter) {
                ChapterFilterType.ALL -> base
                ChapterFilterType.DOWNLOADED -> base.filter { it.isDownloaded }
                ChapterFilterType.UNREAD -> base.filter { !it.isRead }
                ChapterFilterType.READED -> base.filter { it.isRead }
                ChapterFilterType.BOOKMARKED -> base.filter { it.isBookmarked }
            }
            // The base list arrives newest-first (source order); `ID` and `LAST_READ_DATE` preserve
            // that source order (native's autoincrement `id` reflects insertion order, and the
            // domain model carries no per-chapter read timestamp — see ChapterSortType KDoc). The
            // ascending toggle reverses whatever the chosen key produced (native: `if (asc) sorted
            // else sorted.reversed()` over an ascending base — here the base is descending source
            // order, so the directional intent is preserved by reversing for ascending).
            val sorted = when (chapterSort) {
                ChapterSortType.ID -> filtered
                ChapterSortType.LAST_READ_DATE -> filtered
                ChapterSortType.NUMBER -> filtered.sortedByDescending { it.number.toDoubleOrNull() ?: 0.0 }
                ChapterSortType.DATE -> filtered.sortedByDescending { it.date }
            }
            if (sortAscending) sorted.reversed() else sorted
        }
    }

    /**
     * The chapter the Resume FAB jumps to: the first not-yet-read chapter in *reading* order
     * (oldest-unread), honouring sort direction exactly like native LibraryMangaScreen.kt:144-152
     * (`if (sortAscending) chapters.firstOrNull{!isRead} else chapters.reversed().firstOrNull{!isRead}`).
     *
     * [displayChapters] is oldest-first only when [sortAscending]; under the default descending sort
     * it is newest-first, so a naive `displayChapters.firstOrNull { !it.isRead }` would resume at the
     * *newest* unread chapter instead of the next-to-read one. Reducing to reading order
     * (`asReversed()` when descending) before scanning fixes that while still tracking the current
     * filter. Null when every (filtered) chapter is read — the FAB then shows the "You finished this
     * manga" state.
     */
    val firstUnreadChapter: Chapter? by lazy {
        (if (sortAscending) displayChapters else displayChapters.asReversed())
            .firstOrNull { !it.isRead }
    }

    /**
     * Native size-display parity (2026-06-02). The human-readable on-disk size for the chapter at
     * [url] (e.g. `"15.2 MB"`), or `null` when the chapter isn't downloaded or its size isn't known
     * yet. Native shows this next to the chapter date in `LibraryChapterItem`, only for downloaded
     * chapters. Sourced from the SUCCESS [chapterDownloads] entry's [ChapterDownloadProgress.sizeBytes]
     * (back-filled for pre-existing downloads by the startup reconcile), formatted via `:core`
     * `formatBytes`.
     */
    fun chapterSizeLabel(url: String): String? = chapterDownloads[url]?.sizeLabel

    /**
     * Number of downloaded chapters for the native total-size header "<size> • <N> downloaded".
     * Derived from the SAME source as [totalDownloadedSizeLabel] — the completed (SUCCESS)
     * [chapterDownloads] entries — so the count and the size never disagree (e.g. they drop together
     * the instant a download row is deleted). Counting `details.chapters{isDownloaded}` instead would
     * desync: the legacy delete path removes the `chapter_downloads` row without clearing
     * `saved_chapters.isDownloaded`, leaving "12 MB • 13 downloaded" where only 12 contribute size.
     */
    val downloadedChapterCount: Int
        get() = chapterDownloads.values.count { it.isDownloaded }

    /**
     * Native `TotalSizeDisplay` parity: the formatted sum of every downloaded chapter's on-disk size
     * (e.g. `"150.5 MB"`), or `null` when nothing downloaded / no sizes known yet. Summed over the
     * SUCCESS [chapterDownloads] entries' [ChapterDownloadProgress.sizeBytes].
     */
    val totalDownloadedSizeLabel: String?
        get() {
            val total = chapterDownloads.values
                .filter { it.state == DownloadState.SUCCESS }
                .sumOf { it.sizeBytes }
            return if (total > 0L) formatBytes(total) else null
        }
}
