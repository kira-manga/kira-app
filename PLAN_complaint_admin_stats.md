# Phase 7.x.complaint.admin.stats — admin statistics aggregation card

## Context

§105's next-candidate block recommended **Phase 7.x.complaint.admin.stats** as the strategic next step — port the legacy admin's statistics card (legacy `composeApp/.../admin/complaint/AdminComplaintScreen.kt:619-720` `AdminStatisticsCard`) onto the rework admin Complaint dashboard. Medium-sized, self-contained slice that lifts the §102.9 stats deferral while staying narrower than versionfilter (no `:domain` widen) and bulk (no selection infrastructure).

Legacy reference: `AdminStatisticsCard(complaints: List<Complaint>)` renders:
1. Title "Complaints statistics" + bold.
2. "Total complaints: N" row.
3. "By status" header + per-status row showing a StatusChip + count (only non-zero buckets — `if (count > 0)`).
4. "By app version" header (if >1 version) + top-5 versions by descending count.

The legacy also wraps the card in an `IconButton(showStats = !showStats)` visibility toggle at the top-bar (eye icon). The rework defers the toggle to a future sibling sub-slice — always-visible card is the simpler default and keeps this slice tight.

## Approach

Pure 4-file slice (plan + State + VM + Screen). ZERO `:domain` / `:data` / Koin / nav touches. The aggregation is a pure projection of `state.all` — no new use case, no new repository method, no new effect, no new intent.

### MVI shape (additive)

- New top-level `AdminComplaintStatistics` data class in `AdminComplaintState.kt` (following the §104 sort precedent where `AdminSortMode` lives in the same file):
  ```kotlin
  data class AdminComplaintStatistics(
      val total: Int = 0,
      val byStatus: Map<ComplaintStatus, Int> = emptyMap(),
  )
  ```
- New state field `val statistics: AdminComplaintStatistics = AdminComplaintStatistics()` appended after `isSubmittingAction`.
- New private VM helper `private fun computeStatistics(list: List<ComplaintSummary>): AdminComplaintStatistics`.
- `loadList()`'s success branch updates `statistics = computeStatistics(list)` alongside `all` / `filtered`. The failure branch sets `statistics = AdminComplaintStatistics()` (default empty).
- **No new intent variant**: stats are data-driven, not user-driven. The user can't toggle anything in this slice.
- **No new effect variant**: stats render to UI, not to one-shot effects.

### Aggregation: by-status only

Legacy renders two breakdowns: by-status and by-app-version. The rework ports only by-status:

- **by-status**: `complaints.groupBy { it.status }.mapValues { it.value.size }`. All 8 `ComplaintStatus` variants are buckets; only non-zero buckets render in `:ui`.
- **by-app-version DEFERRED**: same `metadata.appVersion` blocker as the two deferred APP_VERSION sort modes (§104.9) — `:domain` `ComplaintSummary` deliberately omits the `metadata: Map<String, Any>` field per contract §6. Will ship in `Phase 7.x.complaint.admin.versionfilter` together with the third filter axis (same blocker, same lift).
- **by-type NOT ported**: the legacy card doesn't render it either. The type chip row in the existing filter section already surfaces "what types exist" — adding a by-type breakdown would be redundant.

### Client-side aggregation in `loadList`

Same posture as the §104 sort: aggregation runs over the already-loaded list, in-memory. No server-side count query, no Firestore aggregation. The complaint volume per admin's data scale (≤200 typical, ≤1000 worst-case) makes `groupBy { it.status }.mapValues { it.value.size }` (O(N)) trivially cheap.

If data scale grew beyond practical client-side aggregation (100K+ complaints), a future slice could introduce a server-side count via a new use case (`ObserveComplaintStatisticsUseCase` returning `Flow<AdminComplaintStatistics>`) without touching the MVI surface. The current slice's intent-free, data-driven shape lifts directly into either pattern.

### `:ui` card placement

Insert the new `StatisticsCard` composable as the FIRST `item` in `AdminComplaintList`'s `LazyColumn`, above `SearchAndFilterSection`. The legacy card is also above the search/filter section (legacy line 286-291). Visual parity preserved.

The card renders inside the `LazyColumn` (not above it as a fixed header) so it scrolls naturally with the list — same posture as the legacy. Tapping the card does nothing; it's pure read-only display.

### Inline labels

Hardcoded English: `"Statistics"`, `"Total complaints"`, `"By status"`, and per-status labels (already `status.name` — same posture as the existing filter chip labels). Phase 10 i18n lift will swap to `Res.string.*` lookups in one pass across legacy + rework.

### Empty-state handling

When `state.all.isEmpty()` (no complaints submitted at all), the `AdminComplaintList` is not rendered at all — the outer `Box` in `AdminComplaintScreenContent` shows the "No complaints submitted" placeholder instead. So the stats card never renders in the truly empty case.

When `state.all.isNotEmpty()` but `statistics.byStatus.values.all { it == 0 }`, that's mathematically impossible (the list has items, so at least one status bucket is non-zero). No defensive code needed.

When `statistics.total == 0` (default initial state during load), the card renders briefly with "0" — but `isLoading == true` during this window, so the outer `Box` shows the `CircularProgressIndicator`, not the list. So the user never sees the `total == 0` state in practice.

## Commit roadmap

Three commits, all under the 5-file cap:

1. **Plan commit** — `PLAN_complaint_admin_stats.md` only (1 file).

2. **Source bundle** — 3 files modified:
   - MOD `presentation/.../complaint/admin/AdminComplaintState.kt` — add `AdminComplaintStatistics` data class + `statistics` field on `AdminComplaintState`.
   - MOD `presentation/.../complaint/admin/AdminComplaintViewModel.kt` — add `computeStatistics` helper + thread it through `loadList`'s success and failure branches.
   - MOD `ui/.../complaint/admin/AdminComplaintScreen.kt` — new private `StatisticsCard` composable + `AdminComplaintList` inserts it as the first item.
   - Build gates: Android + iOS Arm64 + iOS SimulatorArm64. All must pass before close-out.

3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append §106 with strategy / layer-by-layer / MVI shape (no new intent, no new effect — data-driven from `loadList`) / strangler-fig boundary (ZERO touches) / aggregation rationale / files / build gates / deferrals / next-candidate block.
   - `SOLID_AUDIT.md` — append Phase 7.x.complaint.admin.stats entry with per-file SOLID 10-point checklists (4 files: plan + State + VM + Screen) + end-of-slice verdict + next-candidate block.

## Critical files

### New
- `PLAN_complaint_admin_stats.md` (this file)

### Modified
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/complaint/admin/AdminComplaintScreen.kt`
- `ARCHITECTURE.md` — §106
- `SOLID_AUDIT.md` — Phase 7.x.complaint.admin.stats entry

### Untouched (verify by read, not modified)
- `presentation/.../complaint/admin/AdminComplaintIntent.kt` — no new intent (stats are data-driven, not user-driven).
- `presentation/.../complaint/admin/AdminComplaintEffect.kt` — no new effect (stats render to UI, not one-shot).
- `composeApp/.../di/ComplaintAdminReworkModule.kt` — no new Koin bindings.
- `composeApp/.../navigation/routes/AdminComplaintReworkScreenRoute.kt` — no new route adapter.
- `domain/`, `data/`, `:shared` — untouched. ZERO strangler-fig boundary impact.

## Reuse

- **`kotlin.collections.groupBy` + `mapValues`** — stdlib aggregation, already used everywhere in the codebase.
- **`Card` / `Column` / `Row` / `Text`** — Compose primitives already on `:ui` classpath via `material3`.
- **`MaterialTheme.colorScheme.surfaceVariant` / `.primary`** — theme tokens already used by `AdminComplaintRow`.
- **`LocalSpacing.current`** — design tokens already imported.
- **`ComplaintStatus.entries`** — already iterated in the filter chip row.

## Verification

After the source-bundle commit (commit 2):
- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile gate.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS simulator arm64.

iOS framework linking + on-device smoke tests deferred to user's Mac. Smoke-test checklist when user is on Mac:
- Open the rework admin dashboard with ≥1 complaint.
- Verify the stats card renders above the search box with the title "Statistics", "Total complaints: N" (matching `state.all.size`), and a per-status row for every non-zero status bucket.
- Verify the per-status counts sum to the total.
- Verify the card scrolls with the list (not fixed-header).
- Verify the empty state still renders "No complaints submitted" when `state.all.isEmpty()` (no stats card visible).
- Verify the loading spinner still renders during the initial fetch (no stats card visible).
- Verify the error state still renders "Retry" inline (no stats card visible).

Edge cases:
- Single-status data (all complaints OPEN): card renders one per-status row + total. No render of empty buckets.
- All 8 statuses populated: card renders 8 per-status rows. No layout pressure (LazyColumn handles arbitrary height).
- Filter applied (e.g., status=CLOSED): the stats card's totals reflect `state.all`, NOT `state.filtered` — the card shows the FULL inventory while the filtered list is below. Matches legacy posture (legacy passes `complaints` — the full list — to `AdminStatisticsCard`, not the filtered list).

## Deferrals

- **`showStats` visibility toggle** — legacy admin has an eye-icon toggle in the top bar that hides/shows the stats card. Defer to a future `Phase 7.x.complaint.admin.statstoggle` sub-slice. The `:ui` module lacks `material.icons` (per §104.8), so a text-button affordance would need to substitute for the eye icon — workable but additive.
- **`byAppVersion` breakdown** — same `metadata.appVersion` blocker as the deferred APP_VERSION sort modes. Will ship in `Phase 7.x.complaint.admin.versionfilter` together with the third filter axis (same blocker, same lift).
- **By-type breakdown** — legacy doesn't render this; the rework follows suit. The existing filter chip row already surfaces "what types exist" so an explicit breakdown would be redundant.
- **Server-side count aggregation** — defer to a future scale-driven slice if data volume warrants. Client-side `groupBy` is trivially cheap at the current scale (≤1000 complaints).
- **i18n** — `"Statistics"`, `"Total complaints"`, `"By status"` are hardcoded English. Phase 10 lift.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: 3 source files modified, each with ≤1 logical addition (1 new ADT + 1 field / 1 new helper / 1 new composable). No multi-purpose mutations.
- **OCP**: `AdminComplaintStatistics` is a closed data class; future additions (e.g., `byAppVersion: Map<String, Int>`, `byType: Map<ComplaintType, Int>`) append nullable / defaulted fields without breaking existing readers. `AdminComplaintState` extends additively — new `statistics` field appended after `isSubmittingAction`.
- **LSP**: No new types substitute existing ones.
- **ISP**: `AdminComplaintStatistics` exposes only the fields the `:ui` card consumes (`total`, `byStatus`). No god-class.
- **DIP**: `:presentation` depends on no new types. `:ui` depends on the new value type but not on any `:platform` interface or `:data` impl.
- **Layer boundary**: ZERO `:domain` / `:data` / Koin / nav touches. Surface is `:presentation` (State + VM) + `:ui` (Screen). The strangler-fig boundary stays where §105 left it.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. `Map<ComplaintStatus, Int>` uses a `:domain` enum key + primitive Int value — no `Any`.
- **MVI contract**: AdminComplaintIntent unchanged (14 variants); AdminComplaintEffect unchanged (2 variants); AdminComplaintState widens from 11 to 12 fields. No new `when` arm in the VM's `handle(intent)` (stats are populated from `loadList`, not from intents).
- **Strangler-fig**: ZERO new `:data` → `:shared` reaches. Continues §105's "zero new boundary" posture.
- **Load-bearing fixes preserved**: this slice does NOT touch Coil ImageLoader, Reader per-request listener, decoder hints, OkHttp interceptor, or any prior load-bearing image-quality posture (admin complaint surface has no images). No load-bearing risk.
