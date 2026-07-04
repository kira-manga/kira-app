# KMP Migration Plan

> A concrete, phase-by-phase plan for moving every part of `yami-manga-apk-main` into `yami-kmp` with 100% behavior parity. Derived from `project-inventory.md`, `module-map.md`, `feature-map.md`, and `android-only-dependencies.md`.

## Target architecture (KMP)

```
yami-kmp/
├── app/                                 # Android launcher (replaces source `:app`)
│   ├── build.gradle.kts                 # Android-only deps: Firebase, AdMob, Hilt-free, Play services
│   ├── src/main/AndroidManifest.xml     # Activities, services, providers, receivers, permissions
│   ├── src/main/java/me/manga/yamiapk/
│   │   ├── MyApp.kt                     # Application, startKoin(...), Firebase.init, AdMob.init
│   │   ├── MainActivity.kt              # setContent { App() }  → calls composeApp/commonMain
│   │   ├── crash/CrashActivity.kt
│   │   ├── firebase_cores/messaging/MyFirebaseMessagingService.kt
│   │   ├── work/                        # WorkManager workers (Android-only)
│   │   ├── dex/                         # Runtime DEX plugin loader (Android-only)
│   │   ├── ad_mob/                      # AdMob implementations (banner, native, rewarded)
│   │   └── google_play_cores/           # In-app update, review, UMP
│   └── google-services.json
│
├── composeApp/                           # Shared Compose Multiplatform UI
│   └── src/
│       ├── commonMain/kotlin/me/manga/yami/
│       │   ├── App.kt                   # @Composable App() entry point used by all platforms
│       │   ├── theme/                   # Color, Theme, Type
│       │   ├── navigation/              # NavGraphV2, routes/, double_click/, NavigationLock, safePopBackStack
│       │   └── presentation/            # common/* + features/*  (UI only — ViewModels live in shared)
│       └── (no platform main dirs for now; UI is shared)
│
├── shared/                              # KMP business / data / domain code
│   └── src/
│       ├── commonMain/kotlin/me/manga/yami/
│       │   ├── di/                      # Koin modules (per area, like Hilt modules in source)
│       │   ├── domain/                  # Models, repository interfaces, services
│       │   ├── data/local/              # Room KMP database, DAOs, entities, type converters
│       │   ├── data/remote/             # Ktor ApiClient, request/response wrappers
│       │   ├── sources_repositry/       # 40+ per-language source repos using Ktor + Ksoup
│       │   ├── core/                    # state, progress, storage, util, network_connectivity, file
│       │   ├── presentation/            # ALL ViewModels (lifecycle-viewmodel KMP)
│       │   └── BrowserHeaders.kt        # Ktor headers plugin
│       ├── androidMain/kotlin/me/manga/yami/
│       │   ├── di/AndroidPlatformModule.kt
│       │   ├── data/local/DatabaseBuilder.android.kt
│       │   ├── core/storage/Settings.android.kt
│       │   ├── core/avif/                # Android AVIF decoder
│       │   ├── core/util/notification/    # Android NotificationManager
│       │   ├── core/network_connectivity/ # Android ConnectivityManager
│       │   └── … (every expect's android actual)
│       ├── iosMain/kotlin/me/manga/yami/
│       │   ├── di/IosPlatformModule.kt
│       │   ├── data/local/DatabaseBuilder.ios.kt   # uses Room KMP + sqlite-bundled
│       │   ├── core/storage/Settings.ios.kt        # NSUserDefaults
│       │   └── …
│       ├── desktopMain/kotlin/me/manga/yami/
│       │   ├── di/DesktopPlatformModule.kt
│       │   ├── data/local/DatabaseBuilder.desktop.kt
│       │   ├── core/storage/Settings.desktop.kt    # PreferencesSettings
│       │   └── …
│       ├── commonTest/                  # kotlin.test specs
│       ├── androidUnitTest/             # existing example test
│       ├── iosTest/
│       └── desktopTest/
│
├── desktopApp/                          # Desktop entry point
│   └── src/jvmMain/kotlin/Main.kt       # application { Window(...) { App() } }
│
├── iosApp/                              # Xcode project (scaffolded; compiled on macOS)
│   ├── iosApp.xcodeproj/
│   └── iosApp/ContentView.swift         # ComposeUIViewController { App() }
│
├── gradle/
│   ├── libs.versions.toml               # KMP version catalog (replaces source's)
│   └── wrapper/gradle-wrapper.properties  # Gradle 8.x stable
├── build.gradle.kts                     # plugins {} for all modules
├── settings.gradle.kts                  # include(":app", ":composeApp", ":shared", ":desktopApp")
└── migration/                           # this directory
```

---

## Phase plan (15 phases, executed in order)

### Phase 0 — Inventory ✅ (this session)

- `migration/project-inventory.md` written.

### Phase 1 — Project graph + module/feature maps 🟡 (this session)

- `migration/project-graph.md` ← hierarchical text view of the source tree with destinations.
- `migration/project-graph.json` ← machine-readable file accountability.
- `migration/module-map.md` ✅
- `migration/feature-map.md` ✅
- `migration/android-only-dependencies.md` ✅
- `migration/kmp-migration-plan.md` ← this file ✅

### Phase 2 — Library research + version locks (this session)

- `migration/library-decisions.md` lists every library, its locked version, official source URL, and the date checked.
- Pins: Kotlin, AGP, Gradle wrapper, KSP, Compose MP, Compose plugin, Koin, Room KMP, sqlite-bundled, lifecycle-viewmodel, lifecycle-viewmodel-compose, navigation-compose, Ktor, kotlinx-serialization, kotlinx-coroutines, kotlinx-datetime, multiplatform-settings, ksoup, coil3, Napier/Kermit.
- `migration/dependency-replacement-report.md` documents each replacement (old → new, reason, files affected).

### Phase 3 — KMP project structure (this session if context allows)

- Create top-level `build.gradle.kts` + `settings.gradle.kts`.
- Create version catalog `gradle/libs.versions.toml`.
- Create `app/build.gradle.kts` (Android application module).
- Create `shared/build.gradle.kts` (Kotlin multiplatform module with Android, iOS, Desktop targets, KSP for Room, Koin, kotlinx-serialization).
- Create `composeApp/build.gradle.kts` (Compose Multiplatform module).
- Create `desktopApp/build.gradle.kts`.
- Stub `iosApp/` Xcode project files.
- Stub source set directory structure (empty `package` files where needed).
- Stub `MyApp.kt`, `MainActivity.kt`, `App.kt`, `desktopApp/Main.kt` so `gradlew.bat :composeApp:compileDebugKotlinAndroid` and `:shared:compileKotlinJvm` succeed against the empty project.
- Commit + push.

### Phase 4 — Move pure Kotlin code (next session)

- Move every entity in `data/local/entity/`, every type converter (with `java.time.*` → `kotlinx.datetime` substitution), every model in `domain/model/`, every pure utility in `core/util/`, every state class in `core/states/`, every progress class in `core/progress/`, every file-size util in `core/file/`, every use case in `presentation/features/*/usecase/`, every repository interface in `domain/repos/`, the source repository abstract base classes (`sources_repositry/BaseMangaRepository.kt`, `sources_repositry/EmptyMangaRepository.kt`, `sources_repositry/common/*`) — all into `shared/commonMain`.
- Verify each file compiles with `gradlew.bat :shared:compileKotlinJvm`.
- Update `progress-state.json` per file.

### Phase 5 — Hilt → Koin

- For each of the 16 Hilt modules, write the equivalent Koin module. ViewModel bindings use `viewModel { … }`. Singletons → `single { … }`. Factory → `factory { … }`. Multi-binding (`@IntoSet`, `@IntoMap`) → Koin `getAll<T>()`.
- Remove `kotlin-kapt`, `dagger.hilt.android.plugin`, `androidx.hilt:*`, `com.google.dagger:hilt-android*` from `app/build.gradle.kts`.
- For each ViewModel: replace `@HiltViewModel` annotation + `@Inject constructor` with a Koin `viewModel { … }` registration.
- For `@HiltWorker`: implement a custom `WorkerFactory` in `androidMain` that pulls deps from Koin.
- Verify with `gradlew.bat :app:assembleDebug`.
- Output `migration/di-migration-report.md` + `migration/koin-graph-report.md`.

### Phase 6 — Room → Room KMP

- Add `androidx.room:room-runtime`, `room-compiler` (KSP for `kspAndroid`, `kspIosSimulatorArm64`, `kspIosArm64`, `kspIosX64`, `kspDesktop`), `androidx.sqlite:sqlite-bundled` in `shared/build.gradle.kts`.
- Apply Room Gradle plugin (`androidx.room:room-gradle-plugin`).
- Move `MangaDatabase`, every `*Dao`, every `*Entity`, every type converter, `Migrations.kt` into `shared/commonMain/.../data/local/`. Flip `exportSchema = true`. Schemas exported to `shared/schemas/`.
- Implement `expect object AppDatabaseConstructor : RoomDatabaseConstructor<MangaDatabase>` + `androidMain`/`iosMain`/`desktopMain` actuals using the documented `Room.databaseBuilder` per-platform pattern.
- Verify `gradlew.bat :shared:compileKotlinJvm` and `:app:assembleDebug` (Android still loads the DB from disk; iOS migration verified by macOS owner later).
- Output `migration/database-migration-report.md`.

### Phase 7 — Retrofit → Ktor

- Add Ktor deps in `shared/build.gradle.kts` (`ktor-client-core`, `ktor-client-okhttp` Android, `ktor-client-darwin` iOS, `ktor-client-cio` Desktop, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`, `ktor-client-logging`).
- Write `ApiClient` interface in `commonMain` matching `IMangaDataApiServices`'s 17 endpoints (all URL-based).
- Implement `KtorApiClient` in `commonMain` using a single `HttpClient` configured via `expect/actual fun createHttpClient(): HttpClient` (which wires `OkHttp`/`Darwin`/`CIO` per platform).
- Add `BrowserHeaders` plugin to mimic `BrowserHeadersInterceptor.kt`.
- Add `Logging` plugin matching the OkHttp interceptor behavior.
- Add a Ktor cache configuration to replace `core/network_cache/forceCacheForDados.kt`.
- Port `ProgressInterceptor.kt` to a Ktor request/response observer.
- Replace `jsoup` calls in every source repository with `Ksoup`. Verify each port compiles and the parsing produces identical output for a sample HTML fixture (sample test on Android only — full validation deferred to runtime smoke test).
- Output `migration/dependency-replacement-report.md` entries.

### Phase 8 — expect/actual platform abstractions

- Storage: `Settings` via `multiplatform-settings`.
- Secure storage: `interface SecureStorage` + Android Keystore impl / iOS Keychain impl / Desktop encrypted file impl.
- Logging: chosen logger (Phase 2 decides Napier or Kermit) — single `interface Logger`.
- Permissions: `interface PermissionLauncher` (Android Compose Permission, iOS info.plist + UNUserNotificationCenter, Desktop = always granted).
- File system: `interface AppFileSystem`.
- Resources: covered by Compose MP `compose.resources`.
- Date/time: `kotlinx.datetime`.
- Connectivity: `ConnectivityObserver` already an interface in source.
- Database builder: covered by Phase 6.
- AppContext / DeviceInfo / NotificationPresenter / BackgroundJobScheduler / WebViewHost / ZoomableImage / Analytics / CrashReporter / AdProvider / ConsentFlow / AppUpdateChecker / InAppReviewLauncher / PushMessaging / RemoteStore / DominantColorExtractor / ScreenshotProvider / CbzReader+Writer.
- Output `migration/expect-actual-report.md`.

### Phase 9 — ViewModels + Navigation

- Move all 24 ViewModels into `shared/commonMain/.../presentation/.../viewmodel/`.
- Convert any `LiveData` → `StateFlow`. (Source already uses `StateFlow` per Phase 1 inventory; should be no-op.)
- Convert any `SavedStateHandle` usage to KMP-compatible `androidx.lifecycle:lifecycle-viewmodel-savedstate` (KMP).
- Add Koin `viewModel { … }` registration for each.
- Routes: move `navigation/routes/*` to `composeApp/commonMain/.../navigation/routes/` (they are already `@Serializable` — no changes).
- Move `NavGraphV2`, `NavigationLock`, `safePopBackStack`, `double_click/*` to `composeApp/commonMain/.../navigation/`.
- Verify Android navigation flows still work via runtime smoke test.
- Output `migration/navigation-migration-report.md` + entries in `ui-migration-report.md`.

### Phase 10 — Compose UI to commonMain

- Move every `@Composable` file from `presentation/common/*` and `presentation/features/*` into `composeApp/commonMain/.../presentation/`.
- Move `theme/*` into `composeApp/commonMain/.../theme/`.
- Move resources (strings, drawables, fonts, colors) into `composeApp/src/commonMain/composeResources/` (preserving locale folders `values-*/`).
- Replace `android.content.Context`-bound Compose APIs with KMP-safe APIs (e.g., `painterResource(Res.drawable.ic_…)`, `stringResource(Res.string.…)`).
- Wrap Android-only composables (`BannerAdView`, `NativeAdListItem`, WebView, AVIF previews) in expect/actual or `androidMain` factories called via Compose `expect @Composable`.
- Verify with `gradlew.bat :composeApp:compileDebugKotlinAndroid` and `:composeApp:compileKotlinDesktop`.
- Output `migration/ui-migration-report.md` + `migration/resource-migration-report.md`.

### Phase 11 — Wire Android app to shared modules

- `app/src/main/java/.../MainActivity.kt` becomes a thin wrapper calling `setContent { App() }` where `App()` lives in `composeApp/commonMain`.
- `MyApp.kt` initializes Koin via `startKoin { modules(sharedModules + androidPlatformModule) }`, Firebase, AdMob (UMP), in-app update.
- Workers refactored to use Koin `WorkerFactory`.
- DEX plugin loader stays in `app/` (or `shared/androidMain`) and registers loaded plugins into the shared `SourceRegistry`.
- Verify `gradlew.bat :app:assembleDebug` and smoke-test the running APK.

### Phase 12 — iOS readiness

- Scaffold `iosApp/iosApp.xcodeproj` and `iosApp/ContentView.swift` (uses Compose Multiplatform `ComposeUIViewController { App() }`).
- Configure `shared/build.gradle.kts` for `iosArm64`, `iosSimulatorArm64`, `iosX64`. Add KSP for each iOS target. Add `cocoapods` block if needed for any iOS-specific dep.
- Implement all `iosMain` actuals.
- Document macOS validation commands:
  - `./gradlew :shared:compileKotlinIosSimulatorArm64`
  - `./gradlew :shared:compileKotlinIosX64`
  - `./gradlew :shared:compileKotlinIosArm64`
  - Build the Xcode project for the simulator.
- Output `migration/ios-readiness-report.md`.

### Phase 13 — Desktop readiness

- `desktopApp/src/jvmMain/kotlin/Main.kt`: `application { Window(...) { App() } }`.
- Configure Compose Multiplatform Desktop in `composeApp/build.gradle.kts`.
- Verify `gradlew.bat :composeApp:desktopJar`, `:composeApp:run`.
- Document the no-op Android-only features on Desktop in the report.
- Output `migration/desktop-readiness-report.md`.

### Phase 14 — Validation

- Run on Windows:
  - `gradlew.bat :app:assembleDebug`
  - `gradlew.bat :app:assembleRelease` (with signing config / env vars — document the env vars in `release-readiness-report.md`).
  - `gradlew.bat :shared:compileKotlinJvm`
  - `gradlew.bat :composeApp:compileDebugKotlinAndroid`
  - `gradlew.bat :composeApp:compileKotlinDesktop`
  - `gradlew.bat :desktopApp:compileKotlinDesktop`
  - `gradlew.bat :composeApp:run` (smoke test on Desktop)
  - `gradlew.bat :app:testDebugUnitTest` (preserved sample test)
- Document failures + fixes in `migration-log.md`.
- Update `progress-state.json`.

### Phase 15 — Final audit + final-report.md

- Compare original Android tree to KMP tree file-by-file in `file-accountability.md`.
- Run the audit per Section 32.
- Write `final-coverage-audit.md` and `final-report.md` per Section 24.

---

## Session boundaries (best-effort)

| Session | Phases targeted |
|---|---|
| Session 1 (this session) | Phases 0, 1, 2, **start** of 3 |
| Session 2 | Finish Phase 3, run Phase 4 |
| Session 3 | Phase 5 (Hilt → Koin) |
| Session 4 | Phase 6 (Room → Room KMP) |
| Session 5 | Phase 7 (Retrofit → Ktor) + Phase 8 (expect/actual) start |
| Session 6 | Finish Phase 8 + Phase 9 (ViewModels + Navigation) |
| Session 7 | Phase 10 (UI to commonMain) |
| Session 8 | Phase 11 (Wire Android app) |
| Session 9 | Phase 12 + 13 (iOS + Desktop readiness) |
| Session 10 | Phase 14 (validation) |
| Session 11 | Phase 15 (final audit + report) |

This is a rough guide — actual cadence depends on context budget per session.

---

## Risk register

| Risk | Mitigation |
|---|---|
| Compose MP doesn't fully support Material 3 adaptive layouts as Android does | If a layout breaks, fall back to Material 3 standard layout for non-Android and keep Android-original under `androidMain`. Document in `ui-migration-report.md`. |
| `ksoup` parses slightly differently from `jsoup` | Phase 7 includes a per-source spot-check on saved HTML fixtures. Any divergence is fixed; if not fixable, the source is flagged blocked in `pending-work.md` (Android can stay on jsoup via `androidMain` actual). |
| Telephoto zoomable image has no KMP alternative | Reader uses `expect/actual ZoomableImage`. Non-Android uses a custom Compose zoom impl built on `Modifier.transformable`. |
| Coil 3 AVIF on iOS/Desktop | Coil 3 doesn't ship AVIF; non-Android falls back to JPEG/PNG. Behavior parity is preserved on Android. |
| KSP for all iOS targets times out | Use Room Gradle plugin (`androidx.room:room-gradle-plugin`) which handles KSP correctly for all KMP targets. |
| `runtime-livedata` is actually used in shared code | Audit in Phase 4; if used, convert observers to `collectAsState()`. |
| Firebase analytics events scattered across source | Phase 8 collects all events behind `interface Analytics`; events captured in `observability-report.md`. |
| Manifest entries / permissions / signing — easy to drop accidentally | Phase 11 includes a manifest diff between `yami-manga-apk-main/app/src/main/AndroidManifest.xml` and `yami-kmp/app/src/main/AndroidManifest.xml`. |
| The Gradle 9 milestone wrapper would break KMP plugins | Decision logged: use Gradle 8.x stable. |

---

## Definition of Done (mirrors Section 23 of the binding spec)

- Android APK builds and runs identically to the source (smoke test: launch, login flow if any, library, reader, downloads, settings, theme toggle).
- Shared modules compile for JVM.
- Desktop runs and shows the app.
- iOS targets compile (validated on macOS by the project owner, not by this agent on Windows).
- No feature deleted, mocked, stubbed, or silently changed.
- Every file in source has a row in `file-accountability.md` with a known status.
- Migration reports written: `di-migration-report.md`, `database-migration-report.md`, `dependency-replacement-report.md`, `expect-actual-report.md`, `navigation-migration-report.md`, `ui-migration-report.md`, `resource-migration-report.md`, `koin-graph-report.md`, `accessibility-report.md`, `localization-rtl-report.md`, `observability-report.md`, `release-readiness-report.md`, `ios-readiness-report.md`, `desktop-readiness-report.md`, `runtime-smoke-test.md`, `final-coverage-audit.md`, `final-report.md`.
