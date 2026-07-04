# Feature Map

> One row per feature. **Status** at end of migration must be `migrated` / `verified` / `platform_specific_keep` / `blocked` for every feature listed.

| # | Feature | Source package | Screens | ViewModel | Repository | Use cases | Status |
|---|---|---|---|---|---|---|---|
| 1 | **App shell / launcher** | `MainActivity`, `MyApp`, `theme/*`, `navigation/NavGraphV2` | — | — | — | — | not_started |
| 2 | **Crash reporting + crash screen** | `crash/*`, Firebase Crashlytics in `firebase_cores/*` | `CrashScreen`, `CrashInfoScreen` (`CrashActivity` Android only) | — | — | — | not_started |
| 3 | **Onboarding (welcome, sources, theme)** | `presentation/features/onboarding/*` | `WelcomeScreen`, `SourcesScreen`, `ThemeSelector`, `ThemeSelectionScreen` | `OnboardingViewModel` | — | — | not_started |
| 4 | **Home (manga catalog + multi-repo search)** | `presentation/features/home/*` | `HomeScreen`, `SearchScreen`, `MultiRepoResults`, `SiteMaintenanceScreen` + 6 components | `HomeViewModel` | (uses `MangaRepository` and source `BaseMangaRepository`) | — | not_started |
| 5 | **Manga details** | `presentation/features/details/*` | `MangaDetailsScreen`, `DetailsContent`, `ChapterItem` + header/genres/dialog components | `MangaDerailsViewModel` (typo preserved) | (uses `MangaRepository`) | — | not_started |
| 6 | **Reader (vertical / horizontal / webtoon / continuous)** | `presentation/features/reader/*` | `ReaderScreen`, `HorizontalReadingMode`, `VerticalReadingMode`, `WebToonReadingMode`, `ContinuousVerticalReadingMode`, `ZoomableImage`, `PagerImageItem`, control overlay, error card, seek bar, reading mode dialog | `ReaderViewModel` | (uses `MangaRepository` for pages; depends on Coil 3 + Telephoto) | — | not_started |
| 7 | **Library** | `presentation/features/library/*` | `LibraryScreen`, `LibraryItems`, `MangaCard` + components (empty placeholder, animated preloader, download progress dialog, icon-with-count, filter bottom sheet, sort/filter chips, custom filter sheet) | `LibraryViewModel` | `LibraryRepository` (`presentation/features/library/domain/`) | — | not_started |
| 8 | **Library details (per-manga local view)** | `presentation/features/library_details/*` | `LibraryMangaScreen`, header section, chapter item, selection actions, info compose, top app bar, total size display, download menu, preview | `LibraryDetailsViewModel` | (uses `LibraryRepository` + `MangaDao`) | — | not_started |
| 9 | **History (recently read)** | `presentation/features/history/*` | `HistoryScreen`, `HistoryItem` | `HistoryViewModel` | `HistoryRepository` | — | not_started |
| 10 | **Downloads** | `presentation/features/download/*` (+ `core/cbz/*`) | `DownloadsScreen`, settings section, CBZ conversion dialog | `DownloadViewModelv2` | `DownloadRepository[Impl]` (+ workers) | — | not_started |
| 11 | **Notifications (chapter updates)** | `presentation/features/notifications/*` | `UpdatesScreen`, `UpdateItem` | `NotificationsViewModel` | `NotificationRepository` | — | not_started |
| 12 | **Settings** | `presentation/features/settings/*` | `SettingsScreen` + navigation item | `SettingsViewModel` | `SettingsRepository` | — | not_started |
| 13 | **Repository (source) settings** | `presentation/features/repo_settings/*` | `RepoSettingsScreen` + toggle/language components | `RepoSettingsViewModel` | `SourcesRepository`, `UpdateSourcesRepository` | — | not_started |
| 14 | **Language selection** | `presentation/features/language/*` | `LanguageSelectionScreen` | `LanguageViewModel` | — | — | not_started |
| 15 | **Statistics** | `presentation/features/statistics/*` | `StatisticsScreen`, `StatsOverview` | `StatisticsViewModel` | `StatisticsRepository` | — | not_started |
| 16 | **What's new** | `presentation/features/whatsnew/*` | `WhatsNewScreen` + WhatsNew/Video/Image/Feature card / FullscreenMediaViewer components | `WhatsNewViewModel` | (in-memory model) | — | not_started |
| 17 | **About / social links** | `presentation/features/about/*` | `AboutScreen`, `SocialMediaRow` | — | — | — | not_started |
| 18 | **Refresh / sources update worker UI** | `presentation/features/refresh/*` | `RefreshScreen` | `RefreshViewModel` | (uses `UpdateSourcesRepository`) | — | not_started |
| 19 | **WebView** | `presentation/features/webview/*` (+ `work/webViewDialog.kt`) | `WebViewComposeScreen`, `webView` component, dialog | `WebViewViewModel` | — | — | **Android-only** (`platform_specific_keep`). iOS/Desktop: stub or `WKWebView`/`JCEF` later. |
| 20 | **Complaint (user complaints + admin reply)** | `presentation/features/complaint/*` + `admin/complaint/*` | `ComplaintScreen` + 5+ components (action dialog, card, components, empty/error/loading states) | `ComplaintViewModel`, `AdminComplaintViewModel` | `ComplaintRepository` (in `di/complaint/`) | `SendComplaintUseCase`, `UpdateComplaintUseCase`, `DeleteComplaintUseCase`, `GetAllComplaintUseCase`, `GetUserComplaintUseCase` | not_started |
| 21 | **Admin API test** | `admin/api_test/*` + `admin/Admin.kt` + `admin/dgfhldghlghg.kt` | `ApiTestScreen` | — | — | — | not_started |
| 22 | **Manga sources (per-language scrapers)** | `sources_repositry/*` | (no screens) | — | 40+ source `*Repository.kt` classes across `ar/`, `en/`, `es/`, `fr/`, `in/`, `it/`, `pt/`, `ru/`, `tr/` | — | not_started |
| 23 | **Common / shared composables** | `presentation/common/*` | `LoadingScreen`, `ErrorScreen`, `BottomNavigationBar`, `TopAppBarCom`, `SearchAppBar`, `FeedbackDialog`, action buttons, chips, scroll, toast, images, list items, section titles, auto-sized text, language toggle | `ChaptersViewModel`, `MangaViewModel`, `SharedChaptersViewModel` | — | — | not_started |
| 24 | **Background work** | `work/*` | (worker UI surfaces in `webViewDialog.kt`) | — | (uses repositories) | — | **Android-only** (`platform_specific_keep`). Domain logic in `commonMain` so iOS later uses BGTaskScheduler. |
| 25 | **AdMob (rewarded, native, banner)** | `ad_mob/*` | `BannerAdView`, `NativeAdListItem` | `AdViewModel` | — | — | **Android-only** (`platform_specific_keep`). iOS/Desktop: `NoopAdProvider`. |
| 26 | **Firebase (analytics, crashlytics, messaging, firestore)** | `firebase_cores/*` | — | — | — | — | **Android-only** (`platform_specific_keep`). iOS Firebase SDK is a later effort. |
| 27 | **Google Play (app update, review)** | `google_play_cores/*` | — | — | — | — | **Android-only** (`platform_specific_keep`). |
| 28 | **Runtime DEX source plugins** | `dex/*` + `buildDexPlugin` Gradle task + `sources_repositry/common/MangaSource.kt` | — | — | — | — | **Android-only** (`platform_specific_keep`). |

---

## Cross-feature dependencies (high-level)

- **`MangaRepository` (`domain/repos/MangaRepository.kt`)** is the central interface aggregating per-source repositories. `Home`, `Details`, `Reader`, `Library`, `Refresh`, `Repo settings`, and per-source feature `Sources_repositry` all depend on it.
- **`SourceRegistry`** (post-migration) wraps `ActiveRepoProvider` + the DEX plugin loader on Android. All `*Repository.kt` source classes register here.
- **`MangaDatabase` (Room)** is consumed by `Library`, `Library details`, `History`, `Notifications`, `Downloads`, `Statistics`, `Repo settings`.
- **`Koin AppModule` (post-migration)** loads all 16 sub-modules at `MyApp.onCreate()` (Android) / `main()` (Desktop) / `KoinHelper.start()` (iOS, called from Swift).
- **`AdViewModel`** is a singleton that injects into screens that show banner/native ads (Home, Library, Details, Reader inter-page native ads).
- **`SharedChaptersViewModel`** / **`MangaViewModel`** are activity-scoped on Android; on KMP they become Koin singletons or component-scoped (TBD per ViewModel in Phase 9).
