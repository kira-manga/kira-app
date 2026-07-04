# Module Map — Source → KMP

> Maps every top-level package under `me.manga.yami.*` in the source to its KMP target source set and module. **No file may have an unknown target.**

| KMP target | Description |
|---|---|
| `shared/src/commonMain` | Pure Kotlin, KMP-portable (Android+iOS+Desktop). Models, domain, repository interfaces, ViewModels, navigation routes, Ktor client setup, Room entities/DAOs/database, Koin common module, shared Compose UI. |
| `shared/src/androidMain` | Android-only `actual`s + Android-only feature code that no other target needs (Firebase, AdMob, DEX loader, WorkManager, Android FileProvider). |
| `shared/src/iosMain` | iOS `actual`s (storage, secure storage, datetime, image decoding fallback, no-op analytics/ads). |
| `shared/src/desktopMain` | Desktop JVM `actual`s (file I/O, JavaFX/Swing-free, no AdMob, no FCM). |
| `composeApp/src/commonMain` | Shared Compose Multiplatform UI (screens, components, theme). Sometimes folded into `shared`. **Decision (locked):** keep `shared` for non-UI + `composeApp` for UI, so the data layer can be consumed without pulling Compose into iOS-only Swift targets. |
| `app` | Android launcher: `Application` (`MyApp`), `MainActivity`, `CrashActivity`, `FileProvider`, FCM service, AdMob init, manifest, ProGuard rules, signing, `google-services.json`. |
| `desktopApp` | Desktop entry point (`main()`), JVM-specific window setup. |
| `iosApp` | Xcode project + Swift entry point — scaffolded but compiled on macOS only. |

---

## Source package → KMP destination

| Source package (under `me.manga.kira`) | Destination | Notes |
|---|---|---|
| `MyApp.kt` | `app/src/main/java/.../MyApp.kt` (Android-only) | `Application` class; initializes Koin (post-migration), Firebase, AdMob, WorkManager. Stays in `app` module. |
| `MainActivity.kt` | `app/src/main/java/.../MainActivity.kt` (Android-only) | Sets `setContent { App() }` calling a shared `@Composable App()` from `composeApp/commonMain`. |
| `TextViewModel.kt` (root) | `shared/commonMain/.../TextViewModel.kt` | Pure ViewModel, portable. |
| `Base64ImageConverter.kt` (root) | `shared/commonMain/.../Base64ImageConverter.kt` | If pure Kotlin → common; otherwise `androidMain`. Inspect in Phase 4. |
| `BrowserHeadersInterceptor.kt` (root) | `shared/commonMain/.../BrowserHeadersInterceptor.kt` (Ktor `HttpClient` plugin) | Rewritten as a Ktor plugin from OkHttp Interceptor. |
| `me.manga.kira.admin.*` | `composeApp/commonMain/.../admin/` + `shared/commonMain/.../admin/` (ViewModel) | API test screen, complaint admin screen + VM. |
| `me.manga.kira.ad_mob.*` | `shared/androidMain/.../ads/` + `commonMain/.../ads/` (interface) | Android-only AdMob impl behind a common `AdProvider` interface. iOS/Desktop = `NoopAdProvider`. |
| `me.manga.kira.core.avif.*` | `shared/androidMain/.../avif/` | Android-only AVIF decoder. Wrapped behind a common `ImageDecoderRegistry` extension if needed; otherwise pure `androidMain`. |
| `me.manga.kira.core.blur.BlurTransformation` | `shared/commonMain/.../coil/BlurTransformation.kt` if Coil 3 KMP supports `Transformation`; else `androidMain`. To verify in Phase 10. |
| `me.manga.kira.core.cbz.*` | `shared/commonMain/.../cbz/` for `CbzSettings`, `CbzManager` interface, `CbzConversionViewModel`. `androidMain` for the actual file-system implementation (uses `java.io.File`, `Apache commons-compress`). |
| `me.manga.kira.core.file.FileSizeUtils` | `shared/commonMain` (pure Long math) |
| `me.manga.kira.core.network_cache.forceCacheForDados` | `shared/androidMain` (OkHttp-specific) — replaced by Ktor cache config in `commonMain` if behavior matches. |
| `me.manga.kira.core.network_connectivity.*` | `shared/commonMain` interface + `androidMain`/`iosMain`/`desktopMain` actuals. |
| `me.manga.kira.core.progress.*` | `shared/commonMain` (`ProgressManager`, `ProgressState`, `format`) + `androidMain` (`ProgressInterceptor` is OkHttp-bound; rewrite as Ktor plugin in `commonMain`). |
| `me.manga.kira.core.states.*` | `shared/commonMain` (`ImagesState`, `State`, `StringProvider`). |
| `me.manga.kira.core.storage.*` | `shared/commonMain` (`PrefsDelegate`, `StorageKeys`) on top of `multiplatform-settings`. `DataStoreDelegate`/`DataStoreHelper` Android-only. |
| `me.manga.kira.core.util.*` | Mostly `commonMain` (date utils → `kotlinx.datetime`, `data_classes`, `Plus18memes`). Notification helpers Android-only. `ScreenshotUtils` Android-only (uses `View.draw`). `Handle403Error` shared. |
| `me.manga.kira.crash.*` | `app` Android-only (`CrashActivity`) + `composeApp/commonMain` for `CrashScreen` / `CrashInfoScreen` composables if portable. |
| `me.manga.kira.data.local.*` (Room) | `shared/commonMain` — Room KMP entities, DAOs, type converters (`LocalDateConverter`, `LocalDateTimeConverter` reworked for `kotlinx.datetime`), database, migrations. Schema export → `shared/schemas/`. |
| `me.manga.kira.data.remote.*` | `shared/commonMain` — Ktor `HttpClient` setup, response wrappers. Retrofit `IMangaDataApiServices` → `ApiService` Ktor wrapper exposing the same `suspend fun … : Response<String>`-style methods (using Ktor's `HttpResponse` + `.bodyAsText()`). |
| `me.manga.kira.dex.*` | `shared/androidMain` ONLY — `AasqPlugin`, `DexPluginLoader`, `PluginData`. Common code uses `interface MangaSource` + `SourceRegistry`; on Android the registry merges static + DEX-loaded sources. |
| `me.manga.kira.di.*` (Hilt) | Replaced by `me.manga.yami.di.*` Koin modules in `shared/commonMain` + platform modules in `androidMain`/`iosMain`/`desktopMain`. **Hilt removed entirely in Phase 5.** |
| `me.manga.kira.domain.*` | `shared/commonMain` — models, repository interfaces, service interfaces. `domain.device.AndroidDeviceInfoProvider` → `androidMain`; the interface is in `commonMain`. |
| `me.manga.kira.firebase_cores.*` | `shared/androidMain` only. iOS will get a Firebase iOS SDK abstraction in a later iteration (out of session-1 scope — documented in `pending-work.md`). Desktop = no-op analytics. |
| `me.manga.kira.google_play_cores.*` | `shared/androidMain` only. iOS/Desktop = no-op `AppUpdateHelper`/`ReviewManagerHelper`. |
| `me.manga.kira.navigation.*` | Routes → `composeApp/commonMain/.../navigation/routes/`. `NavGraphV2` → `composeApp/commonMain`. `NavigationLock`, `safePopBackStack`, `double_click/*` → `composeApp/commonMain` (pure Compose Navigation helpers). |
| `me.manga.kira.presentation.common.*` | `composeApp/commonMain` (UI components) + `shared/commonMain` (3 shared ViewModels). |
| `me.manga.kira.presentation.features.*` | `composeApp/commonMain` per-feature subpackages. ViewModels → `shared/commonMain`. Repositories → `shared/commonMain`. Use cases → `shared/commonMain`. |
| `me.manga.kira.sources_repositry.*` | `shared/commonMain` (40+ per-language source repositories — port jsoup → ksoup, OkHttp → Ktor). `BaseMangaRepository`, `EmptyMangaRepository` → `commonMain`. |
| `me.manga.kira.theme.*` | `composeApp/commonMain` — Color, Theme, Type. |
| `me.manga.kira.work.*` | `shared/androidMain` (`CbzMigrationWorker`, `LibraryRefreshWorker`, `MangaDownloadWorker`, `NotificationWorker`, `Logs`) — WorkManager is Android-only. Domain logic invoked by each worker stays in `commonMain` so iOS/Desktop can run equivalent background jobs (BGTaskScheduler on iOS later). |

---

## Specific files of note

| File | Action |
|---|---|
| `admin/dgfhldghlghg.kt` | Misnamed file. Investigate in Phase 4; rename if unused or rename to match its content. **Do not delete silently** — record in `discovered-issues.md` and `renames.md`. |
| `admin/Admin.kt` | Verify; likely an entry-point composable. |
| `data/remote/af.kt` | Single-letter file — investigate in Phase 4. Likely `AuthorizationFlags.kt` or generated. Document. |
| `google_play_cores/ss.kt` | Misnamed. Investigate; preserve content. |
| `data/local/util/DataBaseHelper.kt` | Likely a Room builder helper; will be replaced by Room KMP builder in `androidMain` actual. |
| `presentation/features/details/ui/viewmodel/MangaDerailsViewModel.kt` | Typo (`Derails` → `Details`). **Preserve the typo unless the user approves a rename.** Record in `renames.md`. |
| `presentation/features/download/ui/test2/DownloadViewModelv2.kt` | Mid-refactor file (`test2/`, `v2`). Preserve. |
| `data/local/dao/StatisticsDeo.kt`, `LibraryDeo.kt` | Likely typos for `Dao`. Preserve unless user approves. |
| `core/storage/StorageKeys.kt` | Map all keys to `multiplatform-settings` equivalents in Phase 8. |
| `BrowserHeadersInterceptor.kt` | Convert OkHttp `Interceptor` → Ktor `HttpRequestBuilder.headers { … }` plugin. |
