# Architecture Baseline — Pre-Rework Snapshot

> Frozen description of the codebase at commit `98bf8ed` on branch `architecture-rework`.
> This document is the source of truth for "what worked before the rework". Every refactor must
> preserve every feature, entry point, side effect, DAO, and load-bearing fix listed here.
>
> Companion docs (already in `migration/`): `feature-map.md` (full feature inventory),
> `expect-actual-report.md`, `koin-graph-report.md`, `database-migration-report.md`.
> This file links to them rather than duplicating.

---

## 1. Build Configuration (from `gradle/libs.versions.toml`)

| Component | Version | Notes |
|---|---|---|
| Kotlin | 2.3.21 | K2 compiler |
| AGP | 8.13.0 | |
| KSP | 2.3.8 | For Room |
| Compose Multiplatform | 1.11.0 | JetBrains build |
| Coil | 3.4.0 | KMP, stock SkiaImageDecoder overridden — see §7 |
| Room KMP | 2.8.4 | Bundled SQLite driver 2.5.2, schema export ON |
| Ktor | 3.4.3 | OkHttp (Android), Darwin (iOS), CIO (Desktop) |
| Koin | 4.2.0 | 145 total bindings (52 common + 31 × 3 platforms) |
| Lifecycle | 2.10.0 | |
| Navigation Compose | 2.9.2 | `org.jetbrains.androidx.navigation` (NOT Google's) |
| Coroutines | 1.9.0 | |
| kotlinx.serialization | 1.11.0 | |
| kotlinx.datetime | 0.8.0 | |
| atomicfu | 0.27.0 | |
| multiplatform-settings | 1.3.0 | |
| Kermit | 2.0.4 | Logging |
| Telephoto | 0.16.0 | Android-only — `.aar`, no KMP klibs (verified Maven Central 2026-05-25) |

**Platform targets:** Android (minSdk per app), iosArm64, iosSimulatorArm64, Desktop JVM (JDK 17 toolchain).

---

## 2. Top-Level Module Layout

```
yami-kmp/
├── composeApp/           # Application + UI/feature layer (presentation)
│   └── src/
│       ├── commonMain/   # App.kt, navigation, theme, crash, all features under presentation/features/
│       ├── androidMain/  # Android entry, AdMob, Firebase, Google Play, Work*, WebView host
│       ├── iosMain/      # iOS entry + platform glue
│       ├── desktopMain/  # Desktop main() + JVM platform glue
│       └── …MainTest/    # (mostly empty / placeholders)
└── shared/               # Domain + data + core platform abstractions
    └── src/
        ├── commonMain/   # data/, domain/, core/, di/, sources_repositry/, ad_mob/, admin/
        ├── androidMain/  # Room Android driver, OkHttp NetworkFetcher, AVIF decoder, Android impls
        ├── iosMain/      # Darwin Ktor, iOS impls
        ├── desktopMain/  # CIO Ktor, Desktop impls, HighQualitySkiaImageDecoder (via nonAndroidMain)
        └── nonAndroidMain/  # Source set shared by iosMain+desktopMain (Skia decoder lives here)
```

**Cross-module dependency**: `composeApp` depends on `shared`. `shared` has no upward deps.

---

## 3. Package Boundaries (current, pre-rework)

### `composeApp/src/commonMain/kotlin/me/manga/yamiapk/`
- `App.kt` (544 lines) — root composable, `MainScreen`, `AppNavHost`, singleton ImageLoader factory wired here.
- `admin/` — admin API test screen.
- `core/` — composeApp-side core helpers (lean).
- `crash/` — crash UI overlay (consumes `core/crash` from shared).
- `navigation/` — `Screen` sealed class with all routes; nav-graph extension functions.
- `presentation/`
  - `common/componants/` — reusable UI; **`images/` contains the load-bearing image-quality glue** (see §7).
  - `features/<feature>/ui/` — each feature owns its composables + `*ViewModel.kt`.

### `shared/src/commonMain/kotlin/me/manga/yamiapk/`
- `core/` — platform abstractions (ads, analytics, cbz, concurrency, consent, crash, files, image, jobs, locale, network_cache, network_connectivity, notification, platform, progress, push, remote, review, states, storage, update, util).
- `data/`
  - `local/` — Room: `MangaDatabase`, `MangaDatabaseFactory`, `DatabaseBuilder`, `Migrations`, `converter/`, `dao/`, `entity/`, `util/`.
  - `remote/` — Ktor `HttpClient` setup + retrofittable services.
- `domain/`
  - `auth/`, `device/`, `model/`, `repos/MangaRepository.kt`, `service/FileService.kt`.
- `di/` — `KoinInitializer`, `SharedModule`, `PlatformModule` (expect), `sources/` per-source bindings.
- `sources_repositry/` — `BaseMangaRepository`, `EmptyMangaRepository`, per-language subpackages `ar`, `en`, `es`, `fr`, `in`, `it`, `pt`, `ru`, `tr` + `common/` + `data/`.
- `ad_mob/`, `admin/`, `BrowserHeadersInterceptor.kt`.

**Cross-feature dependencies present today** (must be respected by rework):
- `features/reader/` ↔ `features/details/` share `SharedChaptersViewModel` (NavHost-scoped, declared in `App.kt`).
- All features → `core/storage/StorageManager` + `domain/repos/MangaRepository` via Koin.
- `features/download/` → `SharedDownloadViewModel`/`DownloadViewModelv2` (NavHost-scoped in `App.kt`).
- `features/home/` consumes per-source repositories registered by `sources_repositry/`.

---

## 4. Feature Inventory — Entry Points

Authoritative cross-reference: `migration/feature-map.md` (28-feature catalog). The list below
captures the entry composable and primary ViewModel per feature; the full repository/DAO/network
chain per feature lives in `feature-map.md`.

| Feature | Route (Screen.*) | Entry Composable | ViewModel | Notes |
|---|---|---|---|---|
| App shell | n/a | `App` → `MainScreen` | n/a | Singleton `ImageLoader` factory wired here |
| Onboarding (welcome) | `Welcome` | `WelcomeScreen` | `OnboardingViewModel` | First-launch flag drives start dest |
| Onboarding (theme) | `Theme` | `ThemeSelectionScreen` | shared OnboardingVM | |
| Onboarding (sources) | `Sources` | `SourcesScreen` | shared OnboardingVM | |
| Home | `Home` | `HomeScreen` | `HomeViewModel` | Per-source manga listing |
| Library | `Library` | `LibraryScreen` | `LibraryViewModel` | Local saved manga |
| Library details | `LibraryMangaDetails` | `LibraryMangaDetailsScreen` | `LibraryDetailsViewModel` | |
| Details | `MangaDetails` | `MangaDetailsScreen` | `MangaDetailsViewModel` | Plus `SharedChaptersViewModel` (NavHost-scoped) |
| Reader | `ChapterImagesFragment` | `ChapterImagesScreen` | `ReaderViewModel` | Uses `SharedChaptersViewModel`; image pipeline in §7 |
| History | `History` | `HistoryScreen` | `HistoryViewModel` | |
| Downloads | `DownloadsScreen` | `DownloadsScreen` | `DownloadViewModelv2` (NavHost-scoped) | CBZ archive build (Android) |
| Notifications | `Updates` | `UpdatesScreen` | `NotificationsViewModel` | |
| Settings | `Setting` | `SettingsScreen` | `SettingsViewModel` | |
| Repo settings | `RepoSettings` | `RepoSettingsScreen` | `RepoSettingsViewModel` | |
| Language | `LanguageScreen` | `LanguageScreen` | `LanguageViewModel` | |
| Statistics | `Statistics` | `StatisticsScreen` | `StatisticsViewModel` | |
| What's New | `WhatsNewScreen` | `WhatsNewScreen` | `WhatsNewViewModel` | Has its own `data/` + `model/` |
| About | `AboutScreen` | `AboutScreen` | n/a | |
| WebView | `WebView` | `WebViewScreen` (androidMain) | n/a | **Android-only** (KCEF skipped on macOS) |
| Complaint | `Complaint` | `ComplaintScreen` | `ComplaintViewModel` | Has `data/`, `model/`, `utils/` |
| Complaint admin | `ComplaintAdmin` | `ComplaintAdminScreen` | admin VM | |
| Refresh | n/a | (UI util) | — | |
| Search | — | Embedded in Library/Home | — | No separate route |
| AdMob | n/a (Android) | banner composables | — | androidMain only |
| Firebase | n/a (Android) | — | — | androidMain only |
| Google Play | n/a (Android) | review-flow | — | androidMain only |
| Work | n/a (Android) | WorkManager jobs | — | androidMain only |
| DEX plugins | n/a (Android) | source-repo binaries | — | androidMain only |

**Start destination**: `if (firstLaunch) Screen.Welcome else Screen.Library` (App.kt:259+).

**Side effects per feature** (full list in `feature-map.md`):
- Navigation: all routes flow through `AppNavHost` (`App.kt`).
- Snackbars: per-screen `SnackbarHostState`.
- Notifications: `core/notification/` push channel for updates.
- Intents/sharing: Android intent helpers in `androidMain/.../platform/`.
- Dialogs: declared inline in each feature's composable.

---

## 5. `expect`/`actual` Declarations

Authoritative cross-reference: `migration/expect-actual-report.md`.

**Implemented (4)**:
1. `platformModule(): Module` — `di/PlatformModule.kt` (common) + each platform.
2. `mangaDatabaseBuilder(): RoomDatabase.Builder<MangaDatabase>` — `data/local/DatabaseBuilder.kt`.
3. `MangaDatabaseConstructor` — typealias to Room-generated constructor per platform.
4. `createHttpClient(): HttpClient` — `data/remote/` (OkHttp/Darwin/CIO).

**Pending (~20)** — listed in `expect-actual-report.md` with proposed shapes. The rework's
`:platform` module will be where most of these settle.

---

## 6. Koin Dependency Graph

Authoritative cross-reference: `migration/koin-graph-report.md`.

- **52 common bindings**: 2 HTTP + 5 use cases + 1 store + 44 per-source repositories.
- **31 platform-specific bindings × 3 platforms** = 93 platform bindings.
- **44 per-source repositories** across 9 languages (Ar/En/Es/Fr/In/It/Pt/Ru/Tr).

**Top-level wiring**: `KoinInitializer` (commonMain) → loads `SharedModule + platformModule()`.

---

## 7. Image Pipeline — Load-Bearing Fixes (DO NOT BREAK)

These six fixes together produce the current "sharp on Android, sharp on iOS, sharp on Desktop"
image quality. The rework must preserve every one of them. See memory:
[[yami-desktop-skia-size-cap]], [[yami-image-quality-buildrequest]], [[yami-okhttp-fetcher]],
[[yami-avif-decoder]].

| # | Fix | File | Why it's load-bearing |
|---|---|---|---|
| 1 | `HighQualitySkiaImageDecoder` (CATMULL_ROM cubic resampler) registered on iOS + Desktop | `shared/src/nonAndroidMain/kotlin/me/manga/yamiapk/core/image/HighQualitySkiaImageDecoder.kt` | Replaces stock `coil3.decode.SkiaImageDecoder` which uses nearest-neighbor sampling and bakes aliased pixels at decode time. |
| 2 | `ImageDecoderRegistry.registerAll()` returns `listOf(HighQualitySkiaImageDecoder.Factory())` on iOS + Desktop | `shared/src/iosMain/.../core/image/ImageDecoderRegistry.ios.kt`, `shared/src/desktopMain/.../core/image/ImageDecoderRegistry.desktop.kt` | Coil's `ComponentRegistry` tries user-registered decoders before service-loaded defaults; this is the wiring that defeats stock Skia decoder. |
| 3 | Per-request `.maxBitmapSize(Size(Dimension.Undefined, Dimension.Undefined))` | `composeApp/src/commonMain/.../presentation/common/componants/images/SourceImageRequest.kt` | Loader-level setting does NOT propagate to per-request `Options`. Coil 3 defaults to `Size(4096,4096)` per request → collapsed width on tall webtoon pages. |
| 4 | Singleton `ImageLoader.maxBitmapSize(Size.ORIGINAL)` | `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt` | Belt-and-suspenders default for ImageRequests that don't go through `rememberSourceImageRequest`. |
| 5 | Android `applyPlatformDecoderHints` with `allowHardware(false)` + `RGB_565` | `composeApp/src/androidMain/.../presentation/common/componants/images/PlatformDecoderHints.android.kt` | Without this, Android caches ARGB_8888 bitmaps → cache fills early → Coil subsamples evicted pages → blur. |
| 6 | OkHttp `NetworkFetcher.Factory` override on Android | `composeApp/src/androidMain/.../presentation/common/componants/images/PlatformNetworkFetcher.android.kt` | ktor3 + okhttp both on Android classpath; ServiceLoader pick was non-deterministic. Explicit `OkHttpNetworkFetcherFactory()` matches native behavior. |
| 7 | AVIF decoder registration on Android | `shared/src/androidMain/.../core/image/ImageDecoderRegistry.android.kt` | Native registered `AvifDecoderCoil.Factory()`; without it, AVIF responses break. |

**`PlatformDecoderHints.kt` semantics**: `shouldConstrainImageSizeToScreen()` returns `true` on
all platforms. The earlier "iOS/Desktop should be false to dodge Skia GPU limit" rationale was
based on a wrong diagnosis (commit history shows it was reverted) — do not flip it back.

---

## 8. Database — Room KMP

Authoritative cross-reference: `migration/database-migration-report.md`.

- **6 entities**: ChapterEntity, MangaEntity, LibraryEntity, HistoryEntity, ProgressEntity, NotificationEntity (exact names per migration report).
- **8 DAOs**: under `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/dao/`.
- **5 type converters**: under `data/local/converter/`.
- **7 migrations**: schema version 8 committed; all migrations verified to round-trip.

**MUST NOT change**: column names, types, indices, foreign keys. Existing user installs depend on
the wire format being bit-identical. Any genuine schema change requires a documented Room migration
AND a tested sample-DB round-trip.

---

## 9. Networking

- **Ktor `HttpClient`** built in `shared/src/commonMain/.../data/remote/` via `createHttpClient()` (expect).
  - Android: OkHttp engine.
  - iOS: Darwin engine.
  - Desktop: CIO engine.
- **`BrowserHeadersInterceptor`** (top of `shared/src/commonMain/...`) injects per-source UA headers.
- **`CoilSourceHeaderInterceptor`** (private class in `App.kt:152-177`) does the same for image requests.

---

## 10. Cross-Cutting Core Subpackages (`shared/.../core/`)

`ads`, `analytics`, `cbz`, `concurrency`, `consent`, `crash`, `files`, `image`, `jobs`, `locale`,
`network_cache`, `network_connectivity`, `notification`, `platform`, `progress`, `push`, `remote`,
`review`, `states`, `storage`, `update`, `util`.

These are the candidate occupants of the rework's `:core` module. All have at least one `expect`
declaration and at least one platform `actual` already.

---

## 11. Source Repository System

`shared/.../sources_repositry/` contains:
- `BaseMangaRepository.kt` — common abstract repository contract.
- `EmptyMangaRepository.kt` — null-object impl.
- Per-language subpackages: `ar/`, `en/`, `es/`, `fr/`, `in/`, `it/`, `pt/`, `ru/`, `tr/`.
- `common/` and `data/` for shared scrapers and DTOs.
- 44 concrete source repositories total (Koin-bound in common).

Plugin-style DEX-loaded sources (Android-only) live in androidMain.

---

## 12. Platform-Specific Features

**Android-only** (live in `androidMain` / Android Gradle plugins):
- WebView screen (KCEF on Desktop, but route is only built on Android).
- AdMob banners and consent.
- Firebase Crashlytics integration.
- Google Play in-app review.
- WorkManager jobs (library refresh, downloads).
- DEX plugin loader for external source repositories.
- Notification permission flow.

**iOS-only**: Darwin Ktor engine; iOS-specific platform queries.

**Desktop-only**: CIO Ktor engine; JDK 17 toolchain; KCEF skipped on macOS (commit `b49df88`).

---

## 13. Known Smells / Boundary Violations to Address in Rework

(Found while walking the tree; these inform the rework's target package layout.)

1. **`composeApp` directly imports `shared/...core/storage/StorageManager`** — fine today since
   storage is platform abstraction, but the rework should funnel platform access through a
   dedicated `:platform` API surface.
2. **NavHost-scoped ViewModels** (`SharedChaptersViewModel`, `DownloadViewModelv2`) declared inside
   `App.kt` — this couples reader+details+download lifecycles to the App composable. The rework
   should move these into a feature-level shared scope or per-graph Koin scopes.
3. **`CoilSourceHeaderInterceptor` private class inside `App.kt`** — should be a top-level class
   in `presentation/common/componants/images/` (or in `:data` under remote) so other entry points
   (Android widgets, tests) can reuse it.
4. **`sources_repositry/` package name** typo — leaving the typo is fine for now since renaming
   touches Koin bindings; flagged for rework Phase 5+.
5. **No `:domain` / `:data` / `:presentation` Gradle module boundaries** — everything is in two
   modules (`composeApp`, `shared`). Dependency direction is enforced only by convention. The
   rework's Build Order (contract Section 15) splits these into real Gradle modules.
6. **`Greeting.kt`** stub left over from KMP wizard — safe to delete in a later phase.
7. **Some feature folders have inconsistent internal layout** (e.g. `whatsnew/` and `complaint/`
   have their own `data/` + `model/`; everyone else has only `ui/`). The rework should normalize
   to `ui/` + relocate state/model into shared layers.

---

## 14. Build Verification — Baseline Status (as of commit 98bf8ed)

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — passes (last verified in prior session).
- `gradlew.bat :composeApp:compileKotlinIosArm64` — passes (klib link only, full framework deferred to macOS host).
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — passes.
- Desktop run on Windows — works.
- Desktop run on macOS — works since `b49df88` (KCEF skip).

**Functionality preservation gate** for each rework phase: re-run the three Kotlin-compile commands
above. Red on any of them = stop the rework and fix before moving forward.

---

## 15. What This Document Is Not

- It is NOT the rework plan. The plan is `migration/ARCHITECTURE_REWORK_CONTRACT.md` (Section 15
  Build Order).
- It is NOT a refactor target. The package layout above is what EXISTS today; the target layout
  is defined by the contract.
- It is NOT a free pass to break things. Every entry composable, ViewModel, DAO, expect/actual,
  Koin binding, and load-bearing fix listed here MUST still work end-to-end after the rework
  completes.

---

_Last updated: 2026-05-25. Frozen baseline reflecting commit `98bf8ed`._
