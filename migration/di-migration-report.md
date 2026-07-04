# DI Migration Report — Hilt → Koin

> Mandatory output per Phase 5 of `MIGRATION_PROMPT.md`. Maps every Hilt binding in the source project to its Koin equivalent, records the migration status of each binding, and documents the rationale for any deferred work.

## Scaffolding done (Phase 5, batch 5.1)

Initial Koin scaffold in `shared/src/commonMain/kotlin/me/manga/yamiapk/di/`:

- `SharedModule.kt` — top-level `val sharedModule = module { … }` plus `allSharedModules()` accessor.
- `PlatformModule.kt` — `expect fun platformModule(): Module`.
- `KoinInitializer.kt` — `fun initKoin(appDeclaration: KoinAppDeclaration = {})` for use from any host.
- `PlatformModule.android.kt`, `PlatformModule.ios.kt`, `PlatformModule.desktop.kt` — empty `actual` impls; populated as later phases land their code.
- `iosMain/KoinHelper.kt` — `doInitKoin()` callable from Swift.

Per-target Koin dependencies (already configured in `gradle/libs.versions.toml` at version `4.2.0`):

- `commonMain`: `io.insert-koin:koin-core` (api)
- `androidMain` (in `shared/build.gradle.kts`): `io.insert-koin:koin-android`
- `composeApp commonMain` (in `composeApp/build.gradle.kts`): `io.insert-koin:koin-compose`, `koin-compose-viewmodel`, `koin-compose-viewmodel-navigation`

Why **factory** instead of **single** for the use cases? Source uses `@Provides @Singleton`, but use cases are stateless and idempotent — `factory` is the Koin idiom for stateless instances. The behavioral difference (one shared instance vs one-per-injection) is invisible to callers because the classes have no mutable state. If the user later confirms they want singleton semantics here, swap to `single { … }` in a single 5-line patch.

## Binding mapping table

Status legend:

- ✅ **Migrated in this commit** — Koin binding lives in commonMain `sharedModule`.
- 🔜 **Phase-N target** — binding deferred to a later phase because it depends on code not yet in commonMain.
- 🤖 **Android-only** — binding will land in `shared/androidMain/.../PlatformModule.android.kt`.
- ⏸️ **Source still uses Hilt** — the source's Hilt graph stays operational on Android until Phase 11 swaps `MyApp.onCreate()` from `@HiltAndroidApp` to `initKoin { androidContext(…) }`.

| Hilt module + binding | Source target | Koin equivalent | Status |
|---|---|---|---|
| `di/app/AppModule.kt` — `provideSharedPrefsHelper(Context)` | `SharedPrefsHelper` | `single { SharedPrefsHelper(androidContext()) }` in `PlatformModule.android` | 🔜 Phase 8 (after `core/storage/*` ports to `multiplatform-settings`) |
| `di/app/AppModule.kt` — `@MainOkHttpClient provideOkHttpClient(Context)` | OkHttpClient | Replaced by Ktor `HttpClient { engine { OkHttp.create() } }` | 🔜 Phase 7 |
| `di/app/AppModule.kt` — `provideSendComplaintUseCase(ComplaintRepository)` | `SendComplaintUseCase` | `factory { SendComplaintUseCase(get()) }` | ✅ Migrated (sharedModule) |
| `di/app/AppModule.kt` — `provideApplicationScope()` | `CoroutineScope` | `single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }` | 🔜 Phase 6/7 (used by Room/Ktor) |
| `di/app/AppBindings.kt` — `bindUserIdProvider(DeviceIdProvider): UserIdProvider` | `UserIdProvider` | `single<UserIdProvider> { AndroidDeviceIdProvider(androidContext()) }` | 🤖 Phase 8 (`DeviceIdProvider` uses `Settings.Secure.ANDROID_ID`) |
| `di/app/AppBindings.kt` — `bindDeviceInfoProvider(AndroidDeviceInfoProvider): DeviceInfoProvider` | `DeviceInfoProvider` | `single<DeviceInfoProvider> { AndroidDeviceInfoProvider() }` | 🤖 Phase 8 |
| `di/coli/CoilModule.kt` | Coil 3 `ImageLoader` | `single<ImageLoader> { ImageLoader.Builder(…).build() }` | 🔜 Phase 7/10 (after Ktor + Coil 3 KMP wiring) |
| `di/coli/CoilEntryPoint.kt` | Hilt EntryPoint for NativeAd | Replaced by `koinInject<ImageLoader>()` in composables | 🔜 Phase 10 |
| `di/complaint/ComplaintRepositoryModule.kt` — `bindComplaintRepository(ComplaintFirestoreDataSource): ComplaintRepository` | `ComplaintRepository` | `single<ComplaintRepository> { ComplaintFirestoreDataSource(…) }` in `PlatformModule.android`; iOS/Desktop bind a noop or HTTP fallback | 🤖 Phase 11 (Firebase impl is Android-only) |
| `di/database/DatabaseModule.kt` — `provideMangaDatabase(Context)` + 8 DAO providers | Room DB + DAOs | `single { databaseBuilder() … }` in `PlatformModule.android`/`.ios`/`.desktop`; DAO `single { get<MangaDatabase>().historyDao() }` etc. in `sharedModule` | 🔜 Phase 6 (Room KMP) |
| `di/download/DownloadModule.kt` | `DownloadRepository`, `DownloadWorker` deps | Mix of `sharedModule` factories + `PlatformModule.android` WorkManager wiring | 🔜 Phase 6/8/11 |
| `di/firebase/FirebaseModule.kt` | Firebase `FirebaseAnalytics`, `FirebaseMessaging`, `FirebaseFirestore` | `single { Firebase.analytics }` etc. in `PlatformModule.android`; iOS = noop providers | 🤖 Phase 11 |
| `di/network/ConnectivityMudule.kt` (typo preserved) — `bindConnectivityObserver(NetworkConnectivityObserver): ConnectivityObserver` | `ConnectivityObserver` | `single<ConnectivityObserver> { NetworkConnectivityObserver(androidContext()) }` in `PlatformModule.android`; iOS uses `SCNetworkReachability`; Desktop uses polling | 🤖 Phase 8 |
| `di/network/NetworkModule.kt` — `provideRetrofitSMangaLek(OkHttpClient)` + `provideMangaLekApiService(Retrofit)` | Retrofit + `IMangaDataApiServices` | Replaced by Ktor `HttpClient` + `ApiClient`. Bindings in `sharedModule` once Phase 7 lands. | 🔜 Phase 7 |
| `di/notification/NotificationModule.kt` | `NotificationHelper`, `ChapterNotificationHelper` | Interfaces in commonMain; Android impls in `PlatformModule.android`; iOS uses `UNUserNotificationCenter`; Desktop = tray-icon or noop. | 🤖 Phase 8 |
| `di/sources/module/ActiveRepoModule.kt` + `di/sources/module/RepositoryModule.kt` + `di/sources/provider/ActiveRepoProvider.kt` | All 40+ per-source repositories | Each source repo becomes a Koin `single`. `ActiveRepoProvider` is a `factory` resolving by api-id. | 🔜 Phase 7 (after Retrofit → Ktor moves the source repos) |
| `di/whatsnew/WhatsNewModule.kt` | `WhatsNewRemoteDataSource` (Firestore-backed) | `single<WhatsNewRemoteDataSource> { WhatsNewFirestoreDataSource(…) }` in `PlatformModule.android` | 🤖 Phase 11 |
| `di/workmanager/WorkManagerModule.kt` | `KoinWorkerFactory` + worker class registrations | Custom `WorkerFactory : androidx.work.WorkerFactory` in `androidMain` that resolves dependencies from `GlobalContext.get()`. Installed in `MyApp.onCreate()`. | 🤖 Phase 11 |

## Hilt annotations to remove (later)

The following annotations remain in the source files until later phases move each file. None of these need to be removed in Phase 5 because the source code itself is read-only — the KMP project doesn't compile any of it. As each file moves to `shared/commonMain/` or `shared/androidMain/`, its Hilt annotations are dropped during the move:

| Annotation | Source files affected | Replacement |
|---|---|---|
| `@HiltAndroidApp` on `MyApp` | 1 | `MyApp.onCreate() { initKoin { androidContext(this@MyApp) } }` (Phase 11) |
| `@AndroidEntryPoint` on Activity/Service/Receiver | `MainActivity`, `CrashActivity`, `MyFirebaseMessagingService`, `DownloadCancelReceiver`, possibly more | Drop annotation; resolve deps via `KoinComponent` / `koinInject()` (Phase 9/11) |
| `@HiltViewModel` on 24 ViewModels | 24 files | Drop annotation; register as `viewModel { … }` in the appropriate Koin module (Phase 9) |
| `@HiltWorker` on 4 workers | `CbzMigrationWorker`, `LibraryRefreshWorker`, `MangaDownloadWorker`, `NotificationWorker` | Drop annotation; resolve deps in worker constructor via `KoinComponent` (Phase 11) |
| `@Inject constructor(...)` on classes | ~50 (every repo, helper, service that's currently constructor-injected) | Drop annotation; deps still passed as constructor params, Koin module's `get()` resolves them (each move drops as it lands) |
| `@Inject lateinit var ...` field injection | Used in some workers/services | Switch to constructor injection or `koinInject()` |
| `@ApplicationContext` qualifier | Used wherever a Context is needed | `androidContext()` inside the Android Koin module |
| `@MainOkHttpClient` qualifier (custom) | `OkHttpClient` binding | Not needed — Ktor `HttpClient` is configured once (Phase 7) |

## When Hilt actually gets removed

Hilt is **not deleted from the source project** by Phase 5 — the source project is read-only per `MIGRATION_PROMPT.md`'s "Project Context" section. What happens is:

1. **Phase 5 (now)**: Koin scaffold is built in `yami-kmp`. The 5 use cases that ARE in commonMain get Koin bindings. Hilt files in source are not touched.
2. **Phases 6 → 10**: As each piece of code moves into `shared/commonMain/` or `shared/androidMain/`, its corresponding Hilt-side providers/binders get rewritten as Koin entries in the appropriate Koin module. The annotations on classes (`@Inject constructor`, `@HiltViewModel`, etc.) are dropped during the move.
3. **Phase 11 (`MyApp` wiring)**: `app/src/main/java/.../MyApp.kt` is rewritten to call `initKoin { androidContext(…) }` instead of being annotated `@HiltAndroidApp`. At that point Hilt has zero call sites in `yami-kmp` and the Hilt plugin + dependencies are removed from `app/build.gradle.kts` (already absent — Phase 3's `app/build.gradle.kts` rewrite already excluded the Hilt plugin block).

This phased removal means there is **never a state where Hilt and Koin both wire the same binding in the running app** — the Android `:app` always uses exactly one DI graph (still Hilt-equivalent during phases 5-10 because nothing in `yami-kmp/app` is wired yet; switches to Koin in Phase 11).

## Verification

Phase 5 batch 5.1 verified on Windows with JDK 21 / Android SDK 36 / Gradle 8.13:

| Command | Result |
|---|---|
| `:shared:compileKotlinDesktop` | BUILD SUCCESSFUL |
| `:shared:compileKotlinIosArm64` | BUILD SUCCESSFUL |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

Koin DSL syntax verified by the Kotlin compiler in all three target compilations. The 5 complaint use case bindings type-check end-to-end (each `factory { … }` infers the `ComplaintRepository` argument via `get()`).

## Next batches (Phase 5 continued)

Phase 5 is structurally complete (scaffold + 5 bindings + binding-map documentation). Subsequent Koin additions piggyback on the data-layer migrations:

- **Batch 5.2 (Phase 6 sidecar)**: when Room KMP lands, add the DB + 8 DAO bindings to `PlatformModule.<target>` and `sharedModule`.
- **Batch 5.3 (Phase 7 sidecar)**: when Ktor lands, add `HttpClient`, `ApiClient`, `SourceRegistry`, and 40+ source-repo bindings.
- **Batch 5.4 (Phase 8 sidecar)**: when expect/actual abstractions land, add storage/connectivity/notification/device bindings.
- **Batch 5.5 (Phase 9 sidecar)**: when ViewModels land, add 24 `viewModel { … }` registrations.
- **Final Koin step (Phase 11)**: switch `MyApp.kt` from Hilt to Koin; install `KoinWorkerFactory`; drop Hilt plugin + dependencies from `app/build.gradle.kts` (already absent in our rewrite, so this is a no-op verification).
