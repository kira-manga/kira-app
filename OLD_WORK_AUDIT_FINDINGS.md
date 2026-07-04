# Old Work Audit Findings

## Summary

- **Overall status**: Mostly complete
- **Confidence**: High (all 5 audit agents complete)
- **Date/time of audit**: 2026-05-23 (Session 2, in progress)
- **Current branch**: `kmp-migration`
- **Current HEAD**: `2445e99` (`[phase-6] batch 6.1: Room -> Room KMP (entities + DAOs + database + per-platform builders + Koin)`)

Phase 0-6 deliverables all exist and verify against their claims. All referenced commit SHAs are real with the documented messages. `shared/src/commonMain` is **architecturally clean** — zero forbidden imports. Room KMP migration ports every entity / DAO / converter / migration 1-to-1, with all KMP-portability fixes inline. Build verification passes on all three required targets (`:shared:compileKotlinDesktop`, `:shared:compileKotlinIosArm64`, `:app:assembleDebug`).

Two minor doc issues found (both Low severity): one stale narrative in `library-decisions.md` (the AGP section text wasn't updated when `compileSdk` bumped from 35 → 36, though the summary table at the bottom is correct), and `migration/feature-map.md` still shows all 28 features as `not_started` despite Phase 4 moving the pure-Kotlin pieces of several. No Critical or High issues. No build-blocking findings.

The cleanup/dead-code audit was launched in background and is still running; its findings will be appended to this file once available.

## Agents Used

1. **Phase 0-5 deliverable audit agent** (Agent #1) — cross-checked claimed deliverables against actual file paths, ran `git log` to verify commit SHAs, audited Koin binding count against `SharedModule.kt`.
2. **Forbidden imports architecture audit agent** (Agent #2) — exhaustive grep across `shared/src/commonMain` for 21 categories of forbidden imports + suspicious patterns.
3. **Phase 6 Room migration audit agent** (Agent #3) — verified entity/DAO/converter/migration parity, MangaDatabase config, per-platform builders, schema export, suspend-or-Flow DAO audit.
4. **Tracking files consistency audit agent** (Agent #4) — cross-checked `progress-state.json` / `pending-work.md` / `migration-log.md` / `checkpoints.md` / `library-decisions.md` / `koin-graph-report.md` / `di-migration-report.md` / `renames.md` / `discovered-issues.md` / `module-map.md` / `feature-map.md`.
5. **Cleanup / dead code / stale file agent** (Agent #5) — completed; findings appended below under "Findings — Agent #5".

## Findings

### Critical

None.

### High

- **H-1** — Stale `me.manga.yami.composeapp` package in `composeApp/build.gradle.kts` line 100 (see Agent #5 section below). Would surface in generated Compose-Resources code as `me.manga.yami.composeapp.generated.resources` instead of `me.manga.kira.composeapp.generated.resources`.

### Medium

- **M-1 — Swapped package names in `module-map.md`** (line 43): the row for the DI package reads `me.manga.kira.di.* (Hilt) | Replaced by me.manga.yami.di.* Koin modules`. Source/target packages appear swapped — source's Hilt package is `me.manga.yami.di.*` and the new KMP Koin package landed at `me.manga.kira.di.*` (per batch 4.0 consolidation and the actual file at `shared/src/commonMain/kotlin/me/manga/yamiapk/di/SharedModule.kt`). Likely written before the Phase 4 package-rename decision.

### Low

- **L-1 — `library-decisions.md` AGP section narrative is stale.** Line 31 still says "keep `minSdk=26`, `targetSdk=35`, `compileSdk=35` exactly as source." The summary table at line ~393 correctly records the Session 2 bump to `compileSdk=36`. Same file, two contradictory sources of truth.
- **L-2 — `project-inventory.md` lacks a "this is a source snapshot, not current KMP state" disclaimer.** Lines 21-24 record the source's `compileSdk=35`, Kotlin `2.0.21`, AGP `8.9.3`, Gradle `9.0-milestone-1` — all are pre-migration values. A future reader could mistake them for the current KMP project state (which is locked at Kotlin `2.3.21`, AGP `8.13.0`, Gradle `8.13`, compileSdk `36`).
- **L-3 — `migration/feature-map.md` not updated since Phase 1.** All 28 feature rows still show **Status = `not_started`**, but Phase 4 moved pure-Kotlin pieces of several features (e.g. feature #20 Complaint — model + repo interface + 5 use cases are in commonMain; feature #22 Sources — 188 per-source DTOs are in commonMain; feature #21 Admin — `dgfhldghlghg.kt` placeholder moved). The table should reflect partial-migration status.
- **L-4 — `pending-work.md` heading drift.** Line 34 has `### Phase 5 (Hilt → Koin) — next session`, but Phase 5 is already marked `scaffolded` in `progress-state.json` and the RESUME-HERE block at the top correctly points to Phase 6. The "next session" wording should be re-labelled (e.g. "remaining batches 5.2/5.3/5.4/5.5").
- **L-5 — `library-decisions.md` summary table missing `atomicfu`.** Every other locked version is in the summary table (line ~393), but `kotlinx-atomicfu = 0.27.0` (added in Phase 4 batch 4.5 and present in `progress-state.json:106`) is missing from the table.
- **L-6 — Phase 4 file count off-by-one.** `progress-state.json:33` claims 237 files moved; git's per-batch net adds sum to 238. Difference is `Greeting.kt` (Phase 4 batch 4.0 package-consolidation placeholder) being counted as scaffolding in one place and as a port in the other. Not a correctness issue — just a +1 reconciliation note. Total `.kt` files in `shared/src/commonMain/kotlin/me/manga/yamiapk/` at time of audit: 267 (= 238 batch adds + 3 Phase 5 DI files + 26 pre-batch-4.0 Phase 3 stubs + Phase 6 batch 6.1 adds — also reconciled when Room files land).
- **L-7 — Tracking docs don't enumerate the two CLOSE commits.** `checkpoints.md` references the 7 Phase-4 batch commits and the Phase-5 commit, but NOT `864f241` (`[phase-4] CLOSE`) or `5df0e82` (`[phase-5] CLOSE`). These are the tracking-doc closing commits — they can't reference themselves, but the previous checkpoint entry could (won't fix retroactively; record for future phases to include).
- **L-8 — `checkpoints.md` CHECKPOINT 3 has stale "(next commit)" placeholder.** Line 74 says `Commit: ... (next commit)` instead of the actual scaffolding commit SHA `d1572ef`. CHECKPOINT 4 captures the verify commit `1648ee5` cleanly.

## Completion Matrix

| Claim | Evidence | Status | Notes |
|---|---|---|---|
| Phase 0: project-inventory.md + initial tracking files committed | `ab9989e` exists; files present | COMPLETE | |
| Phase 1: project-graph.{md,json}, module-map, feature-map, android-only-deps, kmp-migration-plan | `585957f` exists; all 6 files present | COMPLETE | feature-map.md statuses are stale (L-3) |
| Phase 2: library-decisions, dependency-replacement, renames, discovered-issues | `f5598db` exists; all 4 files present | COMPLETE | AGP narrative + atomicfu missing from summary (L-1, L-5) |
| Phase 3 scaffold: gradle/libs.versions.toml + per-module build files + stub sources + manifest | `d1572ef` exists; 162 files | COMPLETE | CHECKPOINT 3 line 74 has stale "(next commit)" placeholder (L-8) |
| Phase 3 verification: all three builds pass + Kotlin/AGP/KSP/lifecycle/nav/Gradle fixes | `1648ee5` exists; verified `BUILD SUCCESSFUL` | COMPLETE | |
| Phase 4 batch 4.0+4.1 (package consolidation + 3 pure models) | `cf9fe55`; 15 files | COMPLETE | |
| Phase 4 batch 4.2 (6 models w/ Parcelize+LocalDate ports) | `3c9b20c`; 6 files | COMPLETE | |
| Phase 4 batch 4.3 (4 interfaces + sealed states) | `b023930`; 4 files | COMPLETE | |
| Phase 4 batch 4.4 (complaint feature + 2 core; 13 files) | `0dbf0d5`; 13 files | COMPLETE | |
| Phase 4 batch 4.5 (20 pure files + atomicfu) | `da0e49b`; 20 files + 1 dep | COMPLETE | atomicfu missing from library-decisions.md summary (L-5) |
| Phase 4 batch 4.6 (188 per-source DTOs bulk-ported) | `7833dc3`; 188 files; 2 deferred (homeV2.kt + Search.kt); GreenShitModels.kt patched | COMPLETE | |
| Phase 4 batch 4.7 (3 small files + renames resolved) | `84ef336`; 3 files | COMPLETE | |
| Phase 4 total = 237 files moved | git net-add count = 238 (Greeting.kt) | COMPLETE | Off-by-one (L-6) |
| Phase 5 Koin scaffold + 5 complaint use case bindings | `cb44274`; 9 new files in di/; SharedModule.kt has exactly 5 `factory { }` blocks | COMPLETE | |
| Phase 5 di-migration-report.md + koin-graph-report.md | both present | COMPLETE | |
| Phase 6 Room → Room KMP: 6 entities + 5 converters + 7 migrations + 8 DAOs + database + 3 platform builders + Koin wiring | `2445e99`; 32 new files; Room KSP succeeds; `shared/schemas/8.json` generated | COMPLETE | Audit Agent #3 confirmed entity/DAO/converter/migration parity; schema export works; suspend-or-Flow audit clean on all 9 DAOs |
| All forbidden imports absent from commonMain | Agent #2 exhaustive grep — 21 categories, 0 hits | COMPLETE | All `System.currentTimeMillis()` / `Parcelable` / etc. mentions in commonMain are inside migration-note comments only |
| All three required builds passing | `:shared:compileKotlinDesktop` ✓ 38s, `:shared:compileKotlinIosArm64` ✓ 37s, `:app:assembleDebug` ✓ 28s | COMPLETE | |
| All deferred files documented (Phase 7/8/9/10 etc.) | `progress-state.json` `phase_4_summary.deferred_to_later_phases` + `pending-work.md` "What remains for later phases" | COMPLETE | |

## Commands Run

| Command | Result | Important output |
|---|---|---|
| `git log --oneline -20` | OK | All 12 referenced commit SHAs (Phases 0-6 + tracking) present with documented messages |
| `find shared/src/commonMain/kotlin -name "*.kt"` (in audit) | 267 files | Reconciled to 238 Phase-4 batch adds + 3 Phase-5 DI + 26 Phase-3 stubs |
| `gradlew.bat :shared:compileKotlinDesktop` (Phase 6 verify) | BUILD SUCCESSFUL in 38s | All Room KSP succeeds |
| `gradlew.bat :shared:compileKotlinIosArm64` (Phase 6 verify) | BUILD SUCCESSFUL in 37s | Kotlin/Native + Room KMP iOS klibs resolved |
| `gradlew.bat :app:assembleDebug` (Phase 6 verify, background) | BUILD SUCCESSFUL in 28s (exit 0) | Android compileSdk 36 + Room compiles + APK packaged |
| Multi-grep for forbidden imports across commonMain (Agent #2) | 0 hits across all 21 categories | Clean |
| Grep `JsonNames`/`ExperimentalSerializationApi` in commonMain | warnings in `GreenShitModels.kt:189` etc. | Non-fatal warnings; deferred |

## Files Inspected

### Migration tracking files (all reviewed by Agents #1 and #4)

- `migration/progress-state.json`
- `migration/pending-work.md`
- `migration/migration-log.md`
- `migration/checkpoints.md`
- `migration/library-decisions.md`
- `migration/dependency-replacement-report.md`
- `migration/renames.md`
- `migration/discovered-issues.md`
- `migration/koin-graph-report.md`
- `migration/di-migration-report.md`
- `migration/project-inventory.md`
- `migration/project-graph.md`, `migration/project-graph.json`
- `migration/module-map.md`, `migration/feature-map.md`, `migration/android-only-dependencies.md`, `migration/kmp-migration-plan.md`

### Architecture audit targets (Agent #2)

- `shared/src/commonMain/kotlin/me/manga/yamiapk/**/*.kt` — all 267 files greppable

### Phase 6 Room targets (Agent #3)

- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/entity/SavedMangaEntity.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/entity/SavedChapterEntity.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/entity/HistoryItemD.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/entity/ChapterNotification.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/entity/ChapterDownloadEntity.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/entity/SourcesEntity.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/dao/*.kt` (9 files including SavedMangaDao orphan)
- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/converter/*.kt` (5 files)
- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/Migrations.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/MangaDatabase.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/DatabaseBuilder.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/MangaDatabaseFactory.kt`
- `shared/src/androidMain/kotlin/me/manga/yamiapk/data/local/DatabaseBuilder.android.kt`
- `shared/src/iosMain/kotlin/me/manga/yamiapk/data/local/DatabaseBuilder.ios.kt`
- `shared/src/desktopMain/kotlin/me/manga/yamiapk/data/local/DatabaseBuilder.desktop.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/library/data/SavedMangaWithMetrics.kt`
- `shared/schemas/me.manga.kira.data.local.MangaDatabase/8.json`

### Source-of-truth comparison (Agent #3)

- `D:\yami manga\yami-manga-apk-main\app\src\main\java\me\manga\yami\data\local\**` (read-only reference)

## Recommended Fix Plan

Prioritized smallest safe changes first. All are documentation-only — no source code touched.

1. **Fix M-1** — `module-map.md` line 43 swapped packages. Change source/target so source is `me.manga.yami.di.*` (Hilt source) and target is `me.manga.kira.di.*` (KMP Koin destination). 1-line edit.
2. **Fix L-1** — `library-decisions.md` AGP narrative line 31. Update "compileSdk=35" → "compileSdk=36 (bumped from source's 35 per Session 2)". 1-line edit.
3. **Fix L-2** — `project-inventory.md` add a preface note at top: "This file is the as-of-Phase-0 snapshot of the **source** project (`yami-manga-apk-main`). For the current KMP project's locked toolchain versions see `library-decisions.md` and `progress-state.json:locked_versions`."
4. **Fix L-3** — `feature-map.md` update Status column for features that have at least pure-Kotlin pieces moved: #20 Complaint → `partially_migrated`, #22 Sources → `partially_migrated`, etc. Approximate scope: 10-15 row updates.
5. **Fix L-4** — `pending-work.md` line 34 heading. Change `### Phase 5 (Hilt → Koin) — next session` to `### Phase 5 (Hilt → Koin) — remaining batches`.
6. **Fix L-5** — `library-decisions.md` summary table. Add row `| atomicfu | org.jetbrains.kotlinx:atomicfu | 0.27.0 |`.
7. **Fix L-6** — `progress-state.json` and `pending-work.md` references to "237 files moved". Either update to 238 (git-accurate net-adds) or add a footnote that the count excludes the `Greeting.kt` placeholder from batch 4.0.
8. **Fix L-7, L-8** — checkpoints.md cleanup. Add `864f241` and `5df0e82` SHAs to the relevant CHECKPOINT entries. Update CHECKPOINT 3's "(next commit)" placeholder to `d1572ef`.
9. **Fix H-1 (priority — fix soon)** — `composeApp/build.gradle.kts:100`: change `packageOfResClass = "me.manga.yami.composeapp.generated.resources"` → `"me.manga.kira.composeapp.generated.resources"`. One-line edit. Will avoid an inconsistent generated-code import path once Phase 10 runs the Compose Resources generator.
10. **Fix M-2** — three undocumented empty-class placeholders. Add migration-note comments to `asdavxc.kt`, `acxcx.kt`, `Greeting.kt`, and add renames.md rows #17, #18, #19. OR: delete `Greeting.kt` (it self-admits to being removable post-Phase-4) and add the other two to renames.md.

None of these block Phase 7 (Retrofit → Ktor) or later. They can be batched into a single docs-cleanup commit when convenient. Recommend fixing **H-1 immediately** (one-line change, prevents a generated-code package drift surfacing later) and folding the rest into the Phase 7 close commit.

## Findings — Agent #5 (Cleanup / dead code / stale files)

### High (one real regression that would surface in generated code)

- **H-1 — Stale `me.manga.yami.composeapp` package in `composeApp/build.gradle.kts` line 100.**
  Currently set to `packageOfResClass = "me.manga.yami.composeapp.generated.resources"`. Phase 4 batch 4.0 consolidated all packages from `me.manga.yami.*` → `me.manga.kira.*`, but this Compose Resources package config slipped through. Compose Multiplatform's resource generator will emit accessors under `me.manga.yami.composeapp.generated.resources` instead of the intended `me.manga.kira.composeapp.generated.resources`, creating a future import inconsistency. One-line fix.

### Medium

- **M-2 — Three undocumented empty-class placeholders match the `asas.kt`/`dgfhldghlghg.kt`/`af.kt` pattern but aren't tracked in `renames.md`.** ✅ **RESOLVED in Phase 9.x.placeholder.retire (commits `2646c87` + close-out).** All three undocumented files (asdavxc.kt, acxcx.kt ×2, Greeting.kt) plus the two already-tracked siblings (dgfhldghlghg.kt, af.kt) were deleted in one slice — 3-pass grep confirmed zero Kotlin callers; renames.md items #5/#6 marked `retired`. See ARCHITECTURE.md §200 for audit + grep evidence + build-gate matrix. Prior-state notes (kept for history):
  - `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/ar/mangamello/models/asdavxc.kt` — `class asdavxc {}` with no migration-note header. Same pattern, missing documentation.
  - `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/es/manhwaweb/models/acxcx.kt` — bare `package` line + no class declarations at all. Compiles but is functionally dead.
  - `shared/src/commonMain/kotlin/me/manga/yamiapk/Greeting.kt` — `internal class Greeting` returning `"Hello from yami-kmp shared/commonMain"`. Phase 4 batch 4.0 left this in from the initial KMP scaffolding stub; the comment header in the file admits "Will be removed once real shared classes are migrated in Phase 4+." — Phase 4 is now complete, so it's safe to either delete or add a renames.md entry.
  - These appeared in source (`asdavxc.kt`/`acxcx.kt`) under the same gibberish-name pattern that's documented for `dgfhldghlghg.kt`/`af.kt`. Apply the same preservation-with-comment policy or add new `renames.md` rows.

### Low / Confirmed-clean

- TODOs/FIXMEs: only 2 instances (`CrashActivity.kt:13`, `DownloadCancelReceiver.kt:13`); both are intentional Phase 4/10 stubs documented in their file KDoc. None in `shared/src/commonMain`.
- Debug logs in shared code: **zero** `println(` or `Log.*` calls in `shared/src/commonMain`.
- Hardcoded absolute paths in source: **none**. No `D:\`, `C:\`, `/Users/`, `/home/` literals in any `.kt` file under `shared/src` or `app/src`.
- Migration tmp files: **none**. No `.tmp_*`, `~$*`, `*.swp`, `*.swo`, `*.bak`, `*.orig`, `.DS_Store`, `Thumbs.db` files anywhere outside `.git/` and `build/`.
- Duplicate file names: only one `ChapterItem.kt` (in `domain/model/`). All duplicate basenames in `sources_repositry/<lang>/<source>/models/...` are in distinct subpackages — no FQN collision.
- Empty directories: **none** under any source set.
- `me.manga.yami` package references (without `apk`): **only** the Compose Resources package config (H-1 above) and Phase 3 historical entries in append-only migration-log.md. All `.kt` files use `me.manga.kira.*` correctly.
- Git status: clean — only Phase 6 in-progress changes (now committed as `2445e99`).

### Action items added to fix plan (priority order)

Inserted into "Recommended Fix Plan" below.

---

(End of cleanup audit. All five audit agents complete.)
