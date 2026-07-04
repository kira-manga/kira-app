# Discovered Issues

> Per Section 27 of `MIGRATION_PROMPT.md`. Bugs and inconsistencies discovered in the source project. **Do not silently fix.** Document here; preserve migration behavior unless the user explicitly approves a fix.

---

## Findings from Phase 0 / Phase 1 inventory

### I-1. Hilt version mismatch

- **Where**: root `build.gradle.kts` declares `id("com.google.dagger.hilt.android") version "2.57.2"` (root plugin); `app/build.gradle.kts` adds `implementation("com.google.dagger:hilt-android:2.57.1")` and `kapt("com.google.dagger:hilt-android-compiler:2.57.1")`.
- **Impact in source**: Probably tolerable (both 2.57.x); irrelevant after Phase 5 (Hilt removed).
- **Migration action**: Hilt is being replaced entirely in Phase 5. No fix needed.

### I-2. Compose BoM mismatch

- **Where**: `libs.versions.toml` declares `composeBom = "2025.10.01"` but `app/build.gradle.kts` then overrides with `platform("androidx.compose:compose-bom:2025.06.01")` for `composeBom` local val.
- **Impact in source**: Some Compose artifacts use the newer BoM (via `libs.androidx.compose.bom`) while others use the older (via the local `composeBom`). Possibly causes inconsistent transitive deps.
- **Migration action**: standardize on the newer `2025.10.01` in the KMP catalog. Compose Multiplatform 1.11.0 handles this.

### I-3. Navigation version mismatch

- **Where**: `app/build.gradle.kts` adds `implementation("androidx.navigation:navigation-compose:2.8.9")` and `ksp("androidx.navigation:navigation-safe-args-generator:2.5.3")`. Safe-args 2.5.3 is significantly older than Nav 2.8.9.
- **Impact in source**: safe-args is XML-only and unused — likely a stale dependency from legacy code.
- **Migration action**: drop safe-args entirely in Phase 3.

### I-4. Activity Compose version mismatch

- **Where**: `libs.versions.toml` has `activityCompose = "1.10.1"` but `app/build.gradle.kts` has `implementation("androidx.activity:activity-compose:1.10.0")` and `implementation("androidx.activity:activity-ktx:1.10.1")`.
- **Impact in source**: minor; both 1.10.x.
- **Migration action**: standardize on `1.10.1` in KMP catalog.

### I-5. RC-tagged dependency

- **Where**: `implementation("androidx.compose.ui:ui-viewbinding:1.6.0-rc01")`.
- **Impact in source**: RC version in production.
- **Migration action**: drop entirely if Phase 4 audit confirms ViewBinding is unused by composables. Otherwise upgrade to latest stable `androidx.compose.ui:ui-viewbinding`.

### I-6. Gradle wrapper using milestone

- **Where**: `gradle/wrapper/gradle-wrapper.properties` → `gradle-9.0-milestone-1-bin.zip`.
- **Impact in source**: experimental Gradle. May cause KMP plugin compatibility issues.
- **Migration action**: replace with Gradle 8.13 stable in Phase 3.

### I-7. `dex/AasqPlugin.kt` source path hardcoded

- **Where**: `app/build.gradle.kts` lines 271-272 reference an absolute Windows path: `E:/profaction/yami manga last 8-8/yami---manga/app/src/main/java/me/manga/yami/sources_repositry/data/MangaSource.kt`. The `buildDexPlugin` task uses `src/main/java/me/manga/yami/dex/AasqPlugin.kt` (relative — fine).
- **Impact in source**: the hardcoded `inputKtFile` would fail outside that developer's machine, but it's only used by commented-out tasks. The active `buildDexPlugin` uses the relative path correctly.
- **Migration action**: keep `buildDexPlugin` with the relative path. Drop the hardcoded `inputKtFile` reference. Documented in Phase 3 build file rewrite.

### I-8. Typos in DAO and ViewModel filenames

- **Where**: `LibraryDeo.kt`, `StatisticsDeo.kt`, `MangaDerailsViewModel.kt`, `ConnectivityMudule.kt`, `viewmodes/` directory, `componants/` directory, etc.
- **Impact in source**: cosmetic.
- **Migration action**: preserved per renames policy (`renames.md`).

### I-9. Mid-refactor files

- **Where**: `presentation/features/download/ui/test2/DownloadViewModelv2.kt`, `DilarV2Repository.kt`, `BatotoEnRepositoryv2.kt`, etc.
- **Impact in source**: dual implementations or partial refactors.
- **Migration action**: Phase 4 audit each `v2`/`V2`/`test2` file vs its sibling non-`v2` file (if any) — if duplicates, document and preserve; do not delete.

### I-10. Gibberish-named files

- **Where**: `admin/dgfhldghlghg.kt`, `data/remote/af.kt`, `google_play_cores/ss.kt`.
- **Impact in source**: unknown.
- **Migration action**: Phase 4 read each file and determine real intent. Document in `renames.md` whether to keep or propose rename to user.

### I-11. `MyApp` not registered with `androidx.startup`

- **Where**: `AndroidManifest.xml` removes `androidx.startup.InitializationProvider` via `tools:node="remove"`. So Hilt-Work's auto-init via androidx.startup is disabled; manual init is expected.
- **Impact in source**: manual WorkManager init expected in `MyApp.onCreate()`.
- **Migration action**: preserve the manual init path. KoinWorkerFactory will be set up in `MyApp.onCreate()`.

### I-12. AdMob test IDs in debug

- **Where**: `app/build.gradle.kts` debug block sets `REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"` (the Google sample/test ID).
- **Impact in source**: correct behavior — debug uses test ads, release uses real ads from `gradle.properties`.
- **Migration action**: preserve.

### I-13. `exportSchema = false` on Room database

- **Where**: `MangaDatabase.kt` line 36: `exportSchema = false`.
- **Impact in source**: no schema history committed.
- **Migration action**: **flip to `exportSchema = true`** in Phase 6 (required by `MIGRATION_PROMPT.md` Section 37). Document the schema baseline (version 8) in the migration report. Schema directory: `shared/schemas/`.

### I-14. Naming: `MangaDerailsViewModel` → flagged for user review

- **Where**: `presentation/features/details/ui/viewmodel/MangaDerailsViewModel.kt`.
- **Impact**: typo in class name + filename. Public to navigation routes (`MangaDetailsRoute`).
- **Migration action**: preserved (see `renames.md`); user can approve a follow-up rename PR.

### I-15. Two `Migration_4_5` vs `MIGRATION_X_Y` naming style

- **Where**: `data/local/Migrations.kt`. `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`, `Migration_4_5`, `MIGRATION_5_6`, `MIGRATION_6_7`, `MIGRATION_7_8`. The 4_5 one is `Migration_4_5` (lowercased + underscore), all others `MIGRATION_X_Y`.
- **Impact**: cosmetic; runtime-equivalent.
- **Migration action**: preserve verbatim.

---

## Issues recorded for the user to decide post-migration

1. **DEX plugin runtime loading**: Should iOS/Desktop ever support source plugins? Currently the design is "Android only, statically registered sources only elsewhere." If the user wants parity, options are (a) bytecode loading on Desktop via a custom class loader, (b) shipping all sources in the binary, (c) a future plugin format. Out of scope for this migration.

2. **Firebase iOS SDK integration**: Not done in this migration. iOS will get a `NoopAnalytics` / `NoopCrashReporter` until the user opts in via CocoaPods.

3. **WebView on iOS/Desktop**: Currently a stub. iOS would use `WKWebView`; Desktop would use JCEF. Out of scope.

4. **Telephoto KMP equivalent**: If `net.engawapg.lib:zoomable` 2.8.0 turns out to not be KMP-compatible in Phase 10, the reader's zoom-and-pan on non-Android uses a custom `Modifier.transformable` implementation.
