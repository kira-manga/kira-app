# :core/ tier — §253 postscript sweep status

**TIER FULLY SWEPT** at HEAD cluster143 (pending commit). Closes wave-26's :core arc.

Tier survey across `core/src/commonMain/kotlin/me/manga/yamiapk/core/` — 7 files total.

## Final count

- **Pre-wave-26 (entering 2026-05-28)**: 0 swept, 7 unswept.
- **Post-cluster142**: 4 swept, 3 unswept.
- **Post-cluster143 (current)**: 7/7 SWEPT — :core arc of wave-26 closed.

## Wave-26 cluster breakdown

### cluster142 — 4-leaf contract-foundation + SPI batch (Task #598, committed HEAD `ae71a87`)

Coherent thematic grouping: contract-prescribed boundary types + the two cross-cutting SPIs.

1. **`AppResult.kt`** — 146th sibling, opens wave-26 + opens cluster142. 2 classifications STAND. Notable PARTIALLY-FULFILLED-FORECAST on §10/§19 "kotlin.Result forbidden across boundaries" — strangler-fig tier (~30 :domain kotlin.Result return sites: complaint + feedback + downloads + settings) opted out for legacy parity; rework-native tier holds the line.
2. **`AppError.kt`** — 147th sibling. 2 classifications STAND. 29 AppError mappings verified across :data rework-native repository impls (ChapterPagesRepositoryImpl: 13 + MangaDetailsRepositoryImpl: 14 + LibraryRepositoryImpl: 2). Validation/Auth/Platform/Cancelled subclasses declared-but-unused (OCP-preserving).
3. **`Logger.kt`** — 148th sibling. 2 classifications STAND. FORECAST-NOT-YET-FULFILLED on Crashlytics fan-out adapter; CrashReporter exists as separate :platform SPI invoked directly rather than fanned through Logger.
4. **`FeatureFlagProvider.kt`** — 149th sibling, closes cluster142. 2 classifications STAND. FORECAST-NOT-YET-FULFILLED — no domain/presentation consumers; rework slices chose direct DataStore over feature-flag dispatch.

### cluster143 — 3-leaf dispatchers + heap closing batch (Task #599, pending commit at current HEAD)

Tier-closing trio: the dispatcher contract pair + the device-tier enum.

5. **`DispatcherProvider.kt`** — 150th sibling, opens cluster143. 2 classifications STAND. PARTIALLY-FULFILLED-FORECAST on "no platform-specific actuals needed" — coroutines-1.9.0 forced ONE dispatcher (io) into expect/actual splitting via platformIoDispatcher; other four remain platform-agnostic per original prediction.
6. **`IoDispatcher.kt`** — 151st sibling. 1 classification STANDS. FULFILLED-PREDICTION — 3 actuals (Android+iOS+Desktop) shipped; JVM returns Dispatchers.IO, iOS returns Dispatchers.Default per the Native-internal workaround prose.
7. **`DeviceTier.kt`** — 152nd sibling, **CLOSES cluster143 + CLOSES :core tier at 7/7 FULLY SWEPT + CLOSES wave-26's :core arc**. 2 classifications STAND. Notable STALE-PROSE-AS-OF-TASK-#187: the "Until that probe slice ships, call sites still pull from legacy :shared API" claim is now stale — DeviceTierProbe DID land (Phase 5.w.6.5, 3 actuals at platform/src/{android,ios,desktop}Main/) but the predicted call-site migration never executed because rework :data never needed a runtime DeviceTier. Legacy :shared consumers (OptimizedCbzManager + ProMangaImageCombiner) still use the legacy detectDeviceTier() — they're scheduled for retirement themselves.

## Build-gate state

Both cluster commits passed the triple-gate (Android + iosArm64 + iosSimulatorArm64) before commit. Only pre-existing UI-layer deprecation warnings surfaced — none postscript-induced.

## Sibling index continuity

The cluster57+ sweep index advanced from prior tier (`:domain/repository/` closed at 145th sibling per cluster141 commit). Wave-26's :core arc spans 146th → 152nd. Next cluster144+ cycle target candidates (per Rule 3: audit-trail surfaces next-closest productive target):

- `:platform` module surface survey (DeviceTierProbe actuals, CrashReporter, AppFileSystem, ToastShower, etc.)
- `:data` cumulative postscript review (rework-native repository impls already swept; any net-new files since clusters 23-25?)
- Return to `:presentation` / `:ui` for any partial-sweep follow-up

The live status doc (`AUDIT_TRAIL_LIVE_STATUS.md` if extant) will identify the highest-leverage next cycle.
