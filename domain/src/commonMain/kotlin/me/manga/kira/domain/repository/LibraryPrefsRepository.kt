package me.manga.kira.domain.repository

import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.library.GridDensity
import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.domain.model.library.LibraryDisplay
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.model.library.SortDirection

/**
 * User library-display preference store.
 *
 * Contract §6 SRP: owns ONE rule — "expose the user's current Library sort + direction choices as
 * live streams and accept new choices to persist". Where the values live on disk, what string
 * format they take, and how they're bridged to the platform key-value store are all `:data`
 * concerns.
 *
 * Scope today: sort mode + sort direction + filter axis + grid density + category tab + last-
 * updated timestamp (read-only). Future Library prefs (cover aspect, layout style) extend this
 * same interface rather than introduce a parallel `LibraryFilterRepository` — they share a
 * coherent "Library display preferences" SRP and the same disk-cell concentration is preferable
 * to a sprawl of micro-repositories. The filter axis landed in the §153.persist follow-on
 * (Task #320), the grid-density axis in §156.persist (Task #323), the category-tab axis in
 * §158.persist (Task #325), and the last-updated observer in §160.lastupdated (Task #326); each
 * new axis mirrors the sort + direction pair's observe/set shape exactly (the last-updated axis
 * gained a setLastUpdated writer once the cross-platform inline refresh landed, so every platform
 * records refresh completion — the Android-only legacy worker is no longer the sole writer).
 *
 * Why two flows + two setters rather than a single `LibraryPrefs` snapshot:
 *  - The VM consumes sort and direction at independent intent boundaries
 *    (`LibraryIntent.OnSortChange`, `LibraryIntent.OnSortDirectionToggle`). Separate streams keep
 *    each intent's handler narrow — it touches exactly the one cell it owns.
 *  - A combined snapshot would force every consumer to recompose on the OTHER axis changing too,
 *    even when its own axis is stable. The granular split is `kotlinx.coroutines.flow`-friendly.
 *  - Future extension (filter, category) adds new observe/set pairs alongside, not new fields on
 *    a single snapshot ADT. Same OCP posture as [ReadingModeRepository].
 *
 * Why a [Flow] rather than `suspend fun getSort(): LibrarySort`:
 *  - The Library screen is long-lived and the preference can change from a future Settings hub
 *    (e.g. "reset sort to Date added on app start" preference). A live Flow lets the VM react to
 *    those out-of-band changes without a manual re-fetch on every screen entry.
 *  - Compose state-hoisting maps cleanly onto `StateFlow`-fed observation: the VM lifts each
 *    emission into [me.manga.kira.presentation.library.LibraryState] via the existing
 *    `LibraryIntent.OnSortChange` handler.
 *
 * Why suspending setters: parity with [ReadingModeRepository.set]. The underlying
 * multiplatform-settings write is non-blocking today, but keeping the setter `suspend` reserves
 * the option to swap the backend to a `withContext(io)` impl in a future expansion (e.g. if the
 * settings backend grows synchronous I/O on Desktop or moves to encrypted storage).
 *
 * Unknown / malformed persisted values: when disk holds a string that doesn't map to any
 * [LibrarySort] or [SortDirection] entry (corrupt write, future enum entry that disappeared on
 * downgrade), the impl falls back to the canonical default — [LibrarySort.ALPHABETIC] for sort
 * and [SortDirection.ASCENDING] for direction. Same posture as the legacy
 * `DataStoreHelper.librarySortFlow` would have crashed via `valueOf`; the rework's mapper swaps
 * the crash for a deterministic fallback that keeps the Library usable.
 *
 * DIP (contract §6): consumers (`ObserveLibrarySortUseCase`, `SetLibrarySortUseCase`,
 * `ObserveLibrarySortDirectionUseCase`, `SetLibrarySortDirectionUseCase`, and through them the
 * Library VM) depend on this interface, never on the `:platform` `SettingsFactory` or the raw
 * multiplatform-settings `ObservableSettings`. Koin binds the impl at the composition root.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster26.staleKdocSweep.cascade,
 * Task #482, 2026-05-28): one fulfilled-forecast citation appears in
 * the `observeDisplay()` member KDoc below:
 *  - Lines 213-215 ("Phase 9.x route-swap will eventually retire the
 *    legacy `LibraryViewModel` entirely; at that point the keys
 *    belong to the rework alone"). PARTIALLY-FULFILLED-INVERSION —
 *    Phase 9.x.library.swap (§346) re-pointed `Screen.Library`'s
 *    rendering adapter to the rework `LibraryScreen`; Phase
 *    9.x.library.retire (§347 sweep, "delete unreachable legacy
 *    Library screen + parallel debug route") deleted the orphan
 *    legacy `:shared` LibraryScreen + the parallel `Screen.
 *    LibraryRework` debug route; Phase 9.x.library.deadcomposable.
 *    retire (§348) dropped the dead AnimatedPreloader composable.
 *    The legacy `LibraryViewModel.kt` shell + its KEY_SHOW_* /
 *    KEY_SORT_* constants are retired across §§346 + 347 (the legacy
 *    VM at the §253-era L147-176 referenced no longer holds those
 *    constants — verified by file scan). HOWEVER — the underlying
 *    `library_show_*` / `library_sort` / `library_sort_asc` /
 *    `library_filter` / `library_grid_density` / `library_category`
 *    / `library_last_updated` disk keys STILL EXIST as the wire-
 *    format-compat byte-for-byte cell of truth for users' persisted
 *    Library display + sort + filter choices that survive the
 *    strangler-fig transition (per `LibraryPrefsRepositoryImpl`'s
 *    cluster25 / §481 audit-trail postscripts covering both the
 *    class-level KDoc and the companion-level constants). The "keys
 *    belong to the rework alone" branch has effectively resolved —
 *    the legacy LibraryViewModel readers/writers no longer exist, but
 *    the keys remain the persistence backbone for the rework path.
 *    Mirror of `LibraryPrefsRepositoryImpl`'s §481 cluster25
 *    precedent. The SRP / two-flows-rationale / Flow-vs-suspend /
 *    suspending-setters / malformed-fallback / DIP sub-sections all
 *    stand on their own merits past the §§346 + 347 fulfilled
 *    landings. The LibraryPrefsRepository interface remains LIVE as
 *    the canonical rework Library-display preference surface.
 *    Original §253-era prose preserved verbatim per the audit-trail-
 *    preservation convention — the citation is historical record of
 *    the design lineage including the deferred-route-swap forecast
 *    that was subsequently fulfilled across §§346 + 347.
 */
interface LibraryPrefsRepository {

    /**
     * Hot stream of the user's current Library sort mode. Emits the persisted value (or
     * [LibrarySort.ALPHABETIC] when nothing has been persisted yet) and re-emits on every change.
     *
     * Coroutine context: the `:data` impl is backed by `ObservableSettings.getStringFlow` whose
     * emissions are not dispatcher-pinned; callers that need a specific dispatcher should apply
     * their own `.flowOn(io)`. The VM consumes this on `viewModelScope` (main-thread-equivalent)
     * which is correct for state updates.
     */
    fun observeSort(): Flow<LibrarySort>

    /** Persist [sort] as the user's current choice. */
    suspend fun setSort(sort: LibrarySort)

    /**
     * Hot stream of the persisted RANDOM-sort shuffle seed. Emits the stored seed (or the native
     * default `64464L` when nothing has been persisted yet) and re-emits on change.
     *
     * The seed makes [LibrarySort.RANDOM] a STABLE shuffle: the same seed produces the same order
     * across library re-emissions (a like-toggle, refresh, add/remove) and across app restarts, so
     * the grid doesn't reshuffle itself on every recomposition — matching native, which persists
     * the seed under its `KEY_SEED` cell (default `64464L`). Picking RANDOM again writes a fresh
     * seed via [setRandomSeed] to give the user a new shuffle.
     *
     * Wire-format note: the disk-key is `library_random_seed` (Long), the rework's own cell (the
     * native key name differs but the default value matches). Same shared-store posture as the
     * other Library prefs.
     */
    fun observeRandomSeed(): Flow<Long>

    /** Persist [seed] as the current RANDOM-sort shuffle seed. */
    suspend fun setRandomSeed(seed: Long)

    /**
     * Hot stream of the user's current Library sort direction. Emits the persisted value (or
     * [SortDirection.ASCENDING] when nothing has been persisted yet) and re-emits on every change.
     */
    fun observeSortDirection(): Flow<SortDirection>

    /** Persist [direction] as the user's current choice. */
    suspend fun setSortDirection(direction: SortDirection)

    /**
     * Hot stream of the user's current Library filter axis. Emits the persisted value (or
     * [LibraryFilter.ALL] when nothing has been persisted yet) and re-emits on every change.
     *
     * Unknown / malformed disk values fall back to [LibraryFilter.ALL] (the canonical default —
     * matches the §153 foundation slice's `LibraryState.filter` default). Same posture as the
     * sort + direction observers — a future downgrade past an added axis (e.g. tier-b
     * `BOOKMARKED`) won't crash the Library; the fallback never writes back, so the next
     * explicit user pick will overwrite the bad value normally.
     *
     * Wire-format note: the legacy `LibraryViewModel` did NOT persist its `FilterTypes` axis —
     * the chip row reset to ALL on every screen entry. The rework is the FIRST persistence
     * writer for this axis, so the disk-key is new (`library_filter`, namespaced parallel to
     * `library_sort` / `library_sort_asc`) and no cross-route compatibility concern applies.
     * Phase 9.x route-swap will leave this writer as the sole owner.
     */
    fun observeFilter(): Flow<LibraryFilter>

    /** Persist [filter] as the user's current choice. */
    suspend fun setFilter(filter: LibraryFilter)

    /**
     * Hot stream of the user's current Library grid density. Emits the persisted value (or
     * [GridDensity.COMFORTABLE] when nothing has been persisted yet) and re-emits on every change.
     *
     * Unknown / malformed disk values fall back to [GridDensity.COMFORTABLE] (the canonical
     * default — matches the §156 foundation slice's `LibraryState.gridDensity` default and
     * preserves the pre-§156 hardcoded 120.dp cell size byte-for-byte). Same posture as the
     * sort + direction + filter observers — a future downgrade past an added tier (e.g. an
     * even-larger `GIANT` density) won't crash the Library; the fallback never writes back, so
     * the next explicit user pick will overwrite the bad value normally.
     *
     * Wire-format note: the legacy `LibraryViewModel` / `LibraryScreen` does NOT expose a
     * grid-density preference (the legacy uses a fixed 3-column grid). The rework is the FIRST
     * persistence writer for this axis, so the disk-key is new (`library_grid_density`,
     * namespaced parallel to `library_sort` / `library_sort_asc` / `library_filter`) and no
     * cross-route compatibility concern applies. Phase 9.x route-swap will leave this writer as
     * the sole owner.
     */
    fun observeGridDensity(): Flow<GridDensity>

    /** Persist [density] as the user's current choice. */
    suspend fun setGridDensity(density: GridDensity)

    /**
     * Hot stream of the user's current Library items-per-row (grid column count). Emits the
     * persisted value (or `0` — "Auto" — when nothing has been persisted yet) and re-emits on
     * every change.
     *
     * Value semantics mirror native: `0` means "Auto" (the `:ui` grid falls back to an adaptive
     * cell `minSize`), and `1..8` pin the grid to exactly that many fixed columns. The default of
     * `0` matches native's `prefs.getInt("library_items_per_row", 0)` read in the native
     * `LibraryViewModel` `init {}`.
     *
     * Wire-format note: the disk-key is `library_items_per_row` (Int), identical to native's
     * `LibraryViewModel.KEY_ITEMS_PER_ROW`. Same shared-cell posture as the sort + direction +
     * filter + grid-density + category keys — preserves the user's column-count choice
     * byte-for-byte across the native ⇄ KMP transition. This is the FIRST KMP persistence writer
     * for the axis (the rework had previously exposed it only as a transient ViewModel-lifetime
     * field), so the disk-key matches native's exactly to keep the value portable.
     */
    fun observeItemsPerRow(): Flow<Int>

    /** Persist [itemsPerRow] as the user's current choice (0 = Auto, 1..8 = fixed columns). */
    suspend fun setItemsPerRow(itemsPerRow: Int)

    /**
     * Hot stream of the user's current Library category tab. Emits the persisted value (or
     * [LibraryCategory.NAN] when nothing has been persisted yet) and re-emits on every change.
     *
     * Unknown / malformed disk values fall back to [LibraryCategory.NAN] (the canonical default —
     * matches the §158 foundation slice's `LibraryState.category` default and the legacy
     * `FilterTabs` "All" tab). Same posture as the sort + direction + filter + density observers
     * — a future downgrade past an added category (e.g. a new `RECENT_PICKS` tab) won't crash the
     * Library; the fallback never writes back, so the next explicit user pick will overwrite the
     * bad value normally.
     *
     * Wire-format note: the legacy `LibraryViewModel` did NOT persist its `FilterTabs` axis —
     * the tab row reset to NAN/"All" on every screen entry. The rework is the FIRST persistence
     * writer for this axis, so the disk-key is new (`library_category`, namespaced parallel to
     * `library_sort` / `library_sort_asc` / `library_filter` / `library_grid_density`) and no
     * cross-route compatibility concern applies. Phase 9.x route-swap will leave this writer as
     * the sole owner.
     */
    fun observeCategory(): Flow<LibraryCategory>

    /** Persist [category] as the user's current choice. */
    suspend fun setCategory(category: LibraryCategory)

    /**
     * Hot stream of the timestamp at which the library was last refreshed end-to-end. Emits
     * `null` when nothing has ever been persisted (e.g. first-run user before any refresh) and
     * re-emits whenever a refresh persists a new value via [setLastUpdated].
     *
     * Wire-format note: the on-disk value is a `kotlinx.datetime.LocalDateTime.toString()`
     * ISO-8601 string (zone-less local-time, e.g. `"2026-05-27T14:30:00"`) — round-trippable with
     * the legacy `java.time.LocalDateTime.toString()` format the original APK persisted. The
     * `:data` impl parses the string, converts it through the current system [TimeZone] to a
     * universal-time [Instant], and emits that. Malformed or empty disk values map to `null`
     * (same fallback shape as the legacy `String::isNotBlank.takeIf` guard in `LibraryViewModel`).
     */
    fun observeLastUpdated(): Flow<Instant?>

    /**
     * Persist [timestamp] as the moment the library was last refreshed end-to-end.
     *
     * Written by every refresh path so the "Last updated" header is correct on all platforms:
     * the Android-only legacy `LibraryRefreshWorker` already writes the same `library_last_updated`
     * cell, and the cross-platform inline `LibraryRefreshRepositoryImpl` (iOS / Desktop, where the
     * worker doesn't exist) calls this setter on successful completion so its refresh no longer
     * leaves the header stuck on "Not updated yet". Both writers use the same
     * `LocalDateTime.toString()` wire format [observeLastUpdated] parses, so the cell stays
     * byte-for-byte compatible across legacy + rework routes.
     */
    suspend fun setLastUpdated(timestamp: Instant)

    /**
     * Hot stream of the user's current Library display-toggle bundle (the five `show*` boolean
     * flags that gate optional surfaces on the Library screen). Emits the persisted snapshot (or
     * the all-`true` default — see [LibraryDisplay] KDoc for the per-flag rationale) and re-emits
     * whenever any one of the five backing cells changes.
     *
     * Why a single bundled flow rather than five independent `observeShow*()` methods:
     *  - The VM consumes all five flags into a single nested `state.display` field (see
     *    `LibraryState.display`), so a bundled flow lets the VM update state once per coalesced
     *    snapshot. Five independent flows would force five `init {}` collectors and five
     *    one-line reducer cases for the same conceptual fan-out. The §232 `ReadingStatistics`
     *    bundle slice established the same posture for an eight-flag display read.
     *  - Setters remain per-flag (see the five [setShowSource]-style methods below) because
     *    each toggle flip is its own user intent. Symmetry break is deliberate — the
     *    `kotlinx.coroutines.flow.combine` in `:data` makes the read side cheap, but a bundled
     *    setter (e.g. `setDisplay(d: LibraryDisplay)`) would force every per-toggle intent to
     *    construct a full bundle just to flip one bit. That's busywork the rework's intent
     *    pipeline doesn't owe.
     *
     * Unknown / malformed disk values: each underlying `getBooleanFlow` returns the default
     * (`true`) on missing keys or type mismatches — same fallback shape as the legacy
     * `prefs.getBoolean(key, true)` reads at `LibraryViewModel.kt:172-176`. No explicit guard
     * in the `:data` impl.
     *
     * Wire-format compatibility: the `:data` impl reuses the legacy `library_show_*` disk
     * keys byte-for-byte so the user's persisted choices survive the strangler-fig transition
     * (same posture as the sort + direction + filter + density + category cells — see
     * `LibraryPrefsRepositoryImpl`'s "wire-format compatibility" KDoc). Phase 9.x route-swap
     * will eventually retire the legacy `LibraryViewModel` entirely; at that point the keys
     * belong to the rework alone.
     *
     * §150 ladder rung 16 (display-toggle persistence foundation).
     */
    fun observeDisplay(): Flow<LibraryDisplay>

    /** Persist [showSource] as the user's current choice. */
    suspend fun setShowSource(showSource: Boolean)

    /** Persist [showCount] as the user's current choice. */
    suspend fun setShowCount(showCount: Boolean)

    /** Persist [showDetails] as the user's current choice. */
    suspend fun setShowDetails(showDetails: Boolean)

    /** Persist [showButtons] as the user's current choice. */
    suspend fun setShowButtons(showButtons: Boolean)

    /** Persist [showTabs] as the user's current choice. */
    suspend fun setShowTabs(showTabs: Boolean)
}
