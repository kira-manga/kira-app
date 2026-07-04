package me.manga.kira.presentation.library

import androidx.lifecycle.viewModelScope
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import me.manga.kira.core.error.AppError
import me.manga.kira.core.logging.FlowLog
import me.manga.kira.core.result.onFailure
import me.manga.kira.core.result.onSuccess
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.library.GridDensity
import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.model.library.SortDirection
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.library.BulkRemoveFromLibraryUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryCategoryUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryDisplayUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryFilterUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryGridDensityUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryItemsPerRowUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryLastUpdatedUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryRandomSeedUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryRefreshResultUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryRefreshUseCase
import me.manga.kira.domain.usecase.library.ObserveLibrarySortDirectionUseCase
import me.manga.kira.domain.usecase.library.ObserveLibrarySortUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.domain.usecase.library.RefreshLibraryUseCase
import me.manga.kira.domain.usecase.library.SetLibraryCategoryUseCase
import me.manga.kira.domain.usecase.library.SetLibraryFilterUseCase
import me.manga.kira.domain.usecase.library.SetLibraryGridDensityUseCase
import me.manga.kira.domain.usecase.library.SetLibraryItemsPerRowUseCase
import me.manga.kira.domain.usecase.library.SetLibraryRandomSeedUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowButtonsUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowCountUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowDetailsUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowSourceUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowTabsUseCase
import me.manga.kira.domain.usecase.library.SetLibrarySortDirectionUseCase
import me.manga.kira.domain.usecase.library.SetLibrarySortUseCase
import me.manga.kira.domain.usecase.library.ToggleMangaLikedUseCase
import me.manga.kira.domain.usecase.library.ToggleMangaWatchingNowUseCase
import me.manga.kira.domain.usecase.settings.ObserveSettingsUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Library screen ViewModel.
 *
 * Strict MVI: state lives in [LibraryState]; intents are sealed; effects are one-shot.
 * Constructor-injected use cases (DIP) — never depends on the data layer directly.
 *
 * Search filtering AND sort ordering happen locally over the unfiltered snapshot [allItems]
 * via [applyView]. Server-side search is not part of this slice (library is local-only); a
 * future Phase 6.x can add remote search by injecting a separate `SearchMangaUseCase` and a
 * different intent.
 *
 * SRP: orchestrates Library presentation state and nothing else. Bulk operations / chapter
 * downloads / history are handled by their own future ViewModels.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster28.staleKdocSweep.cascade,
 * Task #484, 2026-05-28): one stale citation into the §347-retired
 * legacy `:shared` `LibraryViewModel.kt` appears below:
 *  - Line 586 inside [applyView]'s KDoc ("Matches the legacy
 *    `LibraryViewModel.kt:204-248` pipeline shape — narrowing steps
 *    first..."). STALE-SYMBOL-REFERENCE — Phase 9.x.library.retire
 *    (§347, commit `2debbec`) DELETED the legacy `:shared` Library
 *    surface in its entirety, including the cited `LibraryViewModel.
 *    kt`. A recursive search of `:shared/.../presentation/features/
 *    library/` for a file named `LibraryViewModel.kt` returns NO
 *    MATCHES — the cite-target no longer exists on disk. The pipeline-shape rationale itself stands
 *    on its own merits (narrowing → sort → reverse is sound regardless
 *    of legacy lineage); the citation is historical record of where
 *    the design lineage originated. Phase 9.x.library.swap (§346)
 *    re-pointed `Screen.Library`'s rendering adapter to the rework
 *    [LibraryScreen]; Phase 9.x.library.deadcomposable.retire (§348)
 *    cleaned up follow-on dead surfaces. The rework [LibraryViewModel]
 *    remains LIVE as the canonical Library-feature MVI orchestrator.
 *    Original prose preserved verbatim per the audit-trail-preservation
 *    convention — the citation marks the design lineage of the
 *    pipeline shape, even though the cited file has been retired.
 */
@OptIn(ExperimentalTime::class)
class LibraryViewModel(
    private val observeLibrary: ObserveLibraryUseCase,
    private val bulkRemoveFromLibrary: BulkRemoveFromLibraryUseCase,
    private val refreshLibrary: RefreshLibraryUseCase,
    private val observeLibraryRefresh: ObserveLibraryRefreshUseCase,
    private val observeLibraryRefreshResult: ObserveLibraryRefreshResultUseCase,
    private val observeLibrarySort: ObserveLibrarySortUseCase,
    private val setLibrarySort: SetLibrarySortUseCase,
    private val observeLibrarySortDirection: ObserveLibrarySortDirectionUseCase,
    private val setLibrarySortDirection: SetLibrarySortDirectionUseCase,
    private val observeLibraryFilter: ObserveLibraryFilterUseCase,
    private val setLibraryFilter: SetLibraryFilterUseCase,
    private val observeLibraryGridDensity: ObserveLibraryGridDensityUseCase,
    private val setLibraryGridDensity: SetLibraryGridDensityUseCase,
    private val observeLibraryItemsPerRow: ObserveLibraryItemsPerRowUseCase,
    private val setLibraryItemsPerRow: SetLibraryItemsPerRowUseCase,
    private val observeLibraryCategory: ObserveLibraryCategoryUseCase,
    private val setLibraryCategory: SetLibraryCategoryUseCase,
    private val observeLibraryLastUpdated: ObserveLibraryLastUpdatedUseCase,
    private val observeLibraryDisplay: ObserveLibraryDisplayUseCase,
    private val setLibraryShowSource: SetLibraryShowSourceUseCase,
    // §150 rung 16c (Task #336): setter for the showCount display toggle. Mirrors
    // [setLibraryShowSource] one-line wire; the bundled observer in `init {}`
    // already covers the read side for all five flags.
    private val setLibraryShowCount: SetLibraryShowCountUseCase,
    // §150 rung 16d (Task #338): setter for the showDetails display toggle. Mirrors
    // [setLibraryShowCount] one-line wire; same bundled-observer read posture.
    private val setLibraryShowDetails: SetLibraryShowDetailsUseCase,
    // §150 rung 16e (Task #340): setter for the showButtons display toggle. Mirrors
    // [setLibraryShowDetails] one-line wire; same bundled-observer read posture.
    // Single sub-rung (no `:ui` gate follow-on) — the legacy MangaCard bottom action
    // row hasn't been ported yet, so there's no rework `:ui` consumer to gate. The
    // VM-side write path still lands so legacy display-sheet flips propagate through
    // the shared `library_show_buttons` disk cell.
    private val setLibraryShowButtons: SetLibraryShowButtonsUseCase,
    // §150 rung 16f (Task #341): setter for the showTabs display toggle. Closes the
    // 5/5 per-flag vertical ladder (showSource → showCount → showDetails → showButtons
    // → showTabs). The `:ui` gate lifts in the SAME slice as the VM wiring because the
    // gate is one `if (state.display.showTabs)` wrapping the screen-level CategoryTabs
    // row (not per-card) — fits inside the same ≤5-file commit cap.
    private val setLibraryShowTabs: SetLibraryShowTabsUseCase,
    private val observeDownloads: ObserveDownloadsUseCase,
    // §179 rung 19 (Task #345): per-card action-row toggles. `Liked` and `WatchingNow` are
    // flip-not-set semantics (no `value` arg — the repository derives the new boolean from
    // the persisted row's current value). The third action of the row, single-delete, reuses
    // the existing [bulkRemoveFromLibrary] with a 1-element list — no new ctor arg there.
    private val toggleMangaLiked: ToggleMangaLikedUseCase,
    private val toggleMangaWatchingNow: ToggleMangaWatchingNowUseCase,
    // Global "Downloaded only" Settings toggle source (native parity: it filters ALL library
    // entries). Read-only observation; the write side lives in the Settings feature.
    private val observeSettings: ObserveSettingsUseCase,
    // RANDOM-sort stable-shuffle seed (native KEY_SEED parity): persisted so a RANDOM-sorted grid
    // keeps the same order across re-emissions and restarts instead of reshuffling every frame.
    private val observeLibraryRandomSeed: ObserveLibraryRandomSeedUseCase,
    private val setLibraryRandomSeed: SetLibraryRandomSeedUseCase,
) : MviViewModel<LibraryState, LibraryIntent, LibraryEffect>(
    initialState = LibraryState(),
) {

    private var observeJob: Job? = null
    private var allItems: List<LibraryManga> = emptyList()

    init {
        observeLibraryRefresh()
            .onEach { running -> updateState { it.copy(isRefreshing = running) } }
            .launchIn(viewModelScope)

        // Surface a failed inline refresh (Desktop/iOS) as an error effect — otherwise a fully-failed
        // pull-to-refresh is silently indistinguishable from "library is up to date". `null` (no run
        // yet) and Success are intentionally silent; Android's worker reports its own failures.
        observeLibraryRefreshResult()
            .onEach { result -> result?.onFailure { emit(LibraryEffect.ShowError(it)) } }
            .launchIn(viewModelScope)

        observeLibrarySort()
            .onEach { sort ->
                updateState {
                    it.copy(
                        sort = sort,
                        items = applyView(allItems, it.searchQuery, sort, it.sortDirection, it.randomSeed, it.filter, it.category, it.downloadedOnly),
                    )
                }
            }
            .launchIn(viewModelScope)

        observeLibrarySortDirection()
            .onEach { direction ->
                updateState {
                    it.copy(
                        sortDirection = direction,
                        items = applyView(allItems, it.searchQuery, it.sort, direction, it.randomSeed, it.filter, it.category, it.downloadedOnly),
                    )
                }
            }
            .launchIn(viewModelScope)

        observeLibraryFilter()
            .onEach { filter ->
                updateState {
                    it.copy(
                        filter = filter,
                        items = applyView(allItems, it.searchQuery, it.sort, it.sortDirection, it.randomSeed, filter, it.category, it.downloadedOnly),
                    )
                }
            }
            .launchIn(viewModelScope)

        observeLibraryGridDensity()
            .onEach { density -> updateState { it.copy(gridDensity = density) } }
            .launchIn(viewModelScope)

        // Items-per-row persistence (native parity — `library_items_per_row` Int cell). Same
        // status-update-only posture as the density collector: no `applyView` re-run because the
        // column count only changes how the same `items` list is laid out. Defensive coerce on
        // read mirrors the reducer below — a corrupt/out-of-range persisted value (e.g. a future
        // build that widened the slider) still lands inside the supported `0..8` range.
        observeLibraryItemsPerRow()
            .onEach { count -> updateState { it.copy(itemsPerRow = count.coerceIn(0, 8)) } }
            .launchIn(viewModelScope)

        observeLibraryCategory()
            .onEach { category ->
                updateState {
                    it.copy(
                        category = category,
                        items = applyView(allItems, it.searchQuery, it.sort, it.sortDirection, it.randomSeed, it.filter, category, it.downloadedOnly),
                    )
                }
            }
            .launchIn(viewModelScope)

        // §160.lastupdated (Task #326): status-indicator collector — no `applyView` re-run because
        // the timestamp doesn't narrow or reorder the visible item set (unlike sort/filter/category).
        // Same posture as the §157 density collector. On iOS/Desktop the underlying SharedPrefs cell
        // is never written (Android-only legacy `LibraryRefreshWorker` owns the write); the flow
        // emits `null` indefinitely there and the `:ui` row falls back to "Never updated".
        observeLibraryLastUpdated()
            .onEach { instant -> updateState { it.copy(lastUpdated = instant) } }
            .launchIn(viewModelScope)

        // §161.downloadprogress (Task #327): status-indicator collector for the top-bar
        // download-in-flight badge. Aggregates the global downloads list into a single count
        // by filtering rows in the "active" bucket (RUNNING ∪ QUEUED ∪ COMPRESSING — see
        // `DownloadState` KDoc). No `applyView` re-run because the count doesn't narrow or
        // reorder the visible library item set. Same posture as the §157 density + §160
        // lastUpdated collectors.
        //
        // Why inline filter (vs. an `ObserveActiveDownloadCountUseCase` in `:domain`): the
        // bucket definition lives in `DownloadState`'s KDoc as a presentation concern (the
        // legacy Downloads screen groups exactly these three states for its "Active" tab).
        // Pushing it into a new use case would duplicate the rule on the `:domain` side
        // without consolidating it — the rule is already part of `DownloadState`'s contract.
        // If a second consumer emerges (e.g., a global app-bar indicator), the aggregation
        // can lift to `:domain` then; for one consumer, keeping it here is YAGNI-correct.
        observeDownloads()
            .onEach { downloads ->
                val activeCount = downloads.count { it.state.isActive() }
                updateState { it.copy(activeDownloadCount = activeCount) }
            }
            .launchIn(viewModelScope)

        // §150 rung 16b (Task #334): display-toggle bundle collector — projects the persisted
        // five-flag snapshot into `state.display`. No `applyView` re-run because toggle flips
        // don't narrow or reorder the visible item set; they only change which `:ui` surfaces
        // (cardsource caption, cardcount caption, category tab row, …) are visible. Same
        // status-update-only posture as the §157 density / §160 lastUpdated / §161
        // downloadprogress collectors.
        observeLibraryDisplay()
            .onEach { display -> updateState { it.copy(display = display) } }
            .launchIn(viewModelScope)

        // Global "Downloaded only" Settings toggle (native parity: it "Filters all entries in your
        // library"). Re-run applyView when it flips so the grid is constrained to downloaded manga,
        // overriding the selected filter chip. `map`+`distinctUntilChanged` so unrelated Settings
        // changes (theme, cache size, …) carried by the shared snapshot don't churn the view.
        observeSettings()
            .map { it.downloadedOnly }
            .distinctUntilChanged()
            .onEach { downloadedOnly ->
                updateState {
                    it.copy(
                        downloadedOnly = downloadedOnly,
                        items = applyView(allItems, it.searchQuery, it.sort, it.sortDirection, it.randomSeed, it.filter, it.category, downloadedOnly),
                    )
                }
            }
            .launchIn(viewModelScope)

        // Seed the RANDOM-sort shuffle from persistence so the order is STABLE across re-emissions
        // and restarts (native KEY_SEED parity; default 64464L). Re-runs applyView so a RANDOM-sorted
        // grid reflects the loaded seed instead of falling back to a non-deterministic `shuffled()`.
        observeLibraryRandomSeed()
            .onEach { seed ->
                updateState {
                    it.copy(
                        randomSeed = seed,
                        items = applyView(allItems, it.searchQuery, it.sort, it.sortDirection, seed, it.filter, it.category, it.downloadedOnly),
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Active-bucket membership predicate, mirroring `DownloadState`'s KDoc:
     *   Active = RUNNING ∪ QUEUED ∪ COMPRESSING
     *
     * Private to this file (not a top-level extension on `DownloadState`) because the
     * "active" concept is currently library-VM-local — the Downloads screen has its own
     * three-tab grouping (Active / Failed / Completed) where this same predicate is the
     * "Active" tab's filter. If a third call site emerges, lift to a shared extension in
     * `:domain` then; for two call sites, duplication is cheaper than a cross-module helper.
     */
    private fun DownloadState.isActive(): Boolean = when (this) {
        DownloadState.RUNNING, DownloadState.QUEUED, DownloadState.COMPRESSING, DownloadState.DOWNLOADED -> true
        DownloadState.SUCCESS, DownloadState.FAILED -> false
    }

    override suspend fun handle(intent: LibraryIntent) {
        when (intent) {
            LibraryIntent.OnEnter -> startObserving()
            LibraryIntent.OnRefresh -> onRefresh()
            is LibraryIntent.OnItemClick -> onItemClick(intent.manga)
            is LibraryIntent.OnItemLongClick -> onItemLongClick(intent.key)
            is LibraryIntent.OnSelectionToggle -> onSelectionToggle(intent.key)
            LibraryIntent.OnSelectionClear -> updateState {
                it.copy(selection = emptySet(), isInSelectionMode = false)
            }
            LibraryIntent.OnDeleteSelected -> onDeleteSelectedRequest()
            LibraryIntent.OnDeleteSelectedConfirm -> onDeleteSelectedConfirm()
            LibraryIntent.OnDeleteSelectedDismiss -> updateState {
                it.copy(isDeleteDialogVisible = false)
            }
            is LibraryIntent.OnSearchQueryChange -> onSearchQueryChange(intent.query)
            LibraryIntent.OnOpenRandom -> onOpenRandom()
            is LibraryIntent.OnSortChange -> onSortChange(intent.sort)
            LibraryIntent.OnSortDirectionToggle -> onSortDirectionToggle()
            is LibraryIntent.OnFilterChange -> onFilterChange(intent.filter)
            is LibraryIntent.OnCategoryChange -> onCategoryChange(intent.category)
            is LibraryIntent.OnGridDensityChange -> onGridDensityChange(intent.density)
            is LibraryIntent.OnItemsPerRowChange -> onItemsPerRowChange(intent.count)
            is LibraryIntent.OnToggleShowSource -> onToggleShowSource(intent.value)
            is LibraryIntent.OnToggleShowCount -> onToggleShowCount(intent.value)
            is LibraryIntent.OnToggleShowDetails -> onToggleShowDetails(intent.value)
            is LibraryIntent.OnToggleShowButtons -> onToggleShowButtons(intent.value)
            is LibraryIntent.OnToggleShowTabs -> onToggleShowTabs(intent.value)
            is LibraryIntent.OnToggleLike -> onToggleLike(intent.key)
            is LibraryIntent.OnToggleWatchingNow -> onToggleWatchingNow(intent.key)
            is LibraryIntent.OnSingleDeleteRequest -> updateState {
                it.copy(pendingSingleDelete = intent.key)
            }
            LibraryIntent.OnSingleDeleteConfirm -> onSingleDeleteConfirm()
            LibraryIntent.OnSingleDeleteDismiss -> updateState {
                it.copy(pendingSingleDelete = null)
            }
        }
    }

    private fun startObserving() {
        if (observeJob?.isActive == true) return
        observeJob = observeLibrary()
            .onEach { items ->
                allItems = items
                updateState {
                    it.copy(
                        isLoading = false,
                        items = applyView(items, it.searchQuery, it.sort, it.sortDirection, it.randomSeed, it.filter, it.category, it.downloadedOnly),
                    )
                }
            }
            .catch { t ->
                updateState { it.copy(isLoading = false) }
                emit(LibraryEffect.ShowError(AppError.Storage.Io(cause = t)))
            }
            .launchIn(viewModelScope)
    }

    private suspend fun onItemClick(manga: Manga) {
        val current = state.value
        if (current.isInSelectionMode) {
            handle(LibraryIntent.OnSelectionToggle(manga.key()))
            return
        }
        FlowLog.log("Library", "openManga", "title=${manga.title} api=${manga.api} lang=${manga.language}")
        emit(LibraryEffect.NavigateToDetails(manga))
    }

    private fun onItemLongClick(key: MangaKey) {
        updateState {
            val next = if (key in it.selection) it.selection - key else it.selection + key
            it.copy(selection = next, isInSelectionMode = next.isNotEmpty())
        }
    }

    private fun onSelectionToggle(key: MangaKey) {
        updateState {
            val next = if (key in it.selection) it.selection - key else it.selection + key
            it.copy(selection = next, isInSelectionMode = next.isNotEmpty())
        }
    }

    private fun onDeleteSelectedRequest() {
        if (state.value.selection.isEmpty()) return
        updateState { it.copy(isDeleteDialogVisible = true) }
    }

    private suspend fun onDeleteSelectedConfirm() {
        val keys = state.value.selection.toList()
        if (keys.isEmpty()) {
            updateState { it.copy(isDeleteDialogVisible = false) }
            return
        }
        updateState {
            it.copy(
                selection = emptySet(),
                isInSelectionMode = false,
                isDeleteDialogVisible = false,
            )
        }
        bulkRemoveFromLibrary(keys)
            .onSuccess { count -> emit(LibraryEffect.ShowBulkRemoveSuccess(count = count)) }
            .onFailure { emit(LibraryEffect.ShowError(it)) }
    }

    private fun onSearchQueryChange(query: String) {
        updateState {
            it.copy(
                searchQuery = query,
                items = applyView(allItems, query, it.sort, it.sortDirection, it.randomSeed, it.filter, it.category, it.downloadedOnly),
            )
        }
    }

    private suspend fun onOpenRandom() {
        val pick = state.value.items.randomOrNull() ?: return
        emit(LibraryEffect.NavigateToDetails(pick.manga))
    }

    /**
     * Pull-to-refresh handler. Native parity: refreshing an empty library is a no-op — it shows a
     * "no manga yet" message and never enqueues the background refresh worker. Guard on the
     * DISPLAYED list ([LibraryState.items], post-filter) to match native exactly — native's
     * `LibraryRoute` checks the rendered `uiState.items`, so an active filter that hides every row
     * counts as "empty" there too.
     */
    private suspend fun onRefresh() {
        if (state.value.items.isEmpty()) {
            emit(LibraryEffect.ShowEmptyLibraryRefresh)
            return
        }
        refreshLibrary()
    }

    private fun onSortChange(sort: LibrarySort) {
        if (sort == LibrarySort.RANDOM) {
            // Fresh shuffle: generate AND PERSIST a new seed so the order stays stable thereafter
            // (across re-emissions + restarts), matching native's KEY_SEED. The observeLibraryRandomSeed
            // collector echoes the persisted value back into state; we also apply it synchronously here
            // so the reshuffle is immediate.
            val freshSeed = Clock.System.now().toEpochMilliseconds()
            updateState {
                it.copy(
                    sort = sort,
                    randomSeed = freshSeed,
                    items = applyView(allItems, it.searchQuery, sort, it.sortDirection, freshSeed, it.filter, it.category, it.downloadedOnly),
                )
            }
            launchSafely {
                setLibrarySort(sort)
                setLibraryRandomSeed(freshSeed)
            }
        } else {
            updateState {
                it.copy(
                    sort = sort,
                    items = applyView(allItems, it.searchQuery, sort, it.sortDirection, it.randomSeed, it.filter, it.category, it.downloadedOnly),
                )
            }
            launchSafely { setLibrarySort(sort) }
        }
    }

    private fun onSortDirectionToggle() {
        val nextDir = when (state.value.sortDirection) {
            SortDirection.ASCENDING -> SortDirection.DESCENDING
            SortDirection.DESCENDING -> SortDirection.ASCENDING
        }
        updateState {
            it.copy(
                sortDirection = nextDir,
                items = applyView(allItems, it.searchQuery, it.sort, nextDir, it.randomSeed, it.filter, it.category, it.downloadedOnly),
            )
        }
        launchSafely { setLibrarySortDirection(nextDir) }
    }

    private fun onFilterChange(filter: LibraryFilter) {
        updateState {
            it.copy(
                filter = filter,
                items = applyView(allItems, it.searchQuery, it.sort, it.sortDirection, it.randomSeed, filter, it.category, it.downloadedOnly),
            )
        }
        launchSafely { setLibraryFilter(filter) }
    }

    /**
     * Apply a new [LibraryCategory] choice. Synchronous local state.copy first so the grid
     * recomposes immediately with the new tab selection; the persist call is launched on
     * `viewModelScope` and the `observeLibraryCategory` flow in `init {}` re-emits the new value,
     * but `StateFlow`'s distinct-emission guard collapses the echo to a no-op recomposition.
     * Same observer-echo posture as `onFilterChange` / `onSortChange` / `onGridDensityChange`
     * — §154 / §157 wire shape extended by one axis (Task #325, §158.persist).
     *
     * Category narrows BEFORE filter in `applyView` — see [applyView] KDoc for the pipeline
     * ordering rationale (per-manga affinity first → per-chapter status next → comparator last).
     */
    private fun onCategoryChange(category: LibraryCategory) {
        updateState {
            it.copy(
                category = category,
                items = applyView(allItems, it.searchQuery, it.sort, it.sortDirection, it.randomSeed, it.filter, category, it.downloadedOnly),
            )
        }
        launchSafely { setLibraryCategory(category) }
    }

    /**
     * Apply a new [GridDensity] choice. Pure state update — no `applyView` re-run, because
     * density only changes how the same `items` list is laid out on screen (the `:ui` adaptive
     * grid recomposes when `state.gridDensity` flips, since the cell's `minSize` parameter is
     * derived from it).
     *
     * The local state update happens synchronously so the grid recomposes immediately; the
     * persist call is launched on `viewModelScope` and the `observeLibraryGridDensity` flow in
     * `init {}` re-emits the new value, but `StateFlow`'s distinct-emission guard collapses the
     * echo to a no-op recomposition. Same observer-echo posture as `onFilterChange` /
     * `onSortChange` / `onSortDirectionToggle` — §154 wire shape extended by one axis.
     */
    private fun onGridDensityChange(density: GridDensity) {
        updateState { it.copy(gridDensity = density) }
        launchSafely { setLibraryGridDensity(density) }
    }

    /**
     * Apply a new items-per-row value (Library parity fix). Synchronous local state update first —
     * no `applyView` re-run, because the column count only changes how the same `items` list is
     * laid out (the `:ui` grid recomposes when `state.itemsPerRow` flips, since its `GridCells`
     * selection is derived from it). Same layout-only posture as [onGridDensityChange].
     *
     * Mirrors native `LibraryViewModel.onItemsPerRowChange(count)` — `count` is the raw slider
     * position in `0..8` (`0 = Auto`). Coerced into `0..8` defensively. The local state update
     * happens synchronously so the grid recomposes immediately; the persist call is launched on
     * `viewModelScope` (writing the native `library_items_per_row` Int disk cell via
     * [setLibraryItemsPerRow]) and the `observeLibraryItemsPerRow` flow in `init {}` re-emits the
     * new value, but `StateFlow`'s distinct-emission guard collapses the echo to a no-op
     * recomposition. Same observer-echo posture as [onGridDensityChange].
     */
    private fun onItemsPerRowChange(count: Int) {
        val coerced = count.coerceIn(0, 8)
        updateState { it.copy(itemsPerRow = coerced) }
        launchSafely { setLibraryItemsPerRow(coerced) }
    }

    /**
     * Apply a new "show source" display-toggle value. Synchronous local state.copy first so the
     * `:ui` cardsource caption recomposes immediately (it gates on `state.display.showSource`
     * per §162); the persist call is launched on `viewModelScope` and the
     * `observeLibraryDisplay()` flow in `init {}` re-emits the new bundled snapshot, but
     * `StateFlow`'s distinct-emission guard collapses the echo to a no-op recomposition. Same
     * observer-echo posture as `onGridDensityChange` / `onCategoryChange` / `onFilterChange`.
     *
     * No `applyView` re-run — toggle flips only change which `:ui` surfaces are visible, not
     * which items appear or in what order.
     *
     * §150 rung 16b (showSource end-to-end). The remaining four toggles (showCount → showDetails
     * → showButtons → showTabs) get parallel reducer helpers in rungs 16c-16f, each mirroring
     * this same shape with the matching `setLibraryShow*` setter.
     */
    private fun onToggleShowSource(value: Boolean) {
        updateState { it.copy(display = it.display.copy(showSource = value)) }
        launchSafely { setLibraryShowSource(value) }
    }

    /**
     * Handler for [LibraryIntent.OnToggleShowCount]. Mirrors [onToggleShowSource] exactly:
     * synchronous state copy (so the §163 carddownloaded badge + §164 cardbookmarks caption
     * gates recompose immediately on the next frame once 16c2 lands) followed by an async
     * persist on `viewModelScope`. The bundled `observeLibraryDisplay()` collector echoes the
     * change back as an idempotent state update which `StateFlow` collapses.
     *
     * No `applyView` re-run — toggle flips only change which `:ui` surfaces are visible.
     *
     * §150 rung 16c (showCount end-to-end). One flag gates two captions because the legacy
     * `DisplayOptionsSection` bundles "downloaded" + "bookmarks" under a single "Count"
     * switch — see [LibraryIntent.OnToggleShowCount] KDoc.
     */
    private fun onToggleShowCount(value: Boolean) {
        updateState { it.copy(display = it.display.copy(showCount = value)) }
        launchSafely { setLibraryShowCount(value) }
    }

    /**
     * Handler for [LibraryIntent.OnToggleShowDetails]. Mirrors [onToggleShowCount] /
     * [onToggleShowSource] exactly: synchronous state copy (so the §165 cardlastread caption +
     * §166 cardprogress caption gates recompose immediately on the next frame once 16d2 lands)
     * followed by an async persist on `viewModelScope`. The bundled `observeLibraryDisplay()`
     * collector echoes the change back as an idempotent state update which `StateFlow` collapses.
     *
     * No `applyView` re-run — toggle flips only change which `:ui` surfaces are visible.
     *
     * §150 rung 16d (showDetails end-to-end). One flag gates two captions because the legacy
     * `DisplayOptionsSection` bundles "last-read" + "progress" under a single "Details" switch —
     * see [LibraryIntent.OnToggleShowDetails] KDoc. Same "two-captions-per-flag" pattern as
     * rung 16c (`showCount` gates §163 + §164).
     */
    private fun onToggleShowDetails(value: Boolean) {
        updateState { it.copy(display = it.display.copy(showDetails = value)) }
        launchSafely { setLibraryShowDetails(value) }
    }

    /**
     * Handler for [LibraryIntent.OnToggleShowButtons]. Mirrors [onToggleShowDetails] /
     * [onToggleShowCount] / [onToggleShowSource] exactly: synchronous state copy followed by
     * an async persist on `viewModelScope`. The bundled `observeLibraryDisplay()` collector
     * echoes the change back as an idempotent state update which `StateFlow` collapses.
     *
     * No `:ui` gate yet — the legacy MangaCard bottom action row hasn't been ported to the
     * rework. The VM-side write path still lands so legacy display-sheet flips propagate
     * through the shared `library_show_buttons` disk cell. When a future slice ports the
     * action row, gating it on `state.display.showButtons` will be a 1-file `:ui` follow-on
     * (analogous to rung 16b2 / 16c2 / 16d2) — but lifted after the action-row port itself.
     *
     * No `applyView` re-run — toggle flips only change which `:ui` surfaces are visible.
     *
     * §150 rung 16e (showButtons VM-side wiring). Single sub-rung — no `:ui` gate follow-on
     * needed today.
     */
    private fun onToggleShowButtons(value: Boolean) {
        updateState { it.copy(display = it.display.copy(showButtons = value)) }
        launchSafely { setLibraryShowButtons(value) }
    }

    /**
     * Handler for [LibraryIntent.OnToggleShowTabs]. Mirrors [onToggleShowButtons] /
     * [onToggleShowDetails] / [onToggleShowCount] / [onToggleShowSource] exactly: synchronous
     * state copy followed by an async persist on `viewModelScope`. The bundled
     * `observeLibraryDisplay()` collector echoes the change back as an idempotent state update
     * which `StateFlow` collapses.
     *
     * No `applyView` re-run: hiding / showing the category tab row doesn't change which library
     * items match the current category filter — `state.category` is untouched here, and the
     * grid keeps filtering by it regardless of whether the tabs row is rendered. When the user
     * toggles `showTabs` back on, the previously-selected category resurfaces (same posture as
     * the legacy `library_show_tabs` flag).
     *
     * §150 rung 16f (showTabs end-to-end — closes the 5/5 per-flag vertical ladder). The `:ui`
     * gate (`if (state.display.showTabs) { CategoryTabs(...) }`) lands in the SAME slice as
     * this VM wiring because the gate is one screen-level `if` (not per-card), fitting inside
     * the ≤5-file commit cap.
     */
    private fun onToggleShowTabs(value: Boolean) {
        updateState { it.copy(display = it.display.copy(showTabs = value)) }
        launchSafely { setLibraryShowTabs(value) }
    }

    /**
     * Handler for [LibraryIntent.OnToggleLike]. Thin pass-through to
     * [ToggleMangaLikedUseCase] — no synchronous local state copy because the like flag lives
     * on the persisted `SavedMangaEntity` row (not on `state.display` like the showSource /
     * showCount / … flags). The `observeLibrary()` flow in [startObserving] re-emits the
     * updated `LibraryManga` list on every legacy DAO write, so the `:ui` heart icon fills in
     * naturally on the next frame.
     *
     * Failure surfaces through [LibraryEffect.ShowError]; success is silent (the flow
     * re-emit covers it, same posture as [onToggleInLibrary]). The use case itself returns
     * success even when the manga is not in the library (defensive no-op — see the use case
     * KDoc), so we don't gate on a membership check here.
     *
     * §179 rung 19 (Task #345).
     */
    private suspend fun onToggleLike(key: MangaKey) {
        toggleMangaLiked(key).onFailure { emit(LibraryEffect.ShowError(it)) }
    }

    /**
     * Handler for [LibraryIntent.OnToggleWatchingNow]. Same shape as [onToggleLike] — see
     * that handler's KDoc for the flow-re-emit / failure-surfacing narrative.
     *
     * §179 rung 19 (Task #345).
     */
    private suspend fun onToggleWatchingNow(key: MangaKey) {
        toggleMangaWatchingNow(key).onFailure { emit(LibraryEffect.ShowError(it)) }
    }

    /**
     * Handler for [LibraryIntent.OnSingleDeleteConfirm] (GAP-LIB-15). Reads the staged
     * [LibraryState.pendingSingleDelete] key, clears it, and routes through the same
     * [bulkRemoveFromLibrary] path as the bulk-delete (a 1-element list — reusing the existing
     * write path means the strangler-fig boundary retires once, not twice). No-op (just clears the
     * dialog) if nothing is pending — defends against a double-confirm race.
     */
    private suspend fun onSingleDeleteConfirm() {
        val key = state.value.pendingSingleDelete
        updateState { it.copy(pendingSingleDelete = null) }
        if (key == null) return
        bulkRemoveFromLibrary(listOf(key))
            .onSuccess { count -> emit(LibraryEffect.ShowBulkRemoveSuccess(count = count)) }
            .onFailure { emit(LibraryEffect.ShowError(it)) }
    }

    /**
     * Apply the full view pipeline: search → category → filter → sort → optional reverse.
     *
     * Matches the legacy `LibraryViewModel.kt:204-248` pipeline shape — narrowing steps first
     * (smaller working set to sort), then comparator, then a single `asReversed()` for
     * descending. RANDOM short-circuits the reverse step (a shuffle's "direction" has no
     * meaning); see [LibrarySort.RANDOM] KDoc.
     *
     * Pipeline ordering rationale: category narrows by per-manga affinity (heart / watching-now
     * flags) BEFORE filter narrows by per-chapter status — the two axes are orthogonal
     * (see [LibraryCategory] KDoc) and either order produces the same final set, but running
     * category first keeps the filter predicate's working set smaller. Search runs first so
     * the user's typed query takes precedence over any tab/filter narrowing.
     *
     * Pure, deterministic given the same inputs (including [seed]) — safe to call from any
     * reducer without dispatcher pinning. Lives as a private helper rather than a top-level
     * extension because it threads five pieces of state together (filter query + sort mode +
     * direction + seed + filter + category) and that bundle is meaningless outside this VM.
     */
    private fun applyView(
        items: List<LibraryManga>,
        query: String,
        sort: LibrarySort,
        direction: SortDirection,
        seed: Long?,
        filter: LibraryFilter,
        category: LibraryCategory,
        downloadedOnly: Boolean,
    ): List<LibraryManga> {
        val searched = if (query.isBlank()) {
            items
        } else {
            val needle = query.trim()
            items.filter { it.manga.title.contains(needle, ignoreCase = true) }
        }
        val categorized = when (category) {
            LibraryCategory.NAN -> searched
            LibraryCategory.LIKED -> searched.filter { it.isLiked }
            LibraryCategory.WATCHING_NOW -> searched.filter { it.isWatchingNow }
        }
        // Native parity: the global Settings "Downloaded only" toggle ("Filters all entries in your
        // library") OVERRIDES the selected filter chip — when on, the library is constrained to manga
        // with ≥1 downloaded chapter regardless of the chosen FilterType.
        val filtered = if (downloadedOnly) {
            categorized.filter { it.hasDownloads }
        } else when (filter) {
            LibraryFilter.ALL -> categorized
            LibraryFilter.DOWNLOADED -> categorized.filter { it.hasDownloads }
            LibraryFilter.UNREAD -> categorized.filter { it.unreadCount > 0 }
            LibraryFilter.STARTED -> categorized.filter { it.unreadCount < it.totalChapters }
            LibraryFilter.COMPLETED -> categorized.filter { it.totalChapters > 0 && it.unreadCount == 0 }
            LibraryFilter.BOOKMARKED -> categorized.filter { it.bookmarkedCount > 0 }
        }
        val sorted = when (sort) {
            LibrarySort.ALPHABETIC -> filtered.sortedBy { it.manga.title.lowercase() }
            LibrarySort.DATE_ADDED -> filtered.sortedBy { it.addedAt }
            LibrarySort.UNREAD_COUNT -> filtered.sortedBy { it.unreadCount }
            LibrarySort.TOTAL_CHAPTERS -> filtered.sortedBy { it.totalChapters }
            // Native parity: LAST_READ orders by the manga's last-OPEN time (lastOpenedAt, always
            // set + bumped on each Details open), not by chapter read dates. Mirrors native sorting
            // by `manga.lastOpenTimestamp`.
            LibrarySort.LAST_READ -> filtered.sortedBy { it.lastOpenedAt.toEpochMilliseconds() }
            LibrarySort.RANDOM -> if (seed != null) {
                filtered.shuffled(Random(seed))
            } else {
                filtered.shuffled()
            }
        }
        return if (sort == LibrarySort.RANDOM || direction == SortDirection.ASCENDING) {
            sorted
        } else {
            sorted.asReversed()
        }
    }
}

private fun Manga.key(): MangaKey = MangaKey(api = api, language = language, title = title)
