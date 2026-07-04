# Phase 7.x.complaint.admin.sort — admin sort dropdown sub-slice

## Context

§102's actions and §103's edit closed-out the admin mutations triad + edit.
Per §103.9's recommendation, the natural next strategic slice is the sort
dropdown — smallest self-contained extension that materially improves
admin daily workflow (date-desc default → date-asc / status / type /
userId sorts).

The legacy admin screen at
`composeApp/.../admin/complaint/AdminComplaintScreen.kt:143,198-209,567-587`
implements 7 sort modes via `SortOption` enum:
`DATE_DESC` (default), `DATE_ASC`, `STATUS`, `TYPE`, `USER_ID`,
`APP_VERSION`, `APP_VERSION_DESC`.

This slice ports **5 of 7** modes — the ones whose sort key is available
on the `:domain` `ComplaintSummary` (`createdAt` / `status` / `type` /
`userId`). The two `APP_VERSION` modes require a `metadata.appVersion`
field that `:domain` `ComplaintSummary` deliberately omits (per
`ComplaintSummary` KDoc lines 28-34 — `Map<String, Any>` is banned by
contract §6). The two `APP_VERSION` sorts are deferred to a future
`Phase 7.x.complaint.admin.versionfilter` slice that will introduce the
app-version dimension along with the third filter axis.

Block-and-ask triggers (a)-(d) all NOT met:
- (a) No contract library blocker — pure `:presentation` + `:ui`.
- (b) No observable behaviour change — legacy admin route preserved
  (untouched); rework admin route remains debug-guarded.
- (c) No compile risk — sealed `AdminComplaintIntent` extension is
  additive; `AdminComplaintState` field addition is additive; the VM
  `when (intent)` arm grows by 1 case (compile-time enforced
  exhaustiveness).
- (d) No SOLID violation — the sort lives in the VM's `applyFilter`
  pipeline (renamed to `applyFilterAndSort` or kept and chained). The
  `SortMode` enum lives in `:presentation` next to `AdminActionDialogMode`
  for consistency.

## Approach

Pure `:presentation` + `:ui` slice. No `:domain` / `:data` /
`:composeApp` Koin changes — the sort is a VM-side client-side derivation
over the already-fetched list, same posture as the filter.

### `:presentation` MVI shape changes

Additive (OCP §6) — no existing variants modified or removed:

- New enum `AdminSortMode` in `AdminComplaintState.kt`:
  ```kotlin
  enum class AdminSortMode { DATE_DESC, DATE_ASC, STATUS, TYPE, USER_ID }
  ```
  5 variants (the 5 sortable-by-`:domain`-field modes). `DATE_DESC` is
  first — the default. KDoc cross-references the legacy `SortOption` and
  documents the 2 deferred APP_VERSION modes.

- `AdminComplaintState` gains 1 field:
  `val selectedSort: AdminSortMode = AdminSortMode.DATE_DESC`. Default
  matches legacy default (line 143).

- `AdminComplaintIntent` gains 1 variant:
  `data class OnSortChange(val mode: AdminSortMode) : AdminComplaintIntent`.
  Single-select (no "All" / clear — sort always has a mode; the default
  serves as the "natural Firestore order" reset).

- `AdminComplaintViewModel.handle()`'s `when` arm grows by 1 case:
  `is AdminComplaintIntent.OnSortChange -> ...`. State mutation updates
  `selectedSort` and recomputes `filtered` via `applyFilterAndSort(...)`.

- Existing `applyFilter(all, query, status, type)` widens to
  `applyFilterAndSort(all, query, status, type, sort)`. Same logic as
  before for filter; then `.sortedBy { ... }` or
  `.sortedByDescending { ... }` based on the sort mode. The sort branch:
  ```kotlin
  when (sort) {
      AdminSortMode.DATE_DESC -> filtered.sortedByDescending { it.createdAt }
      AdminSortMode.DATE_ASC -> filtered.sortedBy { it.createdAt }
      AdminSortMode.STATUS -> filtered.sortedBy { it.status.ordinal }
      AdminSortMode.TYPE -> filtered.sortedBy { it.type.ordinal }
      AdminSortMode.USER_ID -> filtered.sortedBy { it.userId }
  }
  ```
  Verbatim port from legacy lines 198-209 (minus the 2 APP_VERSION
  branches). `Instant?` sort: `sortedBy { it.createdAt }` puts nulls
  first (Kotlin stdlib's nullable Comparable convention) — same as
  legacy `sortedBy { it.createdAt }` (legacy's `createdAt` is
  `java.util.Date?`, Kotlin's stdlib `compareBy` handles nullable
  consistently — nulls first for ASC, last for DESC).

- All 6 existing handlers that recompute `filtered` (the filter / search
  / clear ones) need to pass `selectedSort` through to the new
  `applyFilterAndSort` helper. Refactor: thread the sort param.

### `:ui` composable changes

Add a sort dropdown to `AdminComplaintScreen.kt`'s `SearchAndFilterSection`
above the results count. Same pattern as the legacy lines 565-587:

```kotlin
var expanded by remember { mutableStateOf(false) }
Box {
    OutlinedButton(onClick = { expanded = true }) {
        Text("Sort: ${selectedSort.name}")
        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        AdminSortMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(mode.name) },
                onClick = {
                    onIntent(AdminComplaintIntent.OnSortChange(mode))
                    expanded = false
                },
            )
        }
    }
}
```

The new `selectedSort` param threads from `AdminComplaintScreen` →
`AdminComplaintScreenContent` → `SearchAndFilterSection`. Each function
signature gains 1 param.

## Commit roadmap

Three commits, all ≤5 files per the standing cap:

1. **Plan commit** — `PLAN_complaint_admin_sort.md` only (1 file).

2. **`:presentation` MVI + `:ui` dropdown** — 4 modified files
   (bundled to keep the build green; if shipped separately, the VM's
   handler for `OnSortChange` would be unreachable in commit 2 and the
   `:ui` dropdown in commit 3 would dispatch an intent the VM doesn't
   handle — bundling is the natural fit):
   - MOD `presentation/.../complaint/admin/AdminComplaintState.kt`
     — add `AdminSortMode` enum + `selectedSort` field.
   - MOD `presentation/.../complaint/admin/AdminComplaintIntent.kt`
     — add `OnSortChange(mode)` variant.
   - MOD `presentation/.../complaint/admin/AdminComplaintViewModel.kt`
     — `when` arm extends; `applyFilter` becomes `applyFilterAndSort`;
     all 4 filter/search/clear handlers pass `selectedSort` through.
   - MOD `ui/.../complaint/admin/AdminComplaintScreen.kt` — sort
     dropdown in `SearchAndFilterSection` + thread `selectedSort` param.

3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` §104 — strategy, layer-by-layer surfaces,
     MVI shape rationale, sort-key availability rationale, files
     added, deferrals, next-candidate block.
   - `SOLID_AUDIT.md` Phase 7.x.complaint.admin.sort entry — per-file
     SOLID 10-point checklist (4 modified files in commit 2 + plan file),
     end-of-slice verdict, build gates, next-candidate block.

## Critical files

### New

- `PLAN_complaint_admin_sort.md`

### Modified

- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/complaint/admin/AdminComplaintScreen.kt`
- `ARCHITECTURE.md` — append §104.
- `SOLID_AUDIT.md` — append Phase 7.x.complaint.admin.sort entry.

### Untouched (verify by read, not modified)

- `composeApp/.../admin/complaint/AdminComplaintScreen.kt` — legacy
  admin screen; preserved verbatim. Phase 9.x route-swap retires it.
- `composeApp/.../admin/complaint/SortOption.kt` — legacy enum; preserved.
- `domain/.../model/complaint/ComplaintSummary.kt` — pure value type;
  the sort uses its existing fields (`createdAt` / `status` / `type` /
  `userId`).
- `domain/.../repository/AdminComplaintListRepository.kt` — sort is
  VM-side, not repository-side. No interface change.
- `data/.../repository/AdminComplaintListRepositoryImpl.kt` — untouched
  for the same reason. Zero new `:data` → `:shared` reach.
- `composeApp/.../di/ComplaintAdminReworkModule.kt` — no Koin changes.

## Reuse

- **Filter pipeline shape** lifted directly from the existing
  `applyFilter` helper — sort is a chained `.sortedBy` after the
  `.filter`. Same `recomputeFiltered` pattern in every handler that
  touches search / filter / sort.
- **MVI base class**: extends `MviViewModel<S, I, E>` — no changes.
- **`AdminActionDialogMode` enum precedent**: `AdminSortMode` lives in
  the same file (`AdminComplaintState.kt`) as a sibling top-level enum.
- **OutlinedButton + DropdownMenu pattern** lifted directly from the
  legacy admin screen lines 565-587 — same Material3 components, same
  shape.

## Verification

After commit 2 (the source commit):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile
  gate.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS sim.

On-device smoke tests (Windows-impossible, deferred to user's Mac):
- Navigate to `Screen.ComplaintAdminRework` via the debug-guarded entry,
  verify the sort dropdown shows 5 modes, verify each mode reorders the
  list correctly, verify the sort persists across search/filter changes.
- Verify DATE_DESC is the initial selection.

Edge cases to mentally model:
- Empty list: sort is a no-op on `emptyList()`. Default state is
  `selectedSort = DATE_DESC` — the dropdown shows it but disables (or
  just shows the label) when there are no items to sort.
- Nullable `createdAt`: `sortedBy { it.createdAt }` puts nulls first
  (Kotlin's nullable Comparable convention). `sortedByDescending` puts
  nulls last. Same as legacy semantics.
- Filter + sort combo: filter runs first, then sort. So sorting "STATUS"
  while filtering "type = TECHNICAL" sorts only the technical complaints
  by status — same as legacy behaviour.
- Sort change with active search: search runs first (matches subject /
  body / id / userId), then sort. So changing sort doesn't clear search.

## Deferrals

- **APP_VERSION / APP_VERSION_DESC sorts** — require
  `metadata.appVersion` on the domain model; deferred to
  `Phase 7.x.complaint.admin.versionfilter` slice that introduces the
  app-version dimension along with the filter axis. Adding it now
  would force `Map<String, Any>` into the `:domain` boundary (contract
  §6 banned) or introduce a sibling `ComplaintFullRecord` data class
  which is a larger architectural decision better made in the
  versionfilter slice.
- **Sort-direction toggle (asc ↔ desc)** — legacy has separate enum
  variants for ASC and DESC of date; this slice preserves that. A
  future polish could fold them into a single "Date" mode with a
  direction arrow icon, but that's UI polish, not architecture.
- **Sort persistence across app restarts** — legacy admin doesn't
  persist sort; this slice doesn't either. A future settings slice
  could add it via `SettingsRepository`.
- **i18n: hardcoded `mode.name` labels** — defer to Phase 10 i18n lift,
  same as the foundation's filter chip labels.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: Sort lives in `AdminComplaintViewModel.applyFilterAndSort` —
  the existing single-responsibility "compute filtered+sorted list"
  step. Renaming preserves semantic clarity.
- **OCP**: Sealed-interface `AdminComplaintIntent` and enum
  `AdminSortMode` are both additive. Future sort modes (APP_VERSION)
  slot into the enum without breaking the existing 5.
- **LSP**: Substitutability unchanged — VM signature gains 0 ctor args.
- **ISP**: Sort param threads through composable signatures by 1 param
  each — minimum viable. `AdminComplaintRow` and the dialogs don't see
  the sort (they don't need it).
- **DIP**: Pure `:presentation` + `:ui`. No new `:data` / `:domain`
  reach. The sort uses existing `:domain` fields.
- **Layer boundary**: changes touch `:presentation` (3 modified files)
  + `:ui` (1 modified file) + close-out (`ARCHITECTURE.md` +
  `SOLID_AUDIT.md`). No `:domain` / `:data` / `:composeApp` Koin
  changes.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. All sort
  operations use stdlib `sortedBy` / `sortedByDescending` on existing
  domain fields.
- **MVI contract**: ADDITIVE — state grows by 1 field + 1 enum; intent
  grows by 1 variant; effect surface unchanged at 2 variants. VM ctor
  unchanged at 5 use cases.
- **Strangler-fig**: ZERO new `:data` → `:shared` reaches. The sort is
  a pure VM derivation — no legacy collaborators involved.
- **Load-bearing fixes preserved**: this slice does NOT touch any
  load-bearing image-quality / Reader / Coil path. No risk.
