# Phase 8.12 — Koin wiring plan

Goal: populate `SharedModule.kt` (commonMain) and the three `PlatformModule.{android,ios,desktop}.kt` actuals with everything Phase 6-8 produced, so by end of Phase 8 the DI graph compiles on all 3 targets. Phase 9 (ViewModels) layers on top.

## commonMain — `sharedModule`

Add to existing `sharedModule` (currently only complaint use cases):

```kotlin
// ---- Sources / repositories (52 repos from Phase 7) ----
factory<BaseMangaRepository> { params -> /* dispatch on params[0]: MangaSource */ }
factory { AzoraRepositoryv2(get(), get(), get()) }
factory { AasqRepositoryv2(get(), get(), get()) }
factory { DilarRepository(get(), get(), get()) }
factory { DilarV2Repository(get(), get(), get()) }
factory { LavatoonsRepositoryv2(get(), get(), get()) }
factory { MangaLekRepositoryv2(get(), get(), get()) }
factory { MangamelloRepository(get(), get(), get()) }
factory { MangamelloPlusRepository(get(), get(), get()) }
factory { MangaParkRepositoryAr(get(), get(), get()) }
factory { MangatukRepository(get(), get(), get()) }
factory { ProMangaRepository(get(), get(), get()) }
factory { ProchanRepository(get(), get(), get()) }
factory { SwatMangaRepository(get(), get(), get()) }
factory { TeamXRepositoryv2(get(), get(), get()) }
factory { ComickRepository(get(), get(), get()) }
factory { MangaParkRepository(get(), get(), get()) }
factory { BatcaveRepository(get(), get(), get()) }
factory { BatotoEnRepositoryv2(get(), get(), get()) }
factory { DemonicScansRepository(get(), get(), get()) }
factory { MangaBuddyRepositoryV2(get(), get(), get()) }
factory { ManhwatopRepositoryV2(get(), get(), get()) }
factory { ReadComicOnlineRepository(get(), get(), get()) }
factory { TapasticRepository(get(), get(), get()) }
factory { ZazamangaRepository(get(), get(), get()) }
factory { MangaParkRepositoryEs(get(), get(), get()) }
factory { MangaParkRepositoryEs419(get(), get(), get()) }
factory { InMangaRepository(get(), get(), get()) }
factory { ManhwawebEsRepository(get(), get(), get()) }
factory { OlympusbibliotecaRepository(get(), get(), get()) }
factory { TaurusFansubEsRepository(get(), get(), get()) }
factory { RaijinScanRepository(get(), get(), get()) }
factory { MangaOrigineRepository(get(), get(), get()) }
factory { ComickRepositoryId(get(), get(), get()) }
factory { KomikCastRepository(get(), get(), get()) }
factory { KomikuRepository(get(), get(), get()) }
factory { ComickRepositoryIt(get(), get(), get()) }
factory { MangaParkRepositoryIt(get(), get(), get()) }
factory { MangaworldItRepository(get(), get(), get()) }
factory { ComickRepositoryPtBr(get(), get(), get()) }
factory { FlowerMangaRepository(get(), get(), get()) }
factory { ManhastroRepository(get(), get(), get()) }
factory { MediocretoonsRepository(get(), get(), get()) }
factory { SussytoonsRepository(get(), get(), get()) }
factory { ComickRepositoryRu(get(), get(), get()) }
factory { DesuRepository(get(), get(), get()) }
factory { MangahubRepository(get(), get(), get()) }
factory { SenkuroRepository(get(), get(), get()) }
factory { ComickRepositoryTr(get(), get(), get()) }
factory { TimenaightRepository(get(), get(), get()) }
factory { WebtoonhattiRepository(get(), get(), get()) }
factory { WebtoontrRepository(get(), get(), get()) }

// ---- ApiClient + DataStoreHelper + SourcesDao ----
// These are common interfaces but constructed differently per platform → bind in platformModule()
```

## platformModule — Android actual

```kotlin
single { ApiClient(get()) } // OkHttp engine wired via Ktor in HttpClientFactory.android.kt
single { DataStoreHelper(SettingsFactory(get()).create("yami_settings")) }
single<SourcesDao> { get<AppDatabase>().sourcesDao() }
// AppDatabase already wired in Phase 6 db module — verify present

// ---- expect/actual actuals ----
single { SettingsFactory(get()) }              // 8.1
single<ConnectivityObserver> { AndroidConnectivityObserver(get()) }   // 8.2
single<UserIdProvider> { AndroidUserIdProvider(get()) }              // 8.3
single<DeviceInfoProvider> { AndroidDeviceInfoProvider(get()) }      // 8.3
single { NotificationPresenter(get()) }        // 8.4
single { AppFileSystem(get()) }                // 8.5
single { CbzWriter(get()) }                    // 8.5
single { CbzReader(get()) }                    // 8.5 (commonMain)
single { BackgroundJobScheduler(get()) }       // 8.6
single { SecureStorage(get()) }                // 8.7
single { AnalyticsClient(get()) }              // 8.8
single { CrashReporter() }                     // 8.8
single { PushTokenProvider() }                 // 8.8
single { RemoteDocStore() }                    // 8.8
single { AdProvider(get()) }                   // 8.9
single { AppUpdateClient(get()) }              // 8.9
single { InAppReviewClient(get()) }            // 8.9
single { ConsentFlowClient(get()) }            // 8.9
single { ImageDecoderRegistry() }              // 8.10
single<ScreenshotProvider> { AndroidScreenshotProvider(get()) }      // 8.11
single<DominantColorExtractor> { AndroidDominantColorExtractor() }   // 8.11
```

## platformModule — iOS actual

Same shape, but constructors take no Context — verify each actual ctor signature. Noops bind to the iOS actuals (which return null/empty for unsupported services).

## platformModule — Desktop actual

Same shape. Construct with no Context. Noops for Firebase/AdMob/PlayStore.

## detectDeviceTier (the other half of Phase 8.12)

Create `shared/src/commonMain/kotlin/me/manga/yamiapk/core/util/heap/DeviceTier.kt`:

```kotlin
enum class DeviceTier { LOW, MID, HIGH }

expect fun detectDeviceTier(): DeviceTier
```

Android actual: read total RAM via `ActivityManager.MemoryInfo`. <2GB → LOW; 2-4GB → MID; >4GB → HIGH.
iOS actual: use `NSProcessInfo.processInfo.physicalMemory`. Same thresholds.
Desktop actual: `Runtime.getRuntime().maxMemory()` ÷ JVM heap, or via `OperatingSystemMXBean` to get host RAM if `com.sun.management.OperatingSystemMXBean` cast is available.

## After Phase 8.12

- Verify 5 builds pass: `:shared:compileKotlinDesktop :shared:compileKotlinIosArm64 :composeApp:compileKotlinDesktop :composeApp:compileKotlinIosArm64 :app:assembleDebug`
- Update `migration/koin-graph-report.md` with new bindings
- Update `migration/progress-state.json` phase-8 → "completed"
- Commit `[phase-8 batch-8.12] populate Koin SharedModule + platformModule actuals + detectDeviceTier`
- Push to kmp-migration
- Advance to Phase 9
