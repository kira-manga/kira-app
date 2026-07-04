package me.manga.kira.data.repository

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.getBooleanFlow
import com.russhwolf.settings.coroutines.getIntFlow
import com.russhwolf.settings.coroutines.getLongFlow
import com.russhwolf.settings.coroutines.getStringFlow
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import me.manga.kira.domain.model.library.GridDensity
import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.domain.model.library.LibraryDisplay
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.model.library.SortDirection
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * [LibraryPrefsRepository] backed by the platform's [ObservableSettings] store.
 *
 * SRP (contract §6): owns ONE rule — "translate between the on-disk representation of the
 * Library sort + direction preferences and the typed [LibrarySort] / [SortDirection] enums,
 * exposing the live stream + setter surface that [LibraryPrefsRepository] declares".
 *
 * DIP: depends on [LibraryPrefsRepository] (`:domain`) and [ObservableSettings] (multiplatform-
 * settings, already bound as a `single` by the platform modules via
 * `SettingsFactory.createObservable("kira_settings")`). The rework reuses that binding — same
 * disk cell the legacy `SharedPrefsHelper` reads/writes.
 *
 * Wire-format compatibility (preserves the legacy persisted values byte-for-byte):
 *  - Sort key on disk: literal `"library_sort"` — identical to legacy `LibraryViewModel.KEY_SORT`
 *    at `shared/.../library/ui/viewmodel/LibraryViewModel.kt:155`. Value is the enum `name`,
 *    same as legacy `prefs.getString(KEY_SORT, SortType.ALPHABETIC.name)`. The constants are
 *    duplicated locally as [SORT_KEY] / [SORT_DIRECTION_KEY] rather than imported from
 *    `:shared` because the `:data` layer is forbidden from depending on legacy
 *    presentation-layer helpers (contract §6 DIP; layer boundary). The duplication is
 *    deliberate and load-bearing — if either side drifts, users lose their preference across
 *    the strangler-fig transition. KDoc on both sides documents the invariant.
 *  - Direction key on disk: literal `"library_sort_asc"` — identical to legacy
 *    `LibraryViewModel.KEY_SORT_ASC` at `shared/.../library/ui/viewmodel/LibraryViewModel.kt:153`.
 *    Value is a `Boolean`: `true` → [SortDirection.ASCENDING], `false` → [SortDirection.DESCENDING].
 *    This is the ONE place in the rework's prefs surface where the legacy key is a Boolean
 *    rather than an enum-name string (the legacy modelled direction as a single ascending/
 *    descending flag, not a named enum). The rework's [SortDirection] enum is two values, so
 *    the Boolean ⇄ enum mapping is total and reversible.
 *
 * Why preserve the legacy keys at all (vs. picking fresh `"library_sort.rework"` /
 * `"library_sort.direction.rework"` keys): the user's persisted choice should survive the
 * strangler-fig transition. A fresh key would silently reset every user's sort + direction
 * back to defaults the first time they hit the rework Library screen — worse UX than the
 * shared cell during the transition. Phase 9.x route-swap will eventually retire the legacy
 * `LibraryViewModel` entirely; at that point the keys belong to the rework alone.
 *
 * Unknown / malformed disk values (preserves the [LibraryPrefsRepository] KDoc contract):
 *  - The legacy reader called `SortType.valueOf(stringFromDisk)` which throws on unknown
 *    names — a downgrade after the rework persists a future enum entry (e.g., if a later
 *    slice adds a `BY_LANGUAGE` sort and the user downgrades) would crash. The rework swaps
 *    the crash for a silent fallback to [LibrarySort.ALPHABETIC] via [toLibrarySort] —
 *    `entries.firstOrNull { it.name == raw }` — so corrupt or future-only writes don't break
 *    the Library. The fallback never writes back to disk: the next explicit user pick will
 *    overwrite the bad value normally.
 *  - Boolean reads are total (no unknown-value branch — multiplatform-settings reads return
 *    the default when the key is absent or the type mismatches), so direction has no
 *    equivalent fallback path.
 *
 * Why not [`flowOn(io)`]: `ObservableSettings.getStringFlow` / `getBooleanFlow` emissions are
 * not dispatcher-pinned — the multiplatform-settings impls read from in-memory state
 * (SharedPreferences / NSUserDefaults / java.util.prefs.Preferences are all in-memory
 * snapshots after first read). The `.map` we apply is pure CPU. Pinning to `io` would add a
 * hop with no benefit. Same posture as [ReadingModeRepositoryImpl] and legacy
 * `LibraryViewModel`'s `_uiState.value.copy(...)` (no `flowOn`).
 *
 * Why the setters are `suspend` despite a non-blocking platform write: contract parity with
 * [LibraryPrefsRepository.setSort] / [LibraryPrefsRepository.setSortDirection] KDocs — keeps
 * the door open for a future `withContext(io)` switch (e.g. if a future settings backend
 * grows synchronous I/O on Desktop). Today these are direct `putString` / `putBoolean` calls
 * which return immediately.
 *
 * No-op write protection: a setter that writes the same value the store already holds would
 * not change disk state (`ObservableSettings.putString` / `putBoolean` short-circuit
 * internally when the value is unchanged) and **also wouldn't trigger an emission** —
 * multiplatform-settings 1.3.0's `getStringFlow` / `getBooleanFlow` only re-emit when the
 * underlying value actually changes. No explicit guard needed.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster25.staleKdocSweep.cascade,
 * Task #481, 2026-05-28): one fulfilled-forecast citation appears
 * above (with a sibling occurrence in the companion-object KDoc at
 * L220-221 covered by a separate postscript):
 *  - Lines 55-56 ("Phase 9.x route-swap will eventually retire the
 *    legacy `LibraryViewModel` entirely; at that point the keys
 *    belong to the rework alone"). PARTIALLY-FULFILLED-INVERSION —
 *    Phase 9.x.library.swap (§346) re-pointed `Screen.Library`'s
 *    rendering adapter to the rework `LibraryScreen`; Phase
 *    9.x.library.retire (§347 sweep) deleted the orphan legacy
 *    `:shared` `LibraryScreen.kt` + `LibraryViewModel.kt` + the
 *    parallel debug-only `Screen.LibraryRework` adapter (which had
 *    served as the side-by-side compare surface during the
 *    7.x-prefixed parity campaign). The keys NOW belong to the
 *    rework alone — the legacy `LibraryViewModel.KEY_SORT` /
 *    `KEY_SORT_ASC` / `KEY_SHOW_*` constant declarations at
 *    `shared/.../library/ui/viewmodel/LibraryViewModel.kt:147-155`
 *    are gone (verified by filesystem check returning zero hits
 *    for the legacy VM file path). HOWEVER — the wire-format-compat
 *    shared-cell posture STILL HOLDS structurally: the on-disk byte
 *    representation under the `"library_sort"` / `"library_sort_asc"`
 *    / `"library_filter"` / `"library_grid_density"` /
 *    `"library_category"` / `"library_last_updated"` /
 *    `"library_show_*"` keys (declared as `SORT_KEY` /
 *    `SORT_DIRECTION_KEY` / `FILTER_KEY` / `GRID_DENSITY_KEY` /
 *    `CATEGORY_KEY` / `LAST_UPDATED_KEY` / `SHOW_*_KEY` in the
 *    companion below) preserves every user's pre-retire choices
 *    byte-for-byte across the strangler-fig transition — exactly
 *    what the §253-era "user's persisted choice should survive the
 *    strangler-fig transition" rationale (L51-53) protected. The
 *    "duplicated locally rather than imported from `:shared`"
 *    rationale (L37-42) is now retroactively the right call —
 *    importing the constants from the legacy `LibraryViewModel`
 *    would have created a §347 retire-blocker that this impl
 *    correctly side-stepped. Mirror of §§475-480 cluster-tier
 *    partially-fulfilled-inversion precedent. The SRP / DIP /
 *    wire-format-compatibility / unknown-value-fallback /
 *    no-flowOn-rationale / suspend-setters-rationale /
 *    no-op-write-protection sub-sections all stand on their own
 *    merits past the §§346 + 347 fulfilled landings — the impl is
 *    now the SOLE owner of these prefs keys, with no legacy reader/
 *    writer in the codebase. Original §253-era prose preserved
 *    verbatim per the audit-trail-preservation convention — the
 *    citation is historical record of the design lineage including
 *    the deferred-route-swap forecast that was subsequently
 *    fulfilled across §§346 + 347.
 */
@OptIn(ExperimentalSettingsApi::class)
class LibraryPrefsRepositoryImpl(
    private val settings: ObservableSettings,
) : LibraryPrefsRepository {

    override fun observeSort(): Flow<LibrarySort> =
        settings.getStringFlow(SORT_KEY, defaultValue = DEFAULT_SORT_NAME)
            .map { it.toLibrarySort() }

    override suspend fun setSort(sort: LibrarySort) {
        settings.putString(SORT_KEY, sort.name)
    }

    override fun observeRandomSeed(): Flow<Long> =
        settings.getLongFlow(RANDOM_SEED_KEY, defaultValue = DEFAULT_RANDOM_SEED)

    override suspend fun setRandomSeed(seed: Long) {
        settings.putLong(RANDOM_SEED_KEY, seed)
    }

    override fun observeSortDirection(): Flow<SortDirection> =
        settings.getBooleanFlow(SORT_DIRECTION_KEY, defaultValue = DEFAULT_ASCENDING)
            .map { ascending -> if (ascending) SortDirection.ASCENDING else SortDirection.DESCENDING }

    override suspend fun setSortDirection(direction: SortDirection) {
        settings.putBoolean(SORT_DIRECTION_KEY, direction == SortDirection.ASCENDING)
    }

    override fun observeFilter(): Flow<LibraryFilter> =
        settings.getStringFlow(FILTER_KEY, defaultValue = DEFAULT_FILTER_NAME)
            .map { it.toLibraryFilter() }

    override suspend fun setFilter(filter: LibraryFilter) {
        settings.putString(FILTER_KEY, filter.name)
    }

    override fun observeGridDensity(): Flow<GridDensity> =
        settings.getStringFlow(GRID_DENSITY_KEY, defaultValue = DEFAULT_GRID_DENSITY_NAME)
            .map { it.toGridDensity() }

    override suspend fun setGridDensity(density: GridDensity) {
        settings.putString(GRID_DENSITY_KEY, density.name)
    }

    override fun observeItemsPerRow(): Flow<Int> =
        settings.getIntFlow(ITEMS_PER_ROW_KEY, defaultValue = DEFAULT_ITEMS_PER_ROW)

    override suspend fun setItemsPerRow(itemsPerRow: Int) {
        settings.putInt(ITEMS_PER_ROW_KEY, itemsPerRow)
    }

    override fun observeCategory(): Flow<LibraryCategory> =
        settings.getStringFlow(CATEGORY_KEY, defaultValue = DEFAULT_CATEGORY_NAME)
            .map { it.toLibraryCategory() }

    override suspend fun setCategory(category: LibraryCategory) {
        settings.putString(CATEGORY_KEY, category.name)
    }

    override fun observeLastUpdated(): Flow<Instant?> =
        settings.getStringFlow(LAST_UPDATED_KEY, defaultValue = "")
            .map { it.toInstantOrNull() }

    override suspend fun setLastUpdated(timestamp: Instant) {
        // Persist the same zone-less `LocalDateTime.toString()` wire format the reader parses and
        // the Android `LibraryRefreshWorker` writes, so the cell stays byte-for-byte compatible.
        val local = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
        settings.putString(LAST_UPDATED_KEY, local.toString())
    }

    override fun observeDisplay(): Flow<LibraryDisplay> =
        combine(
            settings.getBooleanFlow(SHOW_SOURCE_KEY, defaultValue = DEFAULT_SHOW_TOGGLE),
            settings.getBooleanFlow(SHOW_COUNT_KEY, defaultValue = DEFAULT_SHOW_TOGGLE),
            settings.getBooleanFlow(SHOW_DETAILS_KEY, defaultValue = DEFAULT_SHOW_TOGGLE),
            settings.getBooleanFlow(SHOW_BUTTONS_KEY, defaultValue = DEFAULT_SHOW_TOGGLE),
            settings.getBooleanFlow(SHOW_TABS_KEY, defaultValue = DEFAULT_SHOW_TOGGLE),
        ) { showSource, showCount, showDetails, showButtons, showTabs ->
            LibraryDisplay(
                showSource = showSource,
                showCount = showCount,
                showDetails = showDetails,
                showButtons = showButtons,
                showTabs = showTabs,
            )
        }

    override suspend fun setShowSource(showSource: Boolean) {
        settings.putBoolean(SHOW_SOURCE_KEY, showSource)
    }

    override suspend fun setShowCount(showCount: Boolean) {
        settings.putBoolean(SHOW_COUNT_KEY, showCount)
    }

    override suspend fun setShowDetails(showDetails: Boolean) {
        settings.putBoolean(SHOW_DETAILS_KEY, showDetails)
    }

    override suspend fun setShowButtons(showButtons: Boolean) {
        settings.putBoolean(SHOW_BUTTONS_KEY, showButtons)
    }

    override suspend fun setShowTabs(showTabs: Boolean) {
        settings.putBoolean(SHOW_TABS_KEY, showTabs)
    }

    private fun String.toLibrarySort(): LibrarySort =
        LibrarySort.entries.firstOrNull { it.name == this } ?: LibrarySort.ALPHABETIC

    private fun String.toLibraryFilter(): LibraryFilter =
        LibraryFilter.entries.firstOrNull { it.name == this } ?: LibraryFilter.ALL

    private fun String.toGridDensity(): GridDensity =
        GridDensity.entries.firstOrNull { it.name == this } ?: GridDensity.COMFORTABLE

    private fun String.toLibraryCategory(): LibraryCategory =
        LibraryCategory.entries.firstOrNull { it.name == this } ?: LibraryCategory.NAN

    /**
     * Parse the legacy `LocalDateTime.toString()` ISO-8601 wire format (e.g. `"2026-05-27T14:30:00"`)
     * into a universal-time [Instant] via the current system [TimeZone]. Empty strings (no value
     * persisted yet — first-run users, iOS/Desktop where the Android-only legacy refresh worker
     * cannot write) and malformed strings (corrupt write, partial value) map to `null` so the
     * `:presentation` layer can render the "Never updated" fallback without crashing.
     *
     * Why parse via [LocalDateTime] rather than [Instant.parse]: the legacy worker persists
     * `LocalDateTime.toString()` (zone-less local-time format), not `Instant.toString()` (UTC
     * with `Z` suffix). Calling `Instant.parse("2026-05-27T14:30:00")` throws because of the
     * missing zone; the legacy `LibraryViewModel.lastUpdatedFlow` uses the same `LocalDateTime
     * .parse(...)` shape (see `shared/.../library/ui/viewmodel/LibraryViewModel.kt:278`).
     */
    private fun String.toInstantOrNull(): Instant? {
        if (this.isBlank()) return null
        return runCatching {
            LocalDateTime.parse(this).toInstant(TimeZone.currentSystemDefault())
        }.getOrNull()
    }

    private companion object {
        const val SORT_KEY = "library_sort"
        const val SORT_DIRECTION_KEY = "library_sort_asc"
        const val FILTER_KEY = "library_filter"
        const val GRID_DENSITY_KEY = "library_grid_density"
        // Native parity: byte-for-byte the native `LibraryViewModel.KEY_ITEMS_PER_ROW`
        // (`shared`/native `library_items_per_row`, an Int) so the user's column-count choice
        // survives the native ⇄ KMP transition. Unlike the enum-string axes above, this cell is
        // a raw Int (0 = Auto, 1..8 = fixed columns) — no name-token mapper needed.
        const val ITEMS_PER_ROW_KEY = "library_items_per_row"
        const val CATEGORY_KEY = "library_category"
        const val LAST_UPDATED_KEY = "library_last_updated"
        // RANDOM-sort stable-shuffle seed (rework's own Long cell). Default matches native's
        // KEY_SEED default so a first shuffle is deterministic across the transition.
        const val RANDOM_SEED_KEY = "library_random_seed"

        // §150 rung 16 — display-toggle disk keys. Each preserves the legacy
        // `SharedPrefsHelper.KEY_SHOW_*` constant byte-for-byte (see
        // `shared/.../library/ui/viewmodel/LibraryViewModel.kt:147-152`) so the user's
        // persisted toggle choices survive the strangler-fig transition without resetting.
        // Same shared-cell posture as the sort + direction + filter + density + category
        // keys above. Phase 9.x route-swap retires the legacy `LibraryViewModel`; at that
        // point the keys belong to the rework alone.
        //
        // Audit-trail postscript (Phase 9.x.cluster25.staleKdocSweep.cascade, Task #481,
        // 2026-05-28): the "Phase 9.x route-swap retires the legacy `LibraryViewModel`" /
        // "the keys belong to the rework alone" forecast is FULFILLED here — Phase
        // 9.x.library.swap (§346) re-pointed `Screen.Library` to the rework adapter;
        // Phase 9.x.library.retire (§347) deleted the legacy `LibraryViewModel` source
        // file at `shared/.../library/ui/viewmodel/LibraryViewModel.kt` (verified by
        // filesystem check returning zero hits — the cited `KEY_SHOW_*` constant
        // declarations at L147-152 of the retired file are gone). The wire-format-compat
        // byte-for-byte posture still HOLDS for users' on-disk persisted toggle choices
        // (preserved across the §347 retire), but the cited legacy-source anchor is now
        // an orphan source-reference pointing at a retired file. The display-toggle
        // semantics + first-run-default-true behaviour now belong fully to this impl + its
        // [LibraryDisplay] domain projection. Mirror of the class-level postscript above
        // and §§475-480 cluster-tier partially-fulfilled-inversion precedent. Original
        // §150-era prose preserved verbatim per the audit-trail-preservation convention.
        const val SHOW_SOURCE_KEY = "library_show_source"
        const val SHOW_COUNT_KEY = "library_show_count"
        const val SHOW_DETAILS_KEY = "library_show_details"
        const val SHOW_BUTTONS_KEY = "library_show_buttons"
        const val SHOW_TABS_KEY = "library_show_tabs"

        const val DEFAULT_SORT_NAME = "ALPHABETIC"
        const val DEFAULT_ASCENDING = true
        const val DEFAULT_FILTER_NAME = "ALL"
        const val DEFAULT_GRID_DENSITY_NAME = "COMFORTABLE"
        // Native default — `prefs.getInt("library_items_per_row", 0)`. 0 = Auto (adaptive grid).
        const val DEFAULT_ITEMS_PER_ROW = 0
        const val DEFAULT_CATEGORY_NAME = "NAN"
        // Native KEY_SEED default (stable shuffle on first RANDOM use before any reshuffle).
        const val DEFAULT_RANDOM_SEED = 64464L

        // All five display toggles default to `true` — mirrors `LibraryDisplay`'s default-true
        // posture (see its KDoc) and the legacy `prefs.getBoolean(KEY_SHOW_*, true)` reads at
        // `LibraryViewModel.kt:172-176`. First-run users on the rework see exactly what they'd
        // see on legacy: every optional surface visible.
        const val DEFAULT_SHOW_TOGGLE = true
    }
}
