# Phase 7.x.complaint.admin.versionfilter — third filter axis + 2 deferred sort modes

## Context

Lifts two long-standing deferrals at once:
- `§104` — `AdminSortMode.APP_VERSION` + `APP_VERSION_DESC` sort modes deferred for lack of an
  `appVersion` field on `:domain` `ComplaintSummary`.
- `§106.8` — `byAppVersion` stats breakdown + the legacy admin's third filter axis (app version
  picker) deferred for the same blocker.

The blocker has always been `:domain` contract §6, which bans `Any` — the legacy
`Complaint.metadata: Map<String, Any>?` field carrying `appVersion` (alongside platform / build
metadata) cannot cross into `:domain` unchanged. The lift here is a **carve-out**: introduce a
single non-`Any` field `appVersion: String?` on `ComplaintSummary` populated by the `:data`
mapper extracting `metadata?.get("appVersion")?.toString()`. The other metadata keys stay in
`:shared` until / unless future slices need them.

Legacy admin app-version filter posture (from `composeApp/.../admin/complaint/AdminComplaintScreen.kt`):
- Lines 150 / 624: `complaints.mapNotNull { it.metadata?.get("appVersion")?.toString() }` →
  the chip-row source.
- Lines 193, 205, 208: filter logic — `complaint.metadata?.get("appVersion")?.toString() ==
  selectedAppVersion`.
- Lines 736 / 787 / 793: per-row "v$appVersion" badge.
- Lines 686-720: per-version stats card row (`appVersionCounts.size > 1` gates the section —
  if all complaints are on one app version, the section is hidden).

## Approach

### Carve-out: `appVersion: String?` on `ComplaintSummary`

Add one new field to `:domain` `ComplaintSummary`. It is NOT `Map<String, Any>` — it is a
single nullable `String`. Domain-layer-clean, no banned types crossed. The other legacy
`metadata` keys (`platform`, `build`, etc.) stay in `:shared` until a future slice needs them.

Both `:data` mappers (admin + user-side) populate it from `legacy.metadata?.get("appVersion")?.toString()`
— the user-side foundation doesn't display it, but symmetry costs nothing and keeps the
mapper functions structurally identical.

### Dynamic chip-row sourced from `state.all`

The available app versions are derived in the composable from
`state.all.mapNotNull { it.appVersion }.distinct().sorted()`. NOT stored as a separate
state field — it is a pure projection of `state.all`, recomputed once per recomposition
(O(n log n) where n is bounded by the complaint count, which is admin-only and small).

Two reasons NOT to store as state:
1. **Source-of-truth singularity** — the available versions ARE `state.all`'s
   appVersion set; a separate state field would have to be kept in sync with `loadList`
   success/failure transitions, adding redundant ceremony.
2. **Filter independence** — the chip-row is "all versions present in the inventory", NOT
   "all versions present in the filtered subset". Filtering by status doesn't shrink the
   chip-row (that would be confusing). Storing the list in state could accidentally
   re-derive from `filtered` instead of `all`.

### Two new sort modes: `APP_VERSION` / `APP_VERSION_DESC`

Mirrors the legacy `SortOption` enum's full set (`composeApp/.../admin/complaint/SortOption.kt`).
Sort treats null `appVersion` per Kotlin nullable-Comparable conventions
(`sortedBy { it.appVersion }` puts nulls first; `sortedByDescending` puts nulls last). Same
as the existing `DATE_ASC` / `DATE_DESC` posture for `createdAt`.

### Statistics: add `byAppVersion: Map<String, Int>` to `AdminComplaintStatistics`

Mirrors `byStatus` shape. Only non-null app versions counted. Empty map when no
complaints carry an `appVersion`. The `:ui` `StatisticsCard` renders the section only if
the map is non-empty (matches legacy "if > 1 distinct version" gate, but more lenient —
even a single version with > 0 count is informative). Defer to a `Phase 7.x.complaint.admin.statspolish`
follow-on if the user wants the >1-distinct-version gate.

## Commit roadmap

Five commits, all ≤5 files per the standing cap. Build gates after every source commit
(Android + iOS Arm64 + iOS SimulatorArm64).

1. **Plan commit** — `PLAN_complaint_admin_versionfilter.md` only (1 file).

2. **`:domain` + `:data` carve-out** — 3 files:
   - MOD `domain/.../model/complaint/ComplaintSummary.kt` — add
     `val appVersion: String? = null` field (defaulted so existing tests / mocks don't
     break; production-path callers populate it explicitly).
   - MOD `data/.../repository/AdminComplaintListRepositoryImpl.kt` — `toSummary` mapper
     populates `appVersion = metadata?.get("appVersion")?.toString()`.
   - MOD `data/.../repository/ComplaintListRepositoryImpl.kt` — same mapper update
     (symmetry — user-side foundation also carries the field).

3. **`:presentation` state + intent + VM** — 3 files:
   - MOD `presentation/.../complaint/admin/AdminComplaintState.kt` —
     - Add `selectedAppVersion: String? = null` field to `AdminComplaintState`.
     - Add `APP_VERSION` and `APP_VERSION_DESC` variants to `AdminSortMode` enum.
     - Add `byAppVersion: Map<String, Int> = emptyMap()` field to `AdminComplaintStatistics`.
   - MOD `presentation/.../complaint/admin/AdminComplaintIntent.kt` — add
     `data class OnAppVersionFilter(val appVersion: String?) : AdminComplaintIntent` variant.
   - MOD `presentation/.../complaint/admin/AdminComplaintViewModel.kt` —
     - New `when` arm: `is AdminComplaintIntent.OnAppVersionFilter -> updateState { ... }`.
     - `applyFilterAndSort` gains `appVersion: String?` parameter + filter branch
       (`matchesAppVersion = appVersion == null || complaint.appVersion == appVersion`).
     - `applyFilterAndSort`'s `when (sort)` gains `APP_VERSION` / `APP_VERSION_DESC`
       arms (`sortedBy { it.appVersion }` / `sortedByDescending { it.appVersion }`).
     - `computeStatistics` populates `byAppVersion =
       list.mapNotNull { it.appVersion }.groupingBy { it }.eachCount()`.

4. **`:ui` composable wiring** — 1 file:
   - MOD `ui/.../complaint/admin/AdminComplaintScreen.kt` —
     - `AdminComplaintList` derives `availableAppVersions =
       state.all.mapNotNull { it.appVersion }.distinct().sorted()`.
     - New `AppVersionFilterRow` private composable rendering a `LazyRow` of `FilterChip`s
       — only when `availableAppVersions.isNotEmpty()`. Insert into
       `SearchAndFilterSection` between the Type filter chips and the Sort dropdown.
     - `sortLabel` helper extended with `APP_VERSION` / `APP_VERSION_DESC` cases ("App
       version" / "App version (desc)").
     - `StatisticsCard` extended: after the `byStatus` rows, render a "By app version"
       section if `statistics.byAppVersion` is non-empty (header + per-version row).
     - Per-row "v$appVersion" badge added to `AdminComplaintRow` — between the
       Type and User badges, only when `complaint.appVersion != null`.

5. **Close-out** — 2 files:
   - MOD `ARCHITECTURE.md` — new `## §107 — Phase 7.x.complaint.admin.versionfilter`
     covering: strategy, layer-by-layer, carve-out rationale (`appVersion: String?` vs
     full `Map<String, Any>?`), dynamic chip-row derivation, null-sort posture,
     by-app-version stats, files, build gates, behaviour preservation (legacy admin
     route untouched), deferrals (statspolish gate + carve-out of other metadata keys
     when needed), next-candidate block.
   - MOD `SOLID_AUDIT.md` — Phase 7.x.complaint.admin.versionfilter entry with
     per-file SOLID 10-point checklists (5 files: ComplaintSummary + 2 repo impls +
     State + VM + Screen — note: Intent file's checklist is folded into State's since
     they share semantic scope; Screen's covers all the `:ui` changes).

## Critical files

### Modified

- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/model/complaint/ComplaintSummary.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/AdminComplaintListRepositoryImpl.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/ComplaintListRepositoryImpl.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/complaint/admin/AdminComplaintScreen.kt`
- `ARCHITECTURE.md` (close-out)
- `SOLID_AUDIT.md` (close-out)

### NEW

- `PLAN_complaint_admin_versionfilter.md` (this file)

### Untouched (verify by Read, not modified)

- `shared/.../complaint/model/Complaint.kt` — legacy `metadata: Map<String, Any>?` field
  remains untouched. The `:data` mapper extracts ONLY the `appVersion` sub-key into the
  new `:domain` field; other keys stay in legacy.
- `domain/.../usecase/complaint/*.kt` — use cases pass `ComplaintSummary` through
  unchanged (the new field travels by composition).
- `composeApp/.../di/ComplaintAdminReworkModule.kt` — Koin wiring unchanged (no new
  bindings).
- `composeApp/.../navigation/routes/AdminComplaintReworkScreenRoute.kt` — nav adapter
  unchanged.

## Reuse

- **Carve-out posture**: mirrors the same posture other slices use to extract a single
  field from a larger legacy `Map<String, Any>?` while preserving the `:domain` `Any`
  ban. The strangler-fig boundary is `:data`'s mapper.
- **Filter+sort pipeline**: extends the existing `applyFilterAndSort` helper in
  `AdminComplaintViewModel` — same shape as the prior `selectedStatus` / `selectedType`
  filters and `DATE_ASC` / `DATE_DESC` / `STATUS` / `TYPE` / `USER_ID` sorts.
- **Stats aggregation**: extends `computeStatistics` from `§106` with one new
  `byAppVersion` field. Same `groupingBy { it }.eachCount()` stdlib idiom — O(n)
  single-pass.
- **`AdminSortMode`** enum: extends the existing enum with 2 new variants per OCP §6
  (sealed-style extension via enum entry append).

## Verification

After every source commit (steps 2-4):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile gate.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS simulator arm64.

Red = stop. Investigate the failure (likely an enum-when exhaustiveness break in the
VM's sort `when (sort)` after adding new variants — fixable in same commit by adding
the missing arms).

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Build the rework debug flag, navigate to `Screen.AdminComplaintsRework`, observe:
  - App-version chip row appears between the Type filter chips and the Sort dropdown
    (only when complaints with `appVersion != null` are loaded).
  - Selecting an app-version chip filters the list to only that version (toggle off by
    re-clicking the active chip).
  - Sort dropdown shows "App version" / "App version (desc)" at the bottom.
  - "By app version" rows appear in the StatisticsCard below "By status" (when stats
    have at least one app-version bucket).
  - Per-row "v$appVersion" badge appears on rows whose complaint carries an app version.

Edge cases:
- All complaints lack `appVersion` → chip row hidden, "By app version" stats section
  hidden, per-row badge absent. No degenerate UI.
- One complaint carries `appVersion = "1.2.3"`, others null → chip row shows one chip,
  "By app version" section shows one row. Filter chip works (selecting "1.2.3" hides
  null-versioned rows; deselecting restores all).
- `appVersion` sort with mixed nulls: null entries cluster at start (ASC) / end (DESC)
  per Kotlin nullable-Comparable defaults.

## Deferrals

- **Mapper extraction** — `AdminComplaintListRepositoryImpl` + `ComplaintListRepositoryImpl`
  still duplicate `toSummary` / `toDomain` mappers. Will lift to a `:data`-internal
  `ComplaintMappers.kt` in a separate `Phase 7.x.complaint.mappers` cleanup commit.
  Not intermixed with this slice's user-visible surface change.
- **Multi-version filter selection** — chip row is single-select with toggle-off (like
  status/type). Multi-select (filter by multiple versions at once) is additive and not
  in this slice.
- **App-version range filter** — legacy doesn't ship one either; one-version-at-a-time
  matches parity.
- **>1-distinct-version gate on stats** — legacy hides the "By app version" section
  when `appVersionCounts.size == 1`. The rework foundation slice shows the section
  unconditionally when `byAppVersion.isNotEmpty()` (simpler; degenerate-but-informative
  vs. legacy's strict-gate). Polish slice `Phase 7.x.complaint.admin.statspolish` can
  add the gate if user feedback requests it.
- **Other `metadata` keys** — legacy carries `platform`, `build`, etc. Not carved out
  here; future slices can extend `ComplaintSummary` with single-typed fields per
  carve-out posture established by this slice.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: One new field on `ComplaintSummary` (one rule — "a complaint as a value");
  one new intent variant; one new state field; two new enum variants; one new state
  derivation in VM (`byAppVersion`).
- **OCP**: Sealed `AdminComplaintIntent` gains a new variant — VM `when (intent)`
  exhaustive arm forces a compile-time error to surface the new intent here.
  `AdminSortMode` enum gains two new variants — VM `when (sort)` exhaustive arm
  similarly forces handling.
- **DIP**: `:presentation` depends on `:domain` interface, not `:data` impl.
  `:data`'s mapper depends on legacy `:shared` `Complaint.metadata` — same
  strangler-fig posture as before.
- **Layer boundary**: changes touch `:domain` (1), `:data` (2 mappers), `:presentation`
  (3 files: state + intent + VM), `:ui` (1 file). Strangler-fig: zero new `:data` →
  `:shared` reaches; the existing `metadata` access is already in `:data`.
- **Banned features**: no `!!` / `Any` / `lateinit` / `Thread`. `metadata?.get("appVersion")?.toString()`
  returns `String?` — the `Any` projection happens entirely within `:data` via the
  `.toString()` cast at the boundary. The `:domain` interface sees only `String?`.
- **MVI contract**: extension — one new intent variant, two new enum variants, one
  new state field. The closed sealed contract gains additions; no signatures
  modified.
- **Strangler-fig**: ZERO new `:data` → `:shared` reaches. The existing mapper
  already touches `LegacyComplaint`; the new `appVersion` extraction is just one
  more line in the same function.
- **Load-bearing fixes preserved**: this slice does NOT touch the Coil ImageLoader,
  AVIF decoder, HighQualitySkiaImageDecoder, or any `:platform` actuals. Pure
  data-shape extension + UI filter wiring. No load-bearing risk.
