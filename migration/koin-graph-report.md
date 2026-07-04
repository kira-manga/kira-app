# Koin Graph Report

> Mandatory output per Section 38 of `MIGRATION_PROMPT.md`. Tracks the live Koin dependency graph as it grows across Phases 5..11. Each row is a binding currently registered in the Koin scaffold.

## Active bindings (as of Phase 8 batch 8.12)

### `commonMain` — `sharedModule` (in `shared/src/commonMain/kotlin/me/manga/yamiapk/di/SharedModule.kt`)

#### HTTP / API

| Binding | Definition | Scope | Phase migrated | Verified |
|---|---|---|---|---|
| `HttpClient` | `single { createHttpClient() }` | single | 8.12 | ✅ 5-build |
| `ApiClient` | `single { ApiClient(get()) }` | single | 8.12 | ✅ 5-build |

#### Use cases (complaint feature)

| Binding | Definition | Scope | Phase migrated | Verified |
|---|---|---|---|---|
| `GetUserComplaintUseCase` | `factory { GetUserComplaintUseCase(get()) }` | factory | 5.1 | ✅ compile |
| `GetAllComplaintUseCase` | `factory { GetAllComplaintUseCase(get()) }` | factory | 5.1 | ✅ compile |
| `SendComplaintUseCase` | `factory { SendComplaintUseCase(get()) }` | factory | 5.1 | ✅ compile |
| `UpdateComplaintUseCase` | `factory { UpdateComplaintUseCase(get()) }` | factory | 5.1 | ✅ compile |
| `DeleteComplaintUseCase` | `factory { DeleteComplaintUseCase(get()) }` | factory | 5.1 | ✅ compile |

#### Stores

| Binding | Definition | Scope | Phase migrated | Verified |
|---|---|---|---|---|
| `ManhastroDadosStore` | `single { ManhastroDadosStore() }` | single | 8.12 | ✅ 5-build |

#### Per-source repositories (Phase 7 ports, bound in Phase 8.12)

All repositories are bound as `factory { … }` to mirror upstream's per-call lifecycle. Each takes `(ApiClient, DataStoreHelper, SourcesDao)` (resolved by type via `get()` — order in the actual ctor varies but Koin uses type-based resolution).

##### Arabic (14)

| Repository | Phase |
|---|---|
| `AzoraRepositoryv2` | 8.12 |
| `AasqRepositoryv2` | 8.12 |
| `DilarRepository` | 8.12 |
| `DilarV2Repository` | 8.12 |
| `LavatoonsRepositoryv2` | 8.12 |
| `MangaLekRepositoryv2` | 8.12 |
| `MangamelloRepository` | 8.12 |
| `MangamelloPlusRepository` | 8.12 |
| `MangaParkRepositoryAr` | 8.12 |
| `MangatukRepository` | 8.12 |
| `ProMangaRepository` | 8.12 |
| `ProchanRepository` | 8.12 |
| `SwatMangaRepository` | 8.12 |
| `TeamXRepositoryv2` | 8.12 |

##### English (9)

| Repository | Phase |
|---|---|
| `ComickRepository` | 8.12 |
| `MangaParkRepository` | 8.12 |
| `BatcaveRepository` | 8.12 |
| `BatotoEnRepositoryv2` | 8.12 |
| `DemonicScansRepository` | 8.12 |
| `MangaBuddyRepositoryV2` | 8.12 |
| `ManhwatopRepositoryV2` | 8.12 |
| `TapasticRepository` | 8.12 |
| `ZazamangaRepository` | 8.12 |

##### Spanish (6)

| Repository | Phase |
|---|---|
| `MangaParkRepositoryEs` | 8.12 |
| `MangaParkRepositoryEs419` | 8.12 |
| `InMangaRepository` | 8.12 |
| `ManhwawebEsRepository` | 8.12 |
| `OlympusbibliotecaRepository` | 8.12 |
| `TaurusFansubEsRepository` | 8.12 |

##### French (2)

| Repository | Phase |
|---|---|
| `RaijinScanRepository` | 8.12 |
| `MangaOrigineRepository` | 8.12 |

##### Indonesian (2)

| Repository | Phase |
|---|---|
| `KomikCastRepository` | 8.12 |
| `KomikuRepository` | 8.12 |

##### Italian (1)

| Repository | Phase |
|---|---|
| `MangaworldItRepository` | 8.12 |

##### Portuguese (5)

| Repository | Phase |
|---|---|
| `FlowerMangaRepository` | 8.12 |
| `ManhastroRepository` (takes 4th arg: `ManhastroDadosStore`) | 8.12 |
| `MediocretoonsRepository` | 8.12 |
| `SussytoonsRepository` | 8.12 |

##### Russian (3)

| Repository | Phase |
|---|---|
| `DesuRepository` | 8.12 |
| `MangahubRepository` | 8.12 |
| `SenkuroRepository` | 8.12 |

##### Turkish (3)

| Repository | Phase |
|---|---|
| `TimenaightRepository` | 8.12 |
| `WebtoonhattiRepository` | 8.12 |
| `WebtoontrRepository` | 8.12 |

##### Intentionally NOT bound (empty stubs in commonMain)

These repository files are doc-only stubs in commonMain because their upstream Android source is either commented out or depends on a base class that hasn't been ported. They will be wired in the migration step that uncomments their bodies.

- `ReadComicOnlineRepository` (en)
- `ComickRepository{Ar,Es,Id,It,PtBr,Ru,Tr}`
- `MangaParkRepositoryIt`

Total `commonMain` bindings: **2 (HTTP) + 5 (use cases) + 1 (store) + 44 (repos) = 52**.

Type-check note: `ComplaintRepository` (consumed by the 5 use cases) is still bound only on `androidMain` once Firestore lands; on iOS/Desktop it'll bind to a noop or HTTP-based impl during Phase 11 — see `di-migration-report.md`.

### `androidMain` — `PlatformModule.android` (in `shared/src/androidMain/kotlin/me/manga/yamiapk/di/PlatformModule.android.kt`)

| Binding | Definition | Scope | Phase migrated | Verified |
|---|---|---|---|---|
| `SettingsFactory` | `single { SettingsFactory(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `ObservableSettings` | `single { get<SettingsFactory>().createObservable("yami_settings") }` | single | 8.12 | ✅ 5-build |
| `DataStoreHelper` | `single { DataStoreHelper(get()) }` | single | 8.12 | ✅ 5-build |
| `MangaDatabase` | `single { buildMangaDatabase() }` | single | 6 | ✅ 5-build |
| `HistoryDao` | `single { get<MangaDatabase>().historyDao() }` | single | 6 | ✅ 5-build |
| `LibraryDeo` | `single { get<MangaDatabase>().libraryDeo() }` | single | 6 | ✅ 5-build |
| `NotificationDao` | `single { get<MangaDatabase>().notificationDao() }` | single | 6 | ✅ 5-build |
| `StatisticsDeo` | `single { get<MangaDatabase>().statisticsDeo() }` | single | 6 | ✅ 5-build |
| `MangaDao` | `single { get<MangaDatabase>().mangaDao() }` | single | 6 | ✅ 5-build |
| `ChapterDao` | `single { get<MangaDatabase>().chapterDao() }` | single | 6 | ✅ 5-build |
| `ChapterDownloadDao` | `single { get<MangaDatabase>().chapterDownloadingDao() }` | single | 6 | ✅ 5-build |
| `SourcesDao` | `single { get<MangaDatabase>().sourcesDao() }` | single | 6 | ✅ 5-build |
| `ConnectivityObserver` | `single<ConnectivityObserver> { AndroidConnectivityObserver(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `UserIdProvider` | `single<UserIdProvider> { AndroidUserIdProvider(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `DeviceInfoProvider` | `single<DeviceInfoProvider> { AndroidDeviceInfoProvider() }` | single | 8.12 | ✅ 5-build |
| `NotificationPresenter` | `single { NotificationPresenter(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `AppFileSystem` | `single { AppFileSystem(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `CbzWriter` | `single { CbzWriter(get()) }` | single | 8.12 | ✅ 5-build |
| `CbzReader` | `single { CbzReader(get()) }` | single | 8.12 | ✅ 5-build |
| `BackgroundJobScheduler` | `single { BackgroundJobScheduler(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `SecureStorage` | `single { SecureStorage(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `AnalyticsClient` | `single { AnalyticsClient(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `CrashReporter` | `single { CrashReporter() }` | single | 8.12 | ✅ 5-build |
| `PushTokenProvider` | `single { PushTokenProvider() }` | single | 8.12 | ✅ 5-build |
| `RemoteDocStore` | `single { RemoteDocStore() }` | single | 8.12 | ✅ 5-build |
| `AdProvider` | `single { AdProvider(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `AppUpdateClient` | `single { AppUpdateClient(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `InAppReviewClient` | `single { InAppReviewClient(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `ConsentFlowClient` | `single { ConsentFlowClient(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `ImageDecoderRegistry` | `single { ImageDecoderRegistry() }` | single | 8.12 | ✅ 5-build |
| `ScreenshotProvider` | `single { ScreenshotProvider(androidContext()) }` | single | 8.12 | ✅ 5-build |
| `DominantColorExtractor` | `single { DominantColorExtractor() }` | single | 8.12 | ✅ 5-build |

Total `androidMain` bindings: **31**.

Context resolution: relies on `androidContext()` registered via `startKoin { androidContext(this) }` in `MyApp.onCreate()` (Phase 11). For the database build, an additional `setAndroidAppContext(...)` call is required — see `DatabaseBuilder.android.kt`. Same pattern for `setAndroidDeviceTierContext` (see `core/util/heap/DeviceTier.android.kt`).

`AdProvider`, `AppUpdateClient`, `InAppReviewClient`, and `ConsentFlowClient` each accept an `activityProvider: () -> Activity?` lambda. The default `{ null }` keeps the build green and the facades return their "no activity" branches until Phase 11 wires a real lambda from an `ActivityHolder` singleton.

### `iosMain` — `PlatformModule.ios` (in `shared/src/iosMain/kotlin/me/manga/yamiapk/di/PlatformModule.ios.kt`)

Same 31 bindings as Android, with no-arg ctors throughout (every iOS actual reaches Foundation singletons — NSFileManager, NSUserDefaults, NSProcessInfo — directly). `IosConnectivityObserver`, `IosUserIdProvider`, `IosDeviceInfoProvider` replace their Android counterparts. `ScreenshotProvider()` takes no args.

Firebase/AdMob/Play-services/UMP facades all resolve to their iOS-noop actuals (logged via Kermit). Phase 12 will replace those noops once the iOS Cocoapods / cinterop story is in place.

Total `iosMain` bindings: **31**.

### `desktopMain` — `PlatformModule.desktop` (in `shared/src/desktopMain/kotlin/me/manga/yamiapk/di/PlatformModule.desktop.kt`)

Same 31 bindings as Android/iOS, with no-arg ctors except for `ScreenshotProvider(get())` which depends on the already-bound `AppFileSystem` for its cache directory. `DesktopConnectivityObserver`, `DesktopUserIdProvider`, `DesktopDeviceInfoProvider` replace their iOS counterparts.

Firebase/AdMob/Play-services/UMP facades all resolve to their Desktop-noop actuals.

Total `desktopMain` bindings: **31**.

## Live binding count by source set

| Source set | Bindings | Last updated |
|---|---|---|
| `commonMain` | 52 | 2026-05-23 (8.12) |
| `androidMain` | 31 | 2026-05-23 (8.12) |
| `iosMain` | 31 | 2026-05-23 (8.12) |
| `desktopMain` | 31 | 2026-05-23 (8.12) |
| **total** | **145** | |

(Each per-platform actual counts once; the same logical binding is registered separately per source set.)

## Pending bindings by source phase

(See `di-migration-report.md` for the full Hilt → Koin mapping table.)

### Phase 9 (ViewModels) — adds:
- 24 `viewModel { … }` registrations across feature modules.
- `SavedStateHandle` parameter wiring via `koinViewModel { parametersOf(…) }`.
- `MangaRepositoryProvider` (or equivalent) that switches between the 44 bound repositories based on `MangaSource`.
- `ComplaintRepository` real impls on each platform (Android Firestore, iOS/Desktop noop or HTTP).

### Phase 11 (MyApp wiring) — adds:
- `KoinWorkerFactory` Android-only.
- `MyApp.onCreate()` calls `initKoin { androidContext(this@MyApp) }`.
- Real `activityProvider` lambdas from `ActivityHolder` singleton (replaces `{ null }` defaults on the 4 facades that need a foreground Activity).
- `setAndroidAppContext(applicationContext)` + `setAndroidDeviceTierContext(applicationContext)` from `MyApp.onCreate()`.
- Removes any remaining Hilt scaffolding from `app/`.

## Verification policy

Per Section 38 ("Koin Verification Rule"):

> After migrating DI to Koin: verify every repository, use case, data source, database, ViewModel, platform-specific binding. Check for duplicates and missing bindings. Create a Koin startup/init function for each platform.

The Phase 5 scaffold creates the platform init functions (`initKoin()` for any host; `doInitKoin()` for iOS Swift consumption). Verification of the full binding graph happens incrementally as each phase lands bindings. The final pass in Phase 15 will run `KoinApplication.verify()` from a `commonTest` to ensure no missing or duplicate bindings — this is documented in `final-coverage-audit.md`'s checklist.

Phase 8.12 verified via the canonical 5-build:
- `:shared:compileDebugKotlinAndroid` ✅
- `:shared:compileKotlinDesktop` ✅
- `:shared:compileKotlinIosArm64` ✅
- `:composeApp:compileKotlinDesktop` ✅
- `:app:assembleDebug` ✅
