# Runtime Smoke Test — Phase 14 / Section 47

> Mandatory output per `MIGRATION_PROMPT.md` Section 47. Manual smoke test checklist for verifying behavioral parity after full migration.

## Test environment

- Android: physical device running API 26+ (target: Android 14 / API 35)
- Desktop: Windows 11 (current dev host)
- iOS: deferred to project owner on macOS

## Smoke checklist

### App launch

| # | Step | Expected | Status |
|---|---|---|---|
| 1 | Cold-start app from launcher | Splash screen shows for ~500ms, then Home screen renders | ⏳ Phase 14 |
| 2 | Cold-start with prior session data (saved manga) | Home + Library show existing data; Room DB v8 round-trips successfully via Room KMP | ⏳ Phase 14 |
| 3 | Cold-start on Android 8 (API 26, minSdk floor) | App launches without crash; native libs strip OK | ⏳ Phase 14 |

### Main navigation

| # | Step | Expected | Status |
|---|---|---|---|
| 4 | Tap each bottom-nav tab (Home, Library, History, Settings) | Each tab navigates correctly via Compose-MP nav | ⏳ Phase 14 |
| 5 | Back-press from a leaf screen | Returns to parent route; backstack preserved | ⏳ Phase 14 |
| 6 | Re-tap current tab | `HomeTabReselectedHandler` triggers (scroll to top OR back-to-root) | ⏳ Phase 14 |
| 7 | Process-death restoration | `SavedStateHandle` (KMP version) restores ViewModel state correctly | ⏳ Phase 14 |

### Manga browsing + reading

| # | Step | Expected | Status |
|---|---|---|---|
| 8 | Browse Home: each source tab loads | Per-source repo calls succeed via Ktor (was Retrofit) | ⏳ Phase 14 |
| 9 | Search: type query, results appear | `IMangaDataApiServices.searchManga` equivalent via `ApiClient.postForm` works | ⏳ Phase 14 |
| 10 | Open manga details | All fields populate; Ksoup-parsed HTML matches source's jsoup output | ⏳ Phase 14 (per-source spot checks) |
| 11 | Open reader for a chapter | Pages load via Coil 3; `BrowserHeaders` headers applied | ⏳ Phase 14 |
| 12 | Reader: switch reading modes (vertical/horizontal/webtoon/continuous) | All 4 modes render identically to source | ⏳ Phase 14 |
| 13 | Reader: zoom-in/out, swipe between pages | `ZoomableImage` expect/actual works on Android (Telephoto) | ⏳ Phase 14 |
| 14 | Reader: AVIF page | Decoded on Android via `core/avif/HeifCoder.kt` | ⏳ Phase 14 |

### Library + downloads

| # | Step | Expected | Status |
|---|---|---|---|
| 15 | Add manga to library | `LibraryDeo.insertManga()` succeeds; UI updates via `Flow<List<SavedMangaEntity>>` | ⏳ Phase 14 |
| 16 | Remove manga from library | Cascading delete works (chapter + notification + history rows removed via `LibraryDeo.removeMangaWithChapters`) | ⏳ Phase 14 |
| 17 | Download a chapter | `ChapterDownloadDao.insert()` + `MangaDownloadWorker` flow runs; progress updates visible | ⏳ Phase 14 |
| 18 | Cancel running download | `DownloadCancelReceiver` broadcast fires; state goes to FAILED | ⏳ Phase 14 |
| 19 | Convert downloaded chapter to CBZ | `core/cbz/*` runs; ZIP file produced via commons-compress | ⏳ Phase 14 |
| 20 | Re-open downloaded chapter offline | Local image paths load instead of network | ⏳ Phase 14 |

### Settings + theme

| # | Step | Expected | Status |
|---|---|---|---|
| 21 | Toggle dark theme | Theme switches without restart; preserves on relaunch | ⏳ Phase 14 |
| 22 | Toggle language to Arabic | All screens re-render in Arabic; layout flips to RTL | ⏳ Phase 14 |
| 23 | Toggle source enabled/disabled | Home tab disappears/reappears; Source toggle persists via `SourcesDao` | ⏳ Phase 14 |
| 24 | Tap "Send complaint" | `SendComplaintUseCase` invokes `ComplaintRepository.sendComplaint` → Firestore | ⏳ Phase 14 |

### Edge cases / error states

| # | Step | Expected | Status |
|---|---|---|---|
| 25 | Airplane mode → open manga | Loading → error state; retry button works after re-enable | ⏳ Phase 14 |
| 26 | HTTP 403 from source | `Handle403Error` flow triggers (WebView CAPTCHA solve, then retry) | ⏳ Phase 14 |
| 27 | Empty search results | Empty state composable shows | ⏳ Phase 14 |
| 28 | Notification: new chapter → tap | Opens MangaDetails for that manga, optionally auto-loads chapter | ⏳ Phase 14 |
| 29 | Crash → `CrashActivity` shows | `CrashInfoScreen` + `CrashScreen` render | ⏳ Phase 14 |

### Ads (Android only)

| # | Step | Expected | Status |
|---|---|---|---|
| 30 | Open Home → native ad appears in list at correct interval | `interleaveAds7.kt` calculates positions; NativeAdQueue serves ads | ⏳ Phase 14 |
| 31 | Open chapter → rewarded ad triggers if cooldown elapsed | `AdViewModel` + `RewardedAdManager` flow works | ⏳ Phase 14 |
| 32 | Banner ad shows on Library | `BannerAdView` composable renders | ⏳ Phase 14 |

### Desktop (Phase 13)

| # | Step | Expected | Status |
|---|---|---|---|
| 33 | `gradlew.bat :desktopApp:run` on Windows | App launches; Home screen renders | ⏳ Phase 14 |
| 34 | Desktop: open manga → load images | Ktor CIO engine fetches; Coil 3 displays | ⏳ Phase 14 |
| 35 | Desktop: window state persisted | Size+position survives close+relaunch | ⏳ Phase 14 |

### iOS (deferred to macOS validation)

| # | Step | Owner runs on macOS |
|---|---|---|
| 36 | `./gradlew :shared:compileKotlinIosX64/Arm64/SimulatorArm64` | ⏳ |
| 37 | `./gradlew :shared:linkDebugFrameworkIos*` | ⏳ |
| 38 | Build iOS app in Xcode simulator | ⏳ |
| 39 | iOS smoke checklist (subset of #1-#29) | ⏳ |

## Documented behavioral asymmetries (not regressions)

- Firebase / AdMob / Play services / WorkManager / WebView / AVIF / DEX-plugins: **Android-only**. iOS + Desktop use noop providers or stubs. Documented in `ios-readiness-report.md` and `desktop-readiness-report.md`.
- `ChapterDownloadDao` paginated queries: removed (Android-only PagingSource); non-paginated Flow used instead. UI behavior identical for users.

## Status

| Item | Status |
|---|---|
| Smoke checklist authored (39 steps) | ✅ |
| Android smoke run (manual on physical device) | ⏳ Phase 14 |
| Desktop smoke run | ⏳ Phase 14 |
| iOS smoke run | ⏳ (project owner, macOS) |
| Per-source HTML parser spot-checks (Ksoup vs jsoup) | ⏳ Phase 7.x batches |
| Discovered-issues additions if any test fails | logged into `discovered-issues.md` as found |
