package me.manga.kira.presentation.complaint.admin

import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.presentation.mvi.MviState

/**
 * State for the rework admin Complaint dashboard screen.
 *
 * Phase 7.x.complaint.admin rework. Holds the full list of all submitted complaints, the
 * in-progress search/filter selections, and the derived [filtered] projection that the `:ui`
 * composable renders directly.
 *
 * **Why store [filtered] explicitly rather than deriving in the composable**: same rationale as
 * the user-side [me.manga.kira.presentation.complaint.ComplaintState] — the rework `:ui`
 * layer is contractually stateless. Storing search/filter selections in Compose `remember`
 * (as the legacy admin screen does, lines 139-144 of legacy
 * `composeApp/.../admin/complaint/AdminComplaintScreen.kt`) would split the source of truth
 * between VM and view. Recomputing in the VM on each intent keeps the composable stateless and
 * lets future tests assert on `state.filtered` directly.
 *
 * **Three filter dimensions** (vs the user-side's one): the admin view filters by status, type,
 * AND app version, matching legacy admin parity. The legacy admin screen supports 7 sort modes
 * (DATE_ASC / DATE_DESC / STATUS / TYPE / USER_ID / APP_VERSION / APP_VERSION_DESC) — all
 * ported across the `sort` + `versionfilter` slices.
 *
 * Field semantics:
 *  - [isLoading]: `true` from construction until the first fetch resolves (success or failure).
 *    Mutually exclusive with [error] and a populated [all].
 *  - [error]: non-null when the fetch failed. The `:ui` composable shows an inline error
 *    message + Retry button (which fires [AdminComplaintIntent.OnRetry]).
 *  - [all]: the immutable backing list — the full set of complaints from the last successful
 *    fetch. Not modified by search/filter intents — only by `OnRetry`.
 *  - [filtered]: derived from [all] + [searchQuery] + [selectedStatus] + [selectedType]. The
 *    composable renders this list directly; an empty [filtered] with a non-empty [all] signals
 *    "no matches" and the composable shows an inline "no matches" message; an empty [filtered]
 *    with an empty [all] signals "empty list" and the composable shows an inline empty-state
 *    message.
 *  - [searchQuery]: free-form text. Empty string disables search. Substring matching against
 *    `subject`/`body`/`id`/`userId` (case-insensitive) — matches legacy admin semantics (lines
 *    184-189 of legacy admin screen — admin searches userId too, vs user-side which doesn't).
 *  - [selectedStatus]: `null` means "all statuses", anything else filters to that status.
 *    Single-select with toggle-off — clicking the chip again clears the filter.
 *  - [selectedType]: `null` means "all types", anything else filters to that type. Single-select
 *    with toggle-off.
 *  - [selectedAppVersion]: `null` means "all app versions", anything else filters to that exact
 *    version string. Single-select with toggle-off. Phase 7.x.complaint.admin.versionfilter:
 *    chip list is dynamically derived from `state.all.mapNotNull { it.appVersion }.distinct()
 *    .sorted()` at the `:ui` boundary — only versions actually present in the dataset render as
 *    chips. Complaints with `appVersion == null` are EXCLUDED when this filter is non-null
 *    (matches legacy semantics — the version chip-row's "All" option is the only path to see
 *    null-version complaints).
 *  - [selectedSort]: current sort mode. Defaults to [AdminSortMode.DATE_DESC] (newest first) —
 *    matches the legacy admin's default sort. Sort is always active (no "null = unsorted"
 *    state); the default IS the unsorted-feeling option. Single-select (no toggle-off — pick a
 *    different mode to change).
 *  - [statistics]: aggregate breakdown of the current [all] list. Populated by the VM in
 *    `loadList`'s success branch (immediately before [filtered]); reset to default on failure.
 *    Reflects [all], NOT [filtered] — the card surfaces the full inventory while the filtered
 *    list is below (matches legacy posture; legacy `AdminStatisticsCard` consumes the full list
 *    too). Data-driven: not changed by any intent, only by `loadList`.
 *
 * **Mutually exclusive states**:
 *  - `isLoading == true` → "Loading…" indicator. [all] and [filtered] are empty, [error] is null.
 *  - `error != null` → inline error. [isLoading] is false, [all] and [filtered] are empty.
 *  - `isLoading == false && error == null && all.isEmpty()` → empty-state. No complaints in
 *    the collection.
 *  - `isLoading == false && error == null && all.isNotEmpty()` → list. [filtered] may be a
 *    subset of [all] depending on [searchQuery] / [selectedStatus] / [selectedType].
 *
 * **Dialog substate fields** (Phase 7.x.complaint.admin.actions extension): [actionDialogMode]
 * / [activeComplaint] / [isSubmittingAction] mirror the user-side actions slice's posture
 * (see [me.manga.kira.presentation.complaint.ComplaintState]). The admin actions slice
 * uses STATUS_CHANGE / CLOSURE_REASON / DELETE_CONFIRM modes instead of user-side's
 * REPLY / EDIT / DELETE — the dialogs differ, the substate machinery is identical.
 *
 * Contract §6 SRP: one rule — "the projection of one admin complaint dashboard screen". No
 * business logic; derivation lives in the VM's intent handlers; rendering lives in the `:ui`
 * composable.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster17.staleKdocSweep.cascade,
 * Task #473, 2026-05-28): three categories of stale citations into the
 * §366-retired legacy admin screen appear above:
 *  - Lines 17-21 ("Storing search/filter selections in Compose `remember`
 *    (as the legacy admin screen does, lines 139-144 of legacy
 *    `composeApp/.../admin/complaint/AdminComplaintScreen.kt`)"): the
 *    cited legacy file was retired in Phase 9.x.admincomplaint.retire
 *    (§366 sweep, commit `48a5c2b` "(1/2): delete orphan legacy admin VM
 *    + screen + 2 helpers + drop Koin binding"); verified by filesystem
 *    check returning zero hits. The VM-owned-state-vs-`remember`
 *    contrast remains correct — the rework `:ui` AdminComplaintScreen
 *    consumes `state.filtered` directly with zero `remember` state for
 *    search/filter selections; the `:ui`-must-be-stateless contract
 *    governs the VM-side derivation pattern.
 *  - Line 42 ("Substring matching ... matches legacy admin semantics
 *    (lines 184-189 of legacy admin screen — admin searches userId too,
 *    vs user-side which doesn't)"): same retired file. The substring-
 *    matching-userId-included-admin-only rule remains correct in the
 *    rework `:ui` chip-row + VM filter recompute (verified at
 *    [AdminComplaintViewModel] — the search predicate includes
 *    `userId.contains(query, ignoreCase = true)` on admin only).
 *  - Lines 138-143 (`SortOption.kt` enum mirror citation):
 *    "Mirrors the legacy admin's `SortOption` enum
 *    (`composeApp/.../admin/complaint/SortOption.kt`) 1:1 — all 7 modes
 *    ported." The legacy `SortOption.kt` file was retired alongside the
 *    admin screen in §366 (`48a5c2b`); verified by filesystem check
 *    returning zero hits. The 1:1 ports + 7-mode enumeration remain
 *    accurate against the rework AdminSortMode enum (lines 176-184) —
 *    DATE_DESC / DATE_ASC / STATUS / TYPE / USER_ID / APP_VERSION /
 *    APP_VERSION_DESC, same ordering. The "all 7 modes ported across
 *    the `sort` + `versionfilter` slices" framing is itself a fulfilled
 *    prediction — §§261 + 264 + 266 landed the full sort surface
 *    including chip-row semver sort.
 *  - Line 147 (`AdminComplaintScreen.kt:143` legacy-default-sort line
 *    anchor): same retired file. The DATE_DESC-default-sort rule remains
 *    correct in the rework state initializer (line 91 above —
 *    `val selectedSort: AdminSortMode = AdminSortMode.DATE_DESC`); the
 *    legacy-line-anchor framing is historical, the default itself stands.
 *  - Line 190 (`AdminComplaintScreen.kt:619-720` legacy
 *    `AdminStatisticsCard` value-shape anchor): same retired file.
 *    Phase 7.x.complaint.admin.stats (§263) ported the total-count +
 *    per-status-breakdown shape to the rework AdminComplaintStatistics
 *    data class (lines 220-224); the legacy-line-anchor is historical,
 *    the shape rationale stands.
 * The mutually-exclusive-states matrix + three-filter-axes posture +
 * stats-reflects-`all`-not-`filtered` posture + dialog-substate-mirrors-
 * user-side posture all stand on their own merits past the §366 retire
 * + §§259-266 + §272 fulfilled landings. The rework AdminComplaintState
 * surface remains LIVE as the canonical 13-field state contract for the
 * rework AdminComplaintViewModel, with sibling AdminActionDialogMode
 * (lines 126-133) + AdminSortMode (lines 176-184) + AdminComplaintStatistics
 * (lines 220-224) enums/classes carrying the per-axis contracts.
 * Original §253-era prose preserved verbatim per the audit-trail-
 * preservation convention — the citations are historical record of the
 * design lineage including the legacy-parity anchors that were
 * subsequently retired.
 */
data class AdminComplaintState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val all: List<ComplaintSummary> = emptyList(),
    val filtered: List<ComplaintSummary> = emptyList(),
    val searchQuery: String = "",
    val selectedStatus: ComplaintStatus? = null,
    val selectedType: ComplaintType? = null,
    val selectedAppVersion: String? = null,
    val selectedSort: AdminSortMode = AdminSortMode.DATE_DESC,
    val actionDialogMode: AdminActionDialogMode = AdminActionDialogMode.NONE,
    val activeComplaint: ComplaintSummary? = null,
    val isSubmittingAction: Boolean = false,
    val statistics: AdminComplaintStatistics = AdminComplaintStatistics(),
    /**
     * GAP-CMP-15 — whether the [StatisticsCard] is shown. Mirrors native
     * `AdminComplaintScreen.kt:162-188`, which renders the StatisticsCard only when `showStats`
     * and exposes a TopAppBar Visibility/VisibilityOff toggle. Default `true` matches the native
     * default-visible posture. Flipped by [AdminComplaintIntent.OnToggleStatsCard].
     */
    val showStats: Boolean = true,
) : MviState

/**
 * Dialog mode for the admin complaint action flow.
 *
 * Phase 7.x.complaint.admin.actions rework. Mirrors the user-side
 * [me.manga.kira.presentation.complaint.ActionDialogMode] state-machine shape — explicit
 * `NONE` (no dialog) + `MENU` (choose-an-action) + per-action modes. Different variants vs
 * the user-side because admin actions are status-change / closure-reason / delete (admin edit
 * deferred to a future `admin.edit` slice).
 *
 * Variants:
 *  - [NONE]: no dialog open. [AdminComplaintState.activeComplaint] is `null`.
 *  - [MENU]: row tapped — the action menu is open showing the 3 admin actions for the active
 *    complaint. Tapping an action transitions to [STATUS_CHANGE], [CLOSURE_REASON], or
 *    [DELETE_CONFIRM].
 *  - [STATUS_CHANGE]: status-picker dialog. Affordance to choose a new [ComplaintStatus]
 *    (excluding the current one — gated by `:ui`). On submit, fires
 *    [AdminComplaintIntent.OnSubmitStatusChange].
 *  - [CLOSURE_REASON]: free-text reason entry. Submit fires
 *    [AdminComplaintIntent.OnSubmitClosureReason]. The repository auto-CLOSES the complaint
 *    if its current status is OPEN or IN_PROGRESS.
 *  - [DELETE_CONFIRM]: confirmation dialog. Submit fires [AdminComplaintIntent.OnConfirmDelete].
 *  - [EDIT]: subject + body edit form. Submit fires [AdminComplaintIntent.OnSubmitEdit]. The
 *    repository preserves `userId` / `type` / `createdAt` / `status` / `metadata` across the
 *    write — closure-reason audit fields survive admin edits.
 *
 * **`MENU` exists vs `NONE` overload**: same rationale as the user-side enum — disambiguates
 * "no dialog" vs "dialog at menu", makes the state machine clearer.
 */
enum class AdminActionDialogMode {
    NONE,
    MENU,
    STATUS_CHANGE,
    CLOSURE_REASON,
    DELETE_CONFIRM,
    EDIT,
}

/**
 * Sort mode for the admin complaint dashboard list.
 *
 * Phase 7.x.complaint.admin.sort rework + Phase 7.x.complaint.admin.versionfilter completion.
 * Mirrors the legacy admin's `SortOption` enum (`composeApp/.../admin/complaint/SortOption.kt`)
 * 1:1 — all 7 modes ported. The two APP_VERSION sort modes landed alongside the third filter
 * axis once the `appVersion: String?` carve-out from legacy `metadata` was in place at the
 * `:data` boundary (see [me.manga.kira.domain.model.complaint.ComplaintSummary] field
 * KDoc + [me.manga.kira.data.repository.AdminComplaintListRepositoryImpl] mapper).
 *
 * Variants (matching the legacy `SortOption` enum's order):
 *  - [DATE_DESC]: newest first by `createdAt`. The default — matches the legacy default
 *    (`AdminComplaintScreen.kt:143`).
 *  - [DATE_ASC]: oldest first by `createdAt`.
 *  - [STATUS]: ordered by [me.manga.kira.domain.model.complaint.ComplaintStatus] ordinal
 *    (OPEN → IN_PROGRESS → RESOLVED → CLOSED → PLANNED → PINNED → UNKNOWN → NOT_PLANNED).
 *  - [TYPE]: ordered by [me.manga.kira.domain.model.complaint.ComplaintType] ordinal
 *    (TECHNICAL → LANGUAGES → SITES_ADD → SITE_ERROR → FEATURES → CUSTOM).
 *  - [USER_ID]: lexicographic by `userId`.
 *  - [APP_VERSION]: lexicographic by `appVersion` ascending. Complaints with `appVersion == null`
 *    sort first (`sortedBy` nullable convention). Matches legacy semantics. Phase 7.x.complaint
 *    .admin.versionfilter.
 *  - [APP_VERSION_DESC]: lexicographic by `appVersion` descending. Complaints with
 *    `appVersion == null` sort last (`sortedByDescending` nullable convention). Phase 7.x.
 *    complaint.admin.versionfilter.
 *
 * **Lexicographic vs semver comparison**: the two APP_VERSION sorts use string comparison, not
 * semver-aware comparison. Matches legacy posture (the legacy sort also compares strings). For
 * dotted-numeric versions like "1.2.3" vs "1.10.0", lexicographic ordering ("1.10.0" sorts
 * before "1.2.3") differs from semver ordering ("1.2.3" sorts before "1.10.0") — accepted as
 * legacy-parity tradeoff. A future `Phase 7.x.complaint.admin.semver` slice could lift this.
 *
 * **Nullable createdAt** ([DATE_ASC] / [DATE_DESC]): `sortedBy { it.createdAt }` puts nulls
 * first; `sortedByDescending` puts nulls last (Kotlin's nullable Comparable convention).
 * Same as the legacy semantics — the legacy's `createdAt` is `java.util.Date?` and uses the
 * identical Kotlin stdlib sort.
 *
 * **No `null` "unsorted" option** — sort is always active. The default [DATE_DESC] serves
 * as the natural baseline; users pick a different mode to change. Single-select; no
 * toggle-off (vs the status/type filter chips which DO toggle off).
 */
enum class AdminSortMode {
    DATE_DESC,
    DATE_ASC,
    STATUS,
    TYPE,
    USER_ID,
    APP_VERSION,
    APP_VERSION_DESC,
}

/**
 * Aggregate breakdown of the admin complaint dashboard's current list.
 *
 * Phase 7.x.complaint.admin.stats rework. Mirrors the legacy admin's `AdminStatisticsCard`
 * value shape (legacy `composeApp/.../admin/complaint/AdminComplaintScreen.kt:619-720`) —
 * total count + per-status breakdown.
 *
 * Field semantics:
 *  - [total]: count of complaints in [AdminComplaintState.all] (the FULL inventory, not the
 *    filtered subset — matches legacy posture where `AdminStatisticsCard(complaints =
 *    allComplaints)` is passed the full list).
 *  - [byStatus]: per-status count. Only enum keys with non-zero counts need be present (the
 *    `:ui` card filters non-zero buckets on render; an empty map renders zero rows). The
 *    aggregation in the VM uses `groupBy { it.status }.mapValues { it.value.size }` which
 *    naturally yields a map containing only the statuses present in the data.
 *  - [byAppVersion]: per-app-version count, keyed by the `appVersion` string. Phase 7.x.
 *    complaint.admin.versionfilter — `appVersion` carved out of the legacy `metadata` map at
 *    the `:data` boundary. Aggregation in the VM uses `list.mapNotNull { it.appVersion }
 *    .groupingBy { it }.eachCount()`. The `mapNotNull` step intentionally drops complaints with
 *    `appVersion == null` — the breakdown only surfaces versions actually present in the
 *    dataset (matches the dynamic chip-row derivation on the filter axis).
 *
 * **Reflects `all`, not `filtered`**: the card surfaces the full inventory while the filtered
 * list is below. Same posture as legacy. The user can still infer "what would my filters show"
 * by reading the results-count text on the search/filter section.
 *
 * **Defer `byType`**: legacy doesn't render a by-type breakdown either — the existing filter
 * chip row already surfaces "what types exist". Adding a breakdown would be redundant.
 *
 * Default empty instance (`AdminComplaintStatistics()`) represents the initial state during
 * load + the failure state. The `:ui` composable never renders the stats card during loading
 * / error / empty-list states (the outer `Box` shows a spinner / inline error / placeholder
 * instead), so the user never sees `total == 0` in practice.
 */
data class AdminComplaintStatistics(
    val total: Int = 0,
    val byStatus: Map<ComplaintStatus, Int> = emptyMap(),
    val byAppVersion: Map<String, Int> = emptyMap(),
)
