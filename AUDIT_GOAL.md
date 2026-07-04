# Data Layer Audit — `D:\yami manga\yami-kmp\`

Versions confirmed: Room **2.8.4** (`gradle/libs.versions.toml:19`), sqlite-bundled **2.5.2** (line 20), Ktor **3.4.3** (line 23). Room KSP processors are registered for `kspAndroid`, `kspIosArm64`, `kspIosSimulatorArm64`, `kspDesktop` (`shared/build.gradle.kts:209-213`).

---

## Section 1 — Repository coverage

### 1a. Domain / feature repositories (concrete classes, no interface)

Bound once in `commonMain` `sharedModule` (`shared/src/commonMain/kotlin/me/manga/yamiapk/di/SharedModule.kt:233-239`); the same impl runs on all three platforms because every dependency (DAOs, `DataStoreHelper`, `AppFileSystem`, `HttpClient`, `BackgroundJobScheduler`) is fed through expect/actual facades that already have real actuals on each target.

| Repository | Class location | Android | iOS | Desktop |
|---|---|---|---|---|
| `MangaRepository` | `domain/repos/MangaRepository.kt:16` | Real | Real | Real |
| `HistoryRepository` | `presentation/features/history/domain/HistoryRepository.kt` | Real | Real | Real |
| `NotificationRepository` | `presentation/features/notifications/domain/NotificationRepository.kt` | Real | Real | Real |
| `LibraryRepository` | `presentation/features/library/domain/LibraryRepository.kt` | Real | Real | Real |
| `StatisticsRepository` | `presentation/features/statistics/domain/StatisticsRepository.kt` | Real | Real | Real |
| `SettingsRepository` | `presentation/features/settings/domain/SettingsRepository.kt` | Real | Real | Real |
| `UpdateSourcesRepository` | `presentation/features/repo_settings/domain/UpdateSourcesRepository.kt` | Real | Real | Real |
| `SourcesRepository` | `presentation/features/repo_settings/domain/SourcesRepository.kt` | Real | Real | Real |

### 1b. Polymorphic / per-target interfaces

| Interface | Android binding | iOS binding | Desktop binding |
|---|---|---|---|
| `DownloadRepository` | `DownloadRepositoryImpl` (WorkManager + Ktor + Room) at `androidMain/.../clean/DownloadRepositoryImpl.kt` — **Real**; bound at `PlatformModule.android.kt:173-181` | `CoroutineDownloadRepositoryImpl` (shared via `nonAndroidMain`) — **Real** but no CBZ archive, no background-task scheduler (iOS BGTaskScheduler not wired); bound at `PlatformModule.ios.kt:143-153` | `CoroutineDownloadRepositoryImpl` — **Real**, one-file-per-page (no PNG/WebP CBZ); bound at `PlatformModule.desktop.kt:141-151` |
| `ComplaintRepository` | `ComplaintFirestoreDataSource` (Firestore Android SDK) — **Real**; `PlatformModule.android.kt:195-196` | `ComplaintFirestoreRestDataSource` (REST API to `complaints_v2` via shared `HttpClient`/Darwin engine) — **Real**; `PlatformModule.ios.kt:165` | `ComplaintFirestoreRestDataSource` (REST API via shared `HttpClient`/CIO engine) — **Real**; `PlatformModule.desktop.kt:164` |

**`NoOpDownloadRepository`** (`shared/src/commonMain/.../clean/NoOpDownloadRepository.kt:25`) and **`NoOpComplaintRepository`** (`shared/src/commonMain/.../complaint/repository/NoOpComplaintRepository.kt:21`) still exist in `commonMain` but are **not bound anywhere** — grep confirms zero `NoOp*Repository(` constructor calls in any DI module. Both are dead code left behind from earlier phases and can be deleted.

### 1c. Per-source manga repositories (`sources_repositry/`)

55 `*Repository.kt` files in KMP vs 56 in the Android reference (`app/src/main/java/me/manga/yami/sources_repositry/`). The single missing one is `ComickRepositoryFr.kt`, and upstream it is **fully commented out** (verified — every line starts with `//`). All remaining language-variant stubs in commonMain (`ComickRepositoryAr/Es/Id/It/PtBr/Ru/Tr`, `MangaParkRepositoryIt`, `ReadComicOnlineRepository`) mirror upstream's commented-out state verbatim — they are intentional disabled placeholders, not regressions. See file headers, e.g. `sources_repositry/ar/comick_io/ComickRepositoryAr.kt:1-13` and `sources_repositry/en/readcomiconline/ReadComicOnlineRepository.kt:1-18`.

Aggregation: `Set<BaseMangaRepository>` multibinding in `SharedModule.kt:247-302` lists **43** concrete source repos for the active-repo picker. `EmptyMangaRepository` (`sources_repositry/EmptyMangaRepository.kt:20`) is upstream-equivalent fallback (returns empty state for all queries) — kept as default when no source matches.

---

## Section 2 — Room entities + DAOs

### Entities — 6 / 6 migrated (parity with Android)

KMP `data/local/entity/` (`shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/entity/`):
`SavedMangaEntity`, `SavedChapterEntity`, `HistoryItemD`, `ChapterNotification`, `ChapterDownloadEntity`, `SourcesEntity` — all six match the reference `app/src/main/java/me/manga/yami/data/local/entity/` set.

### DAOs — 8 wired, 1 orphan (matches upstream)

`MangaDatabase` (`shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/MangaDatabase.kt:35-68`) exposes 8 DAOs: `historyDao, libraryDeo, notificationDao, statisticsDeo, mangaDao, chapterDao, chapterDownloadingDao, sourcesDao`. **Identical** to Android `MangaDatabase.kt:45-53`.

`SavedMangaDao.kt` exists as a 9th file in **both** repos but is wired in neither — orphan dead code that came from the source. Not a KMP regression.

DAO bindings are duplicated in every `PlatformModule.*` (Android lines 82-90, iOS lines 67-76, Desktop lines 67-76). Minor smell — could be hoisted into `sharedModule` — but functionally correct.

No DAOs use `TODO()`, `NotImplementedError`, or other stubs.

### Schema export

- `room { schemaDirectory("$projectDir/schemas") }` (`shared/build.gradle.kts:204-206`) — enabled.
- `@Database(... exportSchema = true)` (`MangaDatabase.kt:45`) — enabled.
- On disk: `shared/schemas/me.manga.kira.data.local.MangaDatabase/8.json` exists. **Only version 8** — no JSON for versions 1-7.

### Migrations

`shared/src/commonMain/.../data/local/Migrations.kt` has all 7 migrations (`MIGRATION_1_2` … `MIGRATION_7_8`), ported from `androidx.sqlite.db.SupportSQLiteDatabase` to `androidx.sqlite.SQLiteConnection`. Wired in `MangaDatabaseFactory.kt:14-25`, using `BundledSQLiteDriver()` consistently across all targets.

Per-platform `mangaDatabaseBuilder()` actuals:
- Android (`DatabaseBuilder.android.kt:23-27`) — `context.getDatabasePath(DATABASE_NAME)`; requires `setAndroidAppContext(applicationContext)` in `MyApp.onCreate()`.
- iOS (`DatabaseBuilder.ios.kt:11-22`) — `NSDocumentDirectory`.
- Desktop (`DatabaseBuilder.desktop.kt:7-13`) — `~/.yami-manga/manga_database`.

---

## Section 3 — Network sources / parsers

- Ktor `HttpClient` is a single `commonMain` `single { createHttpClient() }` (`SharedModule.kt:139`). Per-target actuals: engines OkHttp / Darwin / CIO.
- `ApiClient` (`data/remote/api/ApiClient.kt:28`) is the Retrofit-replacement Ktor wrapper — feature complete.
- 55 source repositories, ksoup-based parsers complete: `MangaBuddyParser.kt` (459 lines), `ManhwatopParser.kt` (371 lines). Small 13-17-line parsers (`AzoraParser`, `MangaLekParser`, `LavatoonsParser`, `TeamxParser`) are upstream-equivalent empty placeholders. No "parser not implemented" sentinels exist.
- Open TODO clusters in sources (functionally fine, degraded behavior):
  - `TODO(Phase 8 - locale)` — KMP has no locale-aware date parser (no ICU bindings). Affected: `AasqRepositoryv2:355`, `LavatoonsRepositoryv2:19`, `MangaworldItRepository:387`, `MangaOrigineRepository:566`, `TeamXRepositoryv2:403`, `MangaLekRepositoryv2:12`, `FlowerMangaRepository:206,252`. Chapter timestamps may be wrong for non-EN sources.
  - `TODO(Phase 8 - parallel-IO)` — `TeamXRepositoryv2:172` — page fetches serialized.
  - 9 commented-out Comick / MangaPark / ReadComicOnline stubs awaiting upstream re-enable.

---

## Section 4 — Risks / incomplete items

1. **Schemas missing for v1–v7** (`shared/schemas/me.manga.kira.data.local.MangaDatabase/` only contains `8.json`). Migrations are coded by hand and correct, but they aren't validated against historic schemas at build time.

2. **Dead code in `commonMain`**:
   - `NoOpDownloadRepository.kt` — not bound on any platform.
   - `NoOpComplaintRepository.kt` — not bound on any platform.
   - `SavedMangaDao.kt` — defined but not exposed via `MangaDatabase`.
   - `SharedModule.kt:313-317` comment is **stale** — claims iOS/Desktop bind `NoOpComplaintRepository`, but both now bind `ComplaintFirestoreRestDataSource`.

3. **DAO binding duplication**: 8 `single<XxxDao> { … }` lines repeated identically in all three `PlatformModule.*` files. Pure cleanup.

4. **iOS/Desktop download impl caveats** (intentional, documented):
   - No CBZ archiving on iOS (`CbzWriter` throws `NotImplementedError`) and Desktop (falls back to PNG). Pages stored loose.
   - No background-task scheduling — queue only runs while the process is alive. iOS BGTaskScheduler integration deferred.
   - Queue *does* persist across launches via Room.

5. **`DownloadRepositoryImpl` Android KoinWorkerFactory follow-up** — `DownloadWorkerV2` resolves deps via `GlobalContext.get()` rather than via Koin's `WorkerFactory`. Works today; brittle to multi-Koin setups.

6. **Non-fatal locale TODOs** may produce wrong chapter dates for AR/PT/IT/FR sources until ICU is introduced.

---

## Section 5 — Unreviewed Areas Discovery

The original audit covered only the data layer. This section surveys UI/feature screens, ViewModels, and navigation wiring that were NOT part of that audit. All paths verified by `find`/`grep` against the live `D:\yami manga\yami-kmp\` tree and cross-checked with the Android reference at `D:\yami manga\yami-manga-apk-main\`.

### 5.1 Feature directory parity

Feature directories under `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/`:
`complaint`, `details`, `download`, `history`, `home`, `language`, `library`, `library_details`, `notifications`, `onboarding`, `reader`, `refresh`, `repo_settings`, `settings`, `statistics`, `text`, `webview`, `whatsnew` (18 directories).

Android reference at `app/src/main/java/me/manga/yami/presentation/features/` has 17 (same set minus `text`, plus `about`). KMP `about` lives in `composeApp` (UI-only feature) instead of `shared` — not a regression.

### 5.2 Per-screen status

Layout: every screen has TWO touchpoints — the screen composable in `composeApp/src/commonMain/.../presentation/features/.../ui/screens/*Screen.kt`, and a route host in `composeApp/src/commonMain/.../navigation/routes/*Route.kt`. Both must be real for end-to-end functionality.

| # | Screen | Composable file (lines) | Route host file (lines) | Wired in NavHost? | Status |
|---|---|---|---|---|---|
| 1 | **Home** | `presentation/features/home/ui/screens/HomeScreen.kt` (423) | `navigation/routes/HomeScreenRoute.kt` (65) | Yes — `App.kt:196-202` | **Stub only** — route renders a `Text("Home — pending MangaViewModel port (Phase 10.x).")` placeholder; HomeScreen composable is fully implemented but never invoked. See `HomeScreenRoute.kt:22-41,60-62`. |
| 2 | **Search** | `presentation/features/home/ui/screens/search/SearchScreen.kt` (194) | None (overlay surfaced by HomeRoute upstream) | No | **Stub only** — SearchScreen composable is real but unreachable: it's invoked only by the upstream HomeRoute's `isSearchVisible` branch, which is part of the Home stub. No standalone route. |
| 3 | **Manga Details** | `presentation/features/details/ui/screens/MangaDetailsScreen.kt` (148) | `navigation/routes/MangaDetailsScreenRoute.kt` (113) | Yes — `App.kt:243-275` | **Implemented** — uses `koinViewModel<MangaDerailsViewModel>()` + `HomeViewModel`. Minor: `Handle403Error` (`MangaDetailsScreenRoute.kt:73`) and `HelpVideoDialog` (`:106`) are TODOs; users see plain error screen on 403. |
| 4 | **Reader (ChapterImages)** | `presentation/features/reader/ui/screens/ReaderScreen.kt` (490) | `navigation/routes/ChapterImagesScreenRoute.kt` (175) | Yes — `App.kt:307-313` | **Implemented** — `koinViewModel<ReaderViewModel>()` wired; chapter loading via `SharedChaptersViewModel`. TODO: `Handle403Error` (`ChapterImagesScreenRoute.kt:96`) and `GellixFontFamily` (`:53`) not yet ported. |
| 5 | **Library UI** | `presentation/features/library/ui/screens/LibraryScreen.kt` (314) | `navigation/routes/LibraryScreenRoute.kt` (211) | Yes — `App.kt:204-225` | **Implemented** — `LibraryViewModel`, `RefreshViewModel`, `WhatsNewViewModel` all Koin-injected. |
| 6 | **History UI** | `presentation/features/history/ui/screens/HistoryScreen.kt` (160) | `navigation/routes/HistoryScreenRoute.kt` (65) | Yes — `App.kt:227-233` | **Implemented** — `koinViewModel<HistoryViewModel>()`. TODO: `onChapterClick` to reader navigation (`HistoryScreenRoute.kt:20`). |
| 7 | **Settings UI** | `presentation/features/settings/ui/screens/SettingsScreen.kt` (397) | `navigation/routes/SettingsRoute.kt` (23) | Yes — `App.kt:315-321` | **Implemented** — trivial pass-through route; ViewModels injected inside the screen. |
| 8 | **Downloads UI** | `presentation/features/download/ui/screens/DownloadsScreen.kt` (412) | `navigation/routes/DownloadsScreenRoute.kt` (83) | Yes — `App.kt:355-361` | **Implemented** — `DownloadViewModelv2` wired. The route's docstring (`:32-34`) is **stale**: says VM "only resolves on Android" but the data audit confirmed iOS + Desktop now bind `CoroutineDownloadRepositoryImpl`. Functional everywhere. TODO: restore `PagingData` flows (`:26-30`) once `paging-compose-common` lands. |
| 9 | **Library Manga Details** | `presentation/features/library_details/ui/screens/LibraryMangaScreen.kt` (414) | `navigation/routes/LibraryMangaScreenRoute.kt` (222) | Yes — `App.kt:277-305` | **Partially implemented** — VM wired; ad/rewarded callbacks are TODOs (`:144,162,181`); WebViewDialog placeholder (`:105`). Core read/download/chapter UI works. |
| 10 | **Notifications / Updates** | `presentation/features/notifications/ui/screens/UpdatesScreen.kt` (248) | `navigation/routes/UpdatesScreenRoute.kt` (86) | Yes — `App.kt:235-241` | **Implemented** — `NotificationsViewModel` wired. |
| 11 | **Statistics** | `presentation/features/statistics/ui/screens/StatisticsScreen.kt` (149) | `navigation/routes/StatisticsScreenRoute.kt` (46) | Yes — `App.kt:323-329` | **Implemented**. |
| 12 | **Repo Settings** | `presentation/features/repo_settings/ui/screens/RepoSettingsScreen.kt` (258) | `navigation/routes/RepoSettingsScreenRoute.kt` (59) | Yes — `App.kt:339-345` | **Implemented**. |
| 13 | **Language Selection** | `presentation/features/language/ui/screens/LanguageSelectionScreen.kt` (228) | `navigation/routes/LanguageScreenRoute.kt` (71) | Yes — `App.kt:347-353` | **Implemented**. |
| 14 | **Onboarding — Welcome** | `presentation/features/onboarding/welcome/WelcomeScreen.kt` (123) | `navigation/routes/WelcomeScreenRoute.kt` (25) | Yes — `App.kt:172-178` | **Implemented**. |
| 15 | **Onboarding — Theme Selection** | `presentation/features/onboarding/theme_selection/ThemeSelectionScreen.kt` (170) | `navigation/routes/ThemeSelectionScreenRoute.kt` (71) | Yes — `App.kt:180-186` | **Implemented**. |
| 16 | **Onboarding — Sources** | `presentation/features/onboarding/sources/SourcesScreen.kt` (330) | `navigation/routes/SourcesScreenRoute.kt` (62) | Yes — `App.kt:188-194` | **Implemented**. |
| 17 | **WhatsNew** | `presentation/features/whatsnew/ui/WhatsNewScreen.kt` (152) | `navigation/routes/WhatsNewScreenRoute.kt` (213) | Yes — `App.kt:379-385` | **Implemented** — VM + DS wired in `SharedModule.kt:387-388`. The route file's claim at `:45-58` that Koin registration is "MISSING" is **stale** (already fixed). |
| 18 | **About** | `composeApp/.../about/screen/AboutScreen.kt` (184) | `navigation/routes/AboutScreenRoute.kt` (35) | Yes — `App.kt:363-369` | **Implemented**. |
| 19 | **WebView** | `presentation/features/webview/ui/screens/WebViewComposeScreen.kt` (136) | `navigation/routes/WebViewScreenRoute.kt` (42) | Yes — `App.kt:331-337` | **Implemented** — uses `WebViewHost` expect/actual at `core/webview/`. |
| 20 | **Complaint** | `presentation/features/complaint/ui/screens/ComplaintScreen.kt` (294) | `navigation/routes/ComplaintScreenRoute.kt` (67) | Yes — `App.kt:371-377` | **Implemented** — real `ComplaintRepository` impls on all 3 platforms (see Section 1b). |
| 21 | **Admin Complaint** | `composeApp/.../admin/complaint/AdminComplaintScreen.kt` | `navigation/routes/AdminComplaintScreenRoute.kt` (63) | Yes — `App.kt:387-393` | **Implemented**. |

### 5.3 Navigation / routing

Single source of truth: `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/Screen.kt` (`Screen` sealed class, 22 routes). NavHost dispatcher: `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt:167-394`. Every `Screen.*` entry has a corresponding `composable<Screen.X>` block in `App.kt`. Bottom-nav visibility driven by `SideEffect { onBottomBarVisibleChange(...) }` per route.

- `safeNavigate` and `safePopBackStack` helpers exist (`navigation/safePopBackStack.kt`, `navigation/NavigationLock.kt`) — defends against double-tap navigation.
- `HomeTabReselectedHandler` / `NavigationHandlerHolder` in `navigation/double_click/` — implemented but not yet wired (depends on Home VM completion).
- `BackHandler` for commonMain is intentionally deferred — see `App.kt:73-77`. System back still works on Android by default.

### 5.4 Missing / partial vs Android reference

Cross-referenced with `app/src/main/java/me/manga/yami/`:

- **`MangaViewModel` is MISSING in shared/commonMain.** Exists in Android at `app/src/main/java/me/manga/yami/presentation/common/viewmodel/MangaViewModel.kt:36` but no class with that name in `D:\yami manga\yami-kmp\shared\src\`. This is the direct cause of the Home Screen being a stub — the route's docstring lists this as the blocker (`HomeScreenRoute.kt:22-41`). HomeScreen composable already in place; SearchScreen overlay already in place; both depend on `MangaViewModel`.
- **No corresponding "AdViewModel" / AdMob plumbing** in commonMain — referenced as Phase 10.x TODOs in `LibraryMangaScreenRoute.kt:144,162,181`. AdProvider expect/actual exists in `core/ads/` but no aggregating ViewModel.
- **`WebViewDialog`** not ported — referenced as TODO in `MangaDetailsScreenRoute.kt:41`, `LibraryMangaScreenRoute.kt:57,105`, `ChapterImagesScreenRoute.kt:49`. Used for 403 captcha handling. WebView host exists, the *dialog* wrapper doesn't.
- **`HelpVideoDialog` / `VideoPlayerSlot`** — `VideoPlayerSlot` expect lives in `composeApp/.../core/platform/VideoPlayerSlot.kt` with only an Android actual. iOS + Desktop actuals not present. Affects MangaDetails help video.
- **`Handle403Error`** missing across multiple routes (depends on WebViewDialog above).
- **`YamiTheme` (custom typography + color scheme)** — `App.kt:105-107` notes it's a TODO. Currently using bare `MaterialTheme {}`.
- **`GellixFontFamily`** — referenced as a TODO at `ChapterImagesScreenRoute.kt:53`. Custom font not yet ported to compose-resources.

### 5.5 Status summary

- **Implemented (12 screens)**: Manga Details, Reader, Library, History, Settings, Downloads, Notifications/Updates, Statistics, Repo Settings, Language, Onboarding (Welcome/Theme/Sources), WhatsNew, About, WebView, Complaint, Admin Complaint.
- **Partially implemented (1 screen)**: Library Manga Details (ads + WebViewDialog stubbed).
- **Stub only (2 screens)**: Home Screen route, Search Screen (reachable only via Home).
- **Missing**: None at the screen level — every Android screen has a KMP counterpart. Gaps are in *supporting* components: `MangaViewModel`, `AdViewModel`, `WebViewDialog`, `Handle403Error`, `YamiTheme`, `GellixFontFamily`, iOS/Desktop actuals for `VideoPlayerSlot`.

### 5.6 TODO checklist (unfinished UI work)

- [ ] **CRITICAL — Port `MangaViewModel` to `shared/commonMain`**. Blocks Home Screen and Search Screen. Upstream class at `D:\yami manga\yami-manga-apk-main\app\src\main\java\me\manga\yami\presentation\common\viewmodel\MangaViewModel.kt`. After porting, restore the real `HomeScreenRoute` per the restoration plan in `HomeScreenRoute.kt:27-40`.
- [ ] Wire SearchScreen overlay inside the real `HomeScreenRoute` (currently unreachable).
- [ ] Port `WebViewDialog` to commonMain so `Handle403Error` can be restored in `MangaDetailsScreenRoute.kt`, `LibraryMangaScreenRoute.kt`, `ChapterImagesScreenRoute.kt`.
- [ ] Provide iOS + Desktop actuals for `VideoPlayerSlot` (currently Android-only), then re-enable `HelpVideoDialog` in `MangaDetailsScreenRoute.kt:106`.
- [ ] Add commonMain `BackHandler` expect/actual (`App.kt:77`).
- [ ] Port `YamiTheme` (custom typography + colour scheme) and replace bare `MaterialTheme` in `App.kt:104-111`.
- [ ] Port `GellixFontFamily` via compose-resources.
- [ ] Introduce `AdViewModel` (or expect/actual `BannerAd` / rewarded-ad surface) and wire callbacks in `LibraryMangaScreenRoute.kt:144,162,181`.
- [ ] Restore `PagingData<ChapterDownloadEntity>` flows in `DownloadsScreenRoute.kt` (and `DownloadViewModelv2`) once `paging-compose-common` is portable.
- [ ] Wire `HomeTabReselectedHandler` (already in `navigation/double_click/`) into the real Home route once it exists.
- [ ] Delete dead code: `NoOpDownloadRepository.kt`, `NoOpComplaintRepository.kt`, orphan `SavedMangaDao.kt` (or expose it).
- [ ] Fix stale comments: `SharedModule.kt:313-317` (claims iOS/Desktop NoOp complaints), `DownloadsScreenRoute.kt:32-34` (claims Android-only), `WhatsNewScreenRoute.kt:45-58` (claims Koin reg missing).
- [ ] Regenerate Room schemas for versions 1-7 (or document intentional gap).
- [ ] Phase 8 locale TODOs (chapter date parsing for AR/PT/IT/FR) — requires ICU bindings or expect/actual.

---

### Bottom-line answers to the data-layer questions

- **Are all repositories real on all three platforms?** Yes for downloads and complaints (Android uses native SDKs; iOS/Desktop use HTTP-equivalent impls). Yes for every other domain repository. The leftover `NoOp*Repository` files are unused dead code; no platform falls back to them at runtime.
- **Are all DAOs wired?** Yes — all 8 exposed DAOs from `MangaDatabase` are bound in every `PlatformModule.*`. The unwired `SavedMangaDao` is an orphan inherited from the source app.
- **Are all entities migrated?** Yes — 6 / 6 entities, identical to the Android reference. All 7 migrations preserved. Schema export is enabled but only the current v8 schema is on disk.

### Bottom-line answers to the UI/feature questions (Section 5)

- **Home Screen status**: composable is complete (423 lines), route is a 1-line `Text(...)` placeholder. **Stub only.**
- **Search Screen status**: composable complete (194 lines), unreachable because Home is stubbed. **Stub only (by dependency).**
- **All other Android screens have working KMP counterparts** with real ViewModels wired through Koin.
- **One missing ViewModel (`MangaViewModel`)** is the single biggest blocker for shipping. Everything else is polish (ads, WebView dialog, theme, fonts, 403 handling).
