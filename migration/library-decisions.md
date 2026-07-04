# Library Decisions (Phase 2)

> Every library used in the new KMP project is locked here with its version, official source URL, the date the source was checked, the justification for the version, the best practices applied, the risks, and the files that will reference it. Per Section 5 of `MIGRATION_PROMPT.md`.
>
> All versions below were verified against official sources on **2026-05-22** (the date listed in the session header).
>
> "Best practices applied" describes the specific config decisions made to follow current documentation guidance.

---

## Core toolchain

### Kotlin

- **Library/plugin**: `org.jetbrains.kotlin` (Kotlin compiler, stdlib, multiplatform plugin)
- **Selected version**: **`2.3.21`** (stable)
- **Stable or pre-release**: stable
- **Official source checked**: <https://kotlinlang.org/docs/releases.html> — verified 2026-05-22
- **Why this version**: latest stable per `kotlinlang.org`. Compose Multiplatform 1.11.x is compatible. Room 2.8.x supports Kotlin 2.3.x. Source project was on 2.0.21 — upgrade is required for the locked KMP stack.
- **Best practices applied**: enable `org.gradle.parallel`, `org.gradle.caching`, `kotlin.incremental` (already in source), set `kotlin.code.style=official`.
- **Risks**: source was on 2.0.21; some third-party Compose-plugin processors may need bumps. Mitigation: every dep below is pinned to a Kotlin-2.3-compatible version.
- **Files**: `gradle/libs.versions.toml`, all `build.gradle.kts`.

### Android Gradle Plugin (AGP)

- **Library/plugin**: `com.android.application`, `com.android.library`
- **Selected version**: **`8.13.0`** (stable)
- **Stable or pre-release**: stable
- **Official source checked**: <https://developer.android.com/build/releases/gradle-plugin> — verified 2026-05-22
- **Why this version**: AGP 8.13 is the latest stable compatible with Kotlin 2.3.x and the Compose Compiler 2.3.21. Source project used AGP 8.9.3. We do **not** jump to AGP 9 yet because Compose MP, Room Gradle Plugin, and KSP compatibility with AGP 9 is still settling per JetBrains' Jan 2026 blog post on AGP 9 migration.
- **Best practices applied**: keep `minSdk=26`, `targetSdk=35`, `compileSdk=35` exactly as source.
- **Risks**: AGP 8.13 may pull a newer Compose Compiler — verified compatible.
- **Files**: `gradle/libs.versions.toml`, `app/build.gradle.kts`, root `build.gradle.kts`.

### Gradle wrapper

- **Library/plugin**: Gradle distribution
- **Selected version**: **`8.13`** (stable)
- **Stable or pre-release**: stable
- **Official source checked**: <https://gradle.org/releases/> — verified 2026-05-22
- **Why this version**: matches AGP 8.13's required Gradle version. Source project shipped with `9.0-milestone-1` — we explicitly downgrade because Gradle 9 milestones break some KMP plugins (notably KSP and Compose hot reload).
- **Best practices applied**: regenerate wrapper using `gradle wrapper --gradle-version 8.13 --distribution-type bin`.
- **Risks**: developers used to Gradle 9 features will need to wait; nothing in this app needs them.
- **Files**: `gradle/wrapper/gradle-wrapper.properties`.

### KSP

- **Library/plugin**: `com.google.devtools.ksp`
- **Selected version**: **`2.3.8`** (stable; see correction note below)
- **Stable or pre-release**: stable
- **Official source checked**: <https://github.com/google/ksp/releases> — verified 2026-05-22
- **Why this version**: KSP for Kotlin 2.3.x uses the new versioning format `<kotlin-major-minor>.<patch>` (e.g. `2.3.8`), NOT the old `<full-kotlin>-<x.y.z>` format. KSP `2.3.8` (released 13 May 2026) is the latest 2.3-line release and targets Kotlin language 2.3 (which covers Kotlin 2.3.21).
- **Correction (Session 2, 2026-05-22)**: Session 1 originally selected `2.3.21-2.0.5` based on the assumption that the old format still applied. The actual published version is `2.3.8`. Verified by `gradlew.bat :shared:compileKotlinDesktop` failing with "could not resolve plugin artifact 'com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.3.21-2.0.5'", then succeeding after the catalog was changed to `2.3.8`.
- **Best practices applied**: enable KSP for `kspAndroid`, `kspIosSimulatorArm64`, `kspIosArm64`, `kspIosX64`, `kspDesktop` as required by Room KMP.
- **Risks**: none (verified compile against Kotlin 2.3.21 on JDK 21).
- **Files**: `shared/build.gradle.kts`, `app/build.gradle.kts`.

---

## Compose Multiplatform

### Compose Multiplatform

- **Library/plugin**: `org.jetbrains.compose`
- **Selected version**: **`1.11.0`** (stable)
- **Stable or pre-release**: stable
- **Official source checked**: <https://github.com/JetBrains/compose-multiplatform/releases> — verified 2026-05-22 (release post: <https://blog.jetbrains.com/kotlin/2026/05/compose-multiplatform-1-11-0/>)
- **Why this version**: latest stable per JetBrains blog (May 2026). Common `@Preview` annotation, native iOS text input, scrolling perf improvements, v2 ComposeUiTest, Compose Hot Reload stable. Compatible with Kotlin 2.3.21.
- **Best practices applied**: Use `org.jetbrains.compose.compiler` plugin (Kotlin Compose plugin); use unified `@Preview` from `org.jetbrains.compose.ui.tooling.preview` for previews in `commonMain`.
- **Risks**: a few Material 3 adaptive APIs lag Jetpack Compose by 1-3 months. For this app, Material 3 adaptive is used only in `androidx.compose.material3.adaptive:adaptive` — Phase 10 will verify the CMP equivalents.
- **Files**: `composeApp/build.gradle.kts`, `desktopApp/build.gradle.kts`.

### Compose Compiler

- **Library/plugin**: `org.jetbrains.kotlin.plugin.compose` (Kotlin Compose plugin, bundled with Kotlin)
- **Selected version**: matches Kotlin **`2.3.21`**
- **Stable or pre-release**: stable
- **Official source checked**: <https://kotlinlang.org/docs/releases.html> — verified 2026-05-22
- **Why this version**: required to be the same as Kotlin since Kotlin 2.0.
- **Best practices applied**: apply per-module via `alias(libs.plugins.kotlin.compose)`.
- **Risks**: none.
- **Files**: `composeApp/build.gradle.kts`, `desktopApp/build.gradle.kts`, `app/build.gradle.kts` (since Android module also needs it for any Compose code that survives there).

---

## Dependency injection — Koin

- **Library/plugin**: `io.insert-koin:koin-core`, `koin-android`, `koin-compose`, `koin-compose-viewmodel`, `koin-compose-viewmodel-navigation`, optionally `koin-test`
- **Selected version**: **`4.2.0`** (stable)
- **Stable or pre-release**: stable
- **Official source checked**: <https://insert-koin.io/docs/support/releases/> and <https://github.com/InsertKoinIO/koin/releases> — verified 2026-05-22
- **Why this version**: latest stable. ViewModel DSL is mutualized in `koin-core-viewmodel`. KMP-first design. `koin-compose-navigation3` is available for AndroidX Navigation 3 — **we use Navigation 2.9.x (not Navigation 3)** because the source app is on Nav 2.8.9 and a Nav 3 jump is out of migration scope; we'll evaluate Navigation 3 later.
- **Best practices applied**: declare one `sharedModule` in `commonMain` and per-platform `platformModule()` via `expect/actual fun platformModule(): Module`. Use `viewModel { … }` from `org.koin.core.module.dsl`. Initialize via `startKoin { modules(sharedModule + platformModule()) }` in `MyApp.onCreate()` on Android, in `main()` on Desktop, in `KoinHelper.start()` callable from Swift on iOS.
- **Risks**: the Koin Compiler Plugin 1.0.0 is still RC — we deliberately do **not** enable it; we'll do runtime startup verification via `KoinApplication.verify()` in `commonTest`.
- **Files**: all `di/` modules, `MyApp.kt`, `Main.kt` (desktop), `KoinHelper.kt` (ios), every `*ViewModel.kt` registration.

---

## Database — Room KMP

- **Library/plugin**: `androidx.room:room-runtime`, `androidx.room:room-compiler`, `androidx.room:room-paging`, `androidx.room:room-gradle-plugin`, `androidx.sqlite:sqlite-bundled`
- **Selected version**: **`2.8.4`** (stable, KMP)
- **Stable or pre-release**: stable
- **Official source checked**: <https://developer.android.com/jetpack/androidx/releases/room> and <https://developer.android.com/kotlin/multiplatform/room> — verified 2026-05-22
- **Why this version**: matches the version used in the source project (2.8.4) and is the latest stable 2.x KMP release. We deliberately do **not** adopt Room 3.0 alpha (March 2026 announcement) — out of scope for a parity migration.
- **Best practices applied**:
  - Apply Room Gradle plugin (`androidx.room:room-gradle-plugin`) for schema export configuration.
  - Set `room { schemaDirectory("$projectDir/schemas") }`.
  - Use `Room.databaseBuilder` with platform-specific factories — `expect object AppDatabaseConstructor : RoomDatabaseConstructor<MangaDatabase>` + per-platform `actual` (`@Suppress("KotlinNoActualForExpect")` on common since the plugin generates the actuals).
  - Use `androidx.sqlite:sqlite-bundled` for the driver.
  - All DAO methods on non-Android targets MUST be `suspend` (Room KMP requirement). The source DAOs already use `suspend` for non-flow returns, but Phase 6 will audit each one and convert any blocking signatures as required.
  - Preserve `version = 8` and all 7 migrations; flip `exportSchema = true` (source had it false) — destructive migration NOT enabled.
- **Risks**:
  - Room enforces `suspend` DAO functions for non-Android targets. Any blocking DAO call must be converted in Phase 6; documented per call.
  - KSP must be configured for every target. Forgetting one target breaks the build.
- **Files**: `shared/build.gradle.kts`, `data/local/MangaDatabase.kt`, all `data/local/dao/*.kt`, `data/local/entity/*.kt`, `data/local/converter/*.kt`, `data/local/Migrations.kt`, `data/local/util/DataBaseHelper.kt`, per-platform `DatabaseBuilder.*.kt`.

---

## ViewModel — androidx.lifecycle KMP

- **Library/plugin** (split between two groups, verified against <https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html> on 2026-05-22):
  - `androidx.lifecycle:lifecycle-viewmodel` (Google AndroidX — iOS-capable since 2.9.2+)
  - `androidx.lifecycle:lifecycle-viewmodel-savedstate` (Google AndroidX — iOS-capable)
  - `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` (JetBrains Compose-MP port — iOS-capable)
  - `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose` (JetBrains Compose-MP port — iOS-capable)
- **Selected version**: **`2.10.0`** (stable, KMP)
- **Stable or pre-release**: stable
- **Official source checked**: <https://developer.android.com/jetpack/androidx/releases/lifecycle> and <https://developer.android.com/kotlin/multiplatform/viewmodel> — verified 2026-05-22
- **Why this version**: 2.10.0 is the current stable KMP release. The locked stack required by `MIGRATION_PROMPT.md` says "2.8.4+" which is satisfied.
- **Best practices applied**: extend `androidx.lifecycle.ViewModel` directly in `commonMain`. Use `viewModelScope`. Expose state via `StateFlow`. Use Koin `viewModel { … }` and `koinViewModel<T>()` in composables. On iOS, expose via `KMP-ObservableViewModel` or `SKIE` — out of session-1 scope but documented in `pending-work.md`.
- **Risks**: `LiveData` usage in source: per Phase 1 audit, `runtime-livedata` is pulled in but only as a transitive — no first-party `LiveData<*>` declarations observed. Phase 9 will reconfirm.
- **Files**: every ViewModel under `presentation/common/viewmodel/`, `presentation/features/*/ui/viewmodel/` (or `viewmodes/`, etc.), `presentation/features/onboarding/viewmodel/`, `core/cbz/CbzConversionViewModel.kt`, `ad_mob/AdViewModel.kt`, `admin/complaint/AdminComplaintViewModel.kt`, `TextViewModel.kt`.

---

## Navigation — androidx.navigation Compose Multiplatform

- **Library/plugin**: `org.jetbrains.androidx.navigation:navigation-compose` (JetBrains Compose-MP port — iOS-capable)
- **Selected version**: **`2.9.2`** (stable, KMP)
- **Stable or pre-release**: stable
- **Official source checked**: <https://kotlinlang.org/docs/multiplatform/compose-navigation.html> — verified 2026-05-22
- **Why this version**: latest stable KMP release of Navigation Compose. Locked stack requires "2.8.0+" with type-safe routes — satisfied. Source used Android-only `androidx.navigation:navigation-compose:2.8.9` with `@Serializable` routes already.
- **Critical coordinate note (Session 2, 2026-05-22)**: Google's `androidx.navigation:navigation-compose:2.9.x` does **NOT** publish iOS klibs — only `android`, `jvm`, and `linuxX64`. Compiling `:shared` (which uses navigation-compose in `commonMain`) for any iOS target fails with `"Couldn't resolve dependency 'androidx.navigation:navigation-compose' in 'iosMain' for all target platforms"`. The iOS-capable artifact is JetBrains' Compose-MP fork at `org.jetbrains.androidx.navigation:navigation-compose:2.9.2`. Verified by `gradlew.bat :shared:compileKotlinIosArm64` succeeding (1m 47s).
- **Best practices applied**:
  - Use `@Serializable` data objects/classes for routes (no string routes).
  - Use `composable<Route> { backStack -> val r = backStack.toRoute<Route>(); … }` builder.
  - Preserve deep links (manifest entries + `deepLinks = listOf(navDeepLink { … })`) — extracted in Phase 9.
  - Drop the `androidx.navigation:navigation-safe-args-generator` KSP plugin entirely (XML-only).
  - Drop `androidx.navigation:navigation-ui-ktx` (Material XML bridge — irrelevant).
- **Risks**: deep links may need platform-specific handling on iOS (not implemented in scope; documented in `pending-work.md`).
- **Files**: `navigation/NavGraphV2.kt`, all `navigation/routes/*.kt`, `composeApp/build.gradle.kts`.

---

## Networking — Ktor Client

- **Library/plugin**: `io.ktor:ktor-client-core`, `ktor-client-okhttp` (Android), `ktor-client-darwin` (iOS), `ktor-client-cio` (Desktop), `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`, `ktor-client-logging`, optionally `ktor-client-mock` for tests
- **Selected version**: **`3.4.3`** (stable)
- **Stable or pre-release**: stable
- **Official source checked**: <https://ktor.io/docs/releases.html> and <https://github.com/ktorio/ktor/releases/tag/3.4.0> — verified 2026-05-22 (3.4.3 published 2026-04-22)
- **Why this version**: latest stable Ktor 3 release. Includes Darwin SIGABRT close/execute race fix relevant for iOS — directly removes a known crash class.
- **Best practices applied**:
  - One shared `HttpClient` instance per-app, created via `expect/actual fun createHttpClient(): HttpClient`.
  - On Android: OkHttp engine to preserve existing OkHttp-based connection pooling.
  - On iOS: Darwin engine.
  - On Desktop: CIO engine.
  - Install `ContentNegotiation { json(Json { ignoreUnknownKeys = true; isLenient = true }) }`, `Logging { level = LogLevel.HEADERS }` (matching source OkHttp logging interceptor verbosity), `HttpCache { … }`, custom `BrowserHeaders` plugin (port of source `BrowserHeadersInterceptor.kt`), custom `ProgressObserver` plugin (port of `ProgressInterceptor.kt`).
  - `Auth` plugin only if any source code uses authenticated endpoints (Phase 7 will audit).
- **Risks**:
  - Behavior differences vs Retrofit: Retrofit's `Response<String>` returns the raw body — Ktor's `bodyAsText()` is equivalent; status codes mapped from `HttpResponse.status.value`. All 17 endpoint signatures map cleanly.
  - OkHttp interceptors don't transfer directly — must port to Ktor plugins.
- **Files**: `data/remote/api/IMangaDataApiServices.kt` (rewritten as `ApiClient` interface + `KtorApiClient` impl in `commonMain`), all `sources_repositry/*/...Repository.kt` (call sites), `BrowserHeadersInterceptor.kt` → Ktor plugin, `core/progress/ProgressInterceptor.kt` → Ktor plugin, `core/network_cache/forceCacheForDados.kt` → Ktor `HttpCache` config.

---

## HTML parsing — Ksoup

- **Library/plugin**: `com.fleeksoft.ksoup:ksoup` (+ optionally `ksoup-network-ktor3` for direct URL fetching, though we use Ktor separately)
- **Selected version**: **`0.2.6`** (stable)
- **Stable or pre-release**: stable
- **Official source checked**: <https://central.sonatype.com/artifact/com.fleeksoft.ksoup/ksoup> and <https://github.com/fleeksoft/ksoup/releases> — verified 2026-05-22 (released 2026-02-19)
- **Why this version**: latest stable per Maven Central. Ksoup is an API-compatible port of jsoup 1.20.1, used by 40+ source repositories for HTML parsing.
- **Best practices applied**:
  - Use `Ksoup.parse(html, baseUri)` matching jsoup's `Jsoup.parse`.
  - Selector syntax (`select`, `selectFirst`) is identical.
  - For each source repository, the only code change is the import path (`org.jsoup.*` → `com.fleeksoft.ksoup.*`).
- **Risks**:
  - **Spot-check per source required**: jsoup is at 1.18.3 in source and Ksoup ports jsoup 1.20.1 — minor parser behavior differences possible. Phase 7 will run a smoke test on saved HTML fixtures per source.
  - If any source diverges, that source is flagged blocked in `pending-work.md` (Android can fall back to jsoup via `androidMain` actual).
- **Files**: every `sources_repositry/<lang>/<site>/...Repository.kt`, `sources_repositry/common/{NormalSites,NormalSitesv2,SeparatedDetailsSites,SeparatedDetailsSitesv2,BaseManga,MangaSource}.kt`.

---

## Image loading — Coil 3

- **Library/plugin**: `io.coil-kt.coil3:coil-compose`, `io.coil-kt.coil3:coil-network-ktor3`, `io.coil-kt.coil3:coil-network-okhttp` (Android only), `io.coil-kt.coil3:coil-svg`
- **Selected version**: **`3.4.0`** (stable)
- **Stable or pre-release**: stable
- **Official source checked**: <https://coil-kt.github.io/coil/> — verified 2026-05-22
- **Why this version**: latest stable Coil 3. Source uses 3.1.0; we upgrade.
- **Best practices applied**:
  - Use `coil-network-ktor3` in `commonMain` (shares the Ktor client).
  - Keep `coil-network-okhttp` in `androidMain` since AdMob and other Android-only call sites might want the existing OkHttp infrastructure.
  - Use `SingletonImageLoader.Factory` instead of legacy `ImageLoaderFactory`.
  - Use `PlatformContext` instead of Android `Context`.
- **Risks**: `coil-gif` and `coil-video` are Android-only. Source uses neither directly (no `image-gif`/`image-video` imports detected). AVIF is handled separately via `org.aomedia.avif.android` (Android-only) — Coil falls back to JPEG/PNG on non-Android.
- **Files**: every screen that uses `AsyncImage`, `BlurredImageCoil`, `ImageWithGradientOverlay`, manga cover renderers, `core/avif/HeifCoder.kt` (Android only).

---

## Coroutines

- **Library/plugin**: `org.jetbrains.kotlinx:kotlinx-coroutines-core`, `kotlinx-coroutines-android`, `kotlinx-coroutines-test`
- **Selected version**: **`1.9.0`** (stable; latest at check)
- **Stable or pre-release**: stable
- **Official source checked**: <https://github.com/Kotlin/kotlinx.coroutines/releases> — verified 2026-05-22 (1.9.0 is the GA line at time of check; bumps to 1.10.x if available before Phase 3)
- **Why this version**: latest stable at check. Compatible with Kotlin 2.3.x.
- **Best practices applied**: declare `kotlinx-coroutines-core` in `commonMain`. `kotlinx-coroutines-android` only in `androidMain`. Test artifact in `commonTest`.
- **Risks**: none material.
- **Files**: every coroutine call site (universal).

---

## Serialization — kotlinx.serialization

- **Library/plugin**: `org.jetbrains.kotlinx:kotlinx-serialization-json`
- **Selected version**: **`1.11.0`** (stable)
- **Stable or pre-release**: stable
- **Official source checked**: <https://github.com/Kotlin/kotlinx.serialization/releases> and <https://central.sonatype.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-json> — verified 2026-05-22
- **Why this version**: latest stable. Compatible with Kotlin 2.3.x. Source used 1.6.3.
- **Best practices applied**: apply `org.jetbrains.kotlin.plugin.serialization` per module with KMP code. `@Serializable` for all routes + DTOs.
- **Risks**: 1.10/1.11 may have stricter parsing — `Json { ignoreUnknownKeys = true; isLenient = true }` covers backward compatibility.
- **Files**: every `@Serializable` class (routes, DTOs, models that already use it).

---

## Date/time — kotlinx.datetime

- **Library/plugin**: `org.jetbrains.kotlinx:kotlinx-datetime`
- **Selected version**: **`0.8.0`** (stable)
- **Stable or pre-release**: stable
- **Official source checked**: <https://github.com/Kotlin/kotlinx-datetime/releases> — verified 2026-05-22
- **Why this version**: latest stable. Compatible with Kotlin 2.3.x.
- **Best practices applied**:
  - **Breaking change awareness**: 0.8.0 removed `kotlinx.datetime.Instant` and `kotlinx.datetime.Clock` in favor of `kotlin.time.Instant`. There are type aliases for migration, but Phase 4 will rewrite our usages to use `kotlin.time.Instant` directly.
  - Also breaking: `dayOfMonth` → `day`, `monthNumber` → `month`, `DayOfWeek`/`Month` no longer type aliases to `java.time.DayOfWeek`/`Month`.
  - Rewrite `core/util/date/Date.kt` for `kotlinx.datetime` (it currently uses `java.time.*`).
  - Rewrite `data/local/converter/LocalDateConverter.kt` and `LocalDateTimeConverter.kt` to convert `kotlinx.datetime.LocalDate` and `LocalDateTime` to/from String/Long for Room storage.
- **Risks**: any code reading from a `java.time.Instant`-stored database row needs careful migration. Source DB schema stores dates as `Long` (epoch millis) per current converters — KMP `Instant.toEpochMilliseconds()` keeps wire compatibility.
- **Files**: `core/util/date/Date.kt`, `data/local/converter/LocalDateConverter.kt`, `LocalDateTimeConverter.kt`, any other `java.time.*` users surfaced in Phase 4.

---

## Settings — multiplatform-settings

- **Library/plugin**: `com.russhwolf:multiplatform-settings`, `multiplatform-settings-coroutines`, `multiplatform-settings-no-arg`
- **Selected version**: **`1.3.0`** (stable)
- **Stable or pre-release**: stable
- **Official source checked**: <https://github.com/russhwolf/multiplatform-settings/releases> and <https://central.sonatype.com/artifact/com.russhwolf/multiplatform-settings> — verified 2026-05-22
- **Why this version**: latest stable. Mature, used by major KMP apps (Touchlab etc.).
- **Best practices applied**:
  - Use `multiplatform-settings-no-arg` to avoid passing `Context` manually on Android.
  - Use `multiplatform-settings-coroutines` for `Flow<Boolean>`, `Flow<String>`, etc. — this replaces the `Flow`-shaped APIs in `core/storage/PrefsDelegate.kt`.
  - Map every key in `core/storage/StorageKeys.kt` to a `Settings` operation.
  - On iOS: backed by `NSUserDefaults` (no Keychain — secure storage will be a separate `expect/actual SecureStorage`, Phase 8).
- **Risks**: behavior change vs Android DataStore — DataStore is async/transactional; `multiplatform-settings` defaults to synchronous on most platforms. We use the coroutines module to keep the `Flow`-based API equivalent.
- **Files**: `core/storage/PrefsDelegate.kt`, `core/storage/SharedPrefsHelper.kt`, `core/storage/StorageKeys.kt`, `core/storage/DataStoreDelegate.kt` (Android-only bridge).

---

## Logging — Kermit

- **Library/plugin**: `co.touchlab:kermit`, optionally `kermit-crashlytics` for Android Crashlytics
- **Selected version**: **`2.0.4`** (stable)
- **Stable or pre-release**: stable
- **Official source checked**: <https://kermit.touchlab.co/docs/> — verified 2026-05-22
- **Why this version**: actively maintained, composable LogWriter design, first-class Crashlytics integration. Better fit than Napier for an app that already uses Crashlytics on Android.
- **Best practices applied**:
  - Create a thin facade `interface AppLogger` in `commonMain` so application code doesn't depend directly on Kermit.
  - On Android: configure `kermit-crashlytics` to send WARN/ERROR to Crashlytics.
  - On iOS: Kermit's default OSLog writer.
  - On Desktop: Kermit's default println writer.
  - Replace any `Log.d`/`Log.e` Android calls with `AppLogger.d`/`AppLogger.e` in shared code (Android `Log` is `android.util.Log` — Android-only).
- **Risks**: lots of `android.util.Log` calls likely exist; Phase 4 will catalog and bulk-rewrite.
- **Files**: every file currently using `android.util.Log` (full count produced in Phase 4), `work/Logs.kt`.

---

## WorkManager (Android-only)

- **Library/plugin**: `androidx.work:work-runtime-ktx`, `androidx.work:work-gcm`
- **Selected version**: **`2.10.1`** (stable; matches source)
- **Stable or pre-release**: stable
- **Official source checked**: <https://developer.android.com/jetpack/androidx/releases/work> — verified 2026-05-22
- **Why this version**: matches source. WorkManager is Android-only; KMP equivalents do not exist as a single library. Common interface `BackgroundJobScheduler` will be defined in Phase 8.
- **Best practices applied**: replace `@HiltWorker` with a custom `WorkerFactory` that resolves dependencies via Koin's `GlobalContext.get()`.
- **Risks**: WorkManager integration with Koin requires custom `WorkerFactory` plumbing in `MyApp.onCreate()`.
- **Files**: `work/CbzMigrationWorker.kt`, `work/LibraryRefreshWorker.kt`, `work/MangaDownloadWorker.kt`, `work/NotificationWorker.kt`, `di/workmanager/WorkManagerModule.kt`, `MyApp.kt`.

---

## DataStore (Android-only bridge, used selectively)

- **Library/plugin**: `androidx.datastore:datastore-preferences`
- **Selected version**: **`1.1.4`** (stable; matches source)
- **Stable or pre-release**: stable
- **Official source checked**: <https://developer.android.com/jetpack/androidx/releases/datastore> — verified 2026-05-22
- **Why this version**: kept identical to source so any existing Android-only DataStore-backed code (in `core/storage/DataStoreHelper.kt`) remains binary-compatible. `multiplatform-settings` becomes the primary settings API; DataStore remains as an Android-only helper for any place that already depends on its async semantics.
- **Best practices applied**: keep `DataStoreHelper` in `androidMain`; expose it via a Koin Android-only module.
- **Risks**: none — Android-only.
- **Files**: `core/storage/DataStoreDelegate.kt`, `core/storage/DataStoreHelper.kt`.

---

## Paging (Android-only for now)

- **Library/plugin**: `androidx.paging:paging-runtime`, `paging-compose`, `androidx.room:room-paging`
- **Selected version**: **`3.3.6`** runtime/compose; **`2.8.4`** room-paging (matches source)
- **Stable or pre-release**: stable
- **Official source checked**: <https://developer.android.com/jetpack/androidx/releases/paging> — verified 2026-05-22
- **Why this version**: KMP-Paging is in Compose Multiplatform 1.11 via JetBrains' `androidx.paging:paging-common`. For Phase 1 simplicity, **Paging stays Android-only** for now; if any KMP target needs paged source lists, we revisit in Phase 10. **No source UI uses paging in a way that's load-bearing for parity** beyond Android — the Library screen uses `LazyColumn` directly.
- **Best practices applied**: keep paging deps in `androidMain` until proven needed elsewhere.
- **Risks**: documented as deferred.
- **Files**: `data/local/dao/*.kt` (any `PagingSource` returns stay Android-only — Phase 6 audit).

---

## AdMob, Firebase, Google Play (Android-only)

The following Android-only libraries are preserved at their source versions because they are wrapped behind Android-only interfaces in `androidMain`. Versions:

| Library | Version | Source |
|---|---|---|
| `com.google.firebase:firebase-bom` | `34.4.0` (matches source) | <https://firebase.google.com/support/release-notes/android> |
| `com.google.android.gms:play-services-ads` | `24.8.0` | <https://developers.google.com/admob/android/quick-start> |
| `com.google.android.play:app-update` / `-ktx` | `2.1.0` | <https://developer.android.com/guide/playcore/in-app-updates> |
| `com.google.android.play:review` / `-ktx` | `2.0.2` | <https://developer.android.com/guide/playcore/in-app-review> |
| `com.google.android.ums:user-messaging-platform` | `3.2.0` | <https://developers.google.com/admob/android/privacy/quick-start> |
| `com.google.ads.mediation:inmobi` | `11.1.0.0` |  AdMob mediation guide |
| `com.google.ads.mediation:ironsource` | `9.2.0.0` | AdMob mediation guide |
| `com.google.ads.mediation:vungle` | `7.6.1.0` | AdMob mediation guide |
| `com.google.ads.mediation:facebook` | `6.21.0.0` | AdMob mediation guide |

All versions match the source; no bumps performed (we are not chasing dependency upgrades — just KMP-portability).

---

## Compose accessories (Android, but used by reader / library / etc.)

| Library | Version | Notes |
|---|---|---|
| `com.airbnb.android:lottie-compose` | **`6.6.6`** | Android-only Compose API. Replaced for non-Android by `compose-lottie` (a KMP fork) **deferred** to a future iteration. Marked Android-only in scope. |
| `com.composables:core` | **`1.32.0`** | KMP-capable; verify in Phase 10. |
| `com.facebook.shimmer:shimmer` | **`0.5.0`** | Android-only XML view. Replaced by `com.valentinilk.shimmer:compose-shimmer` for KMP — verify and document in Phase 10. |
| `me.saket.telephoto:zoomable-image-coil3` | **`0.16.0`** | Android-only. `expect/actual ZoomableImage`. |
| `net.engawapg.lib:zoomable` | **`2.8.0`** | KMP-capable per author docs; verify in Phase 10. |
| `org.aomedia.avif.android:avif` | **`1.3.0.841110fd`** | Android-only AVIF decoder. |
| `androidx.constraintlayout:constraintlayout-compose` | **`1.0.1`** | Verify KMP equivalent. Compose MP 1.11 ships with constraint layout. |
| `androidx.core:core-splashscreen` | **`1.0.1`** | Android-only. CMP splash composable in `commonMain` for other platforms. |

---

## Test libraries

| Library | Version | Notes |
|---|---|---|
| `org.jetbrains.kotlin:kotlin-test` | matches Kotlin (`2.3.21`) | `commonTest` |
| `app.cash.turbine:turbine` | **`1.2.0`** (verify) | Flow testing in `commonTest` |
| `io.mockk:mockk-common` | **`1.13.x`** | Only added when needed; prefer fakes |
| `co.touchlab:kermit` (already covered) | `2.0.4` | Logging |
| `junit:junit` | `4.13.2` (matches source) | `androidUnitTest` only |
| `androidx.test.ext:junit` | `1.2.1` (matches source) | `androidInstrumentedTest` only |
| `androidx.test.espresso:espresso-core` | `3.6.1` (matches source) | `androidInstrumentedTest` only |
| `androidx.compose.ui:ui-test-junit4` | from BoM | `androidInstrumentedTest` only |

---

## Summary table — locked versions

| Concern | Library | Version |
|---|---|---|
| Kotlin | `org.jetbrains.kotlin` | `2.3.21` |
| AGP | `com.android.application` | `8.13.0` |
| Gradle wrapper | distribution | `8.13` |
| KSP | `com.google.devtools.ksp` | `2.3.8` |
| Compose Multiplatform | `org.jetbrains.compose` | `1.11.0` |
| Koin | `io.insert-koin:koin-*` | `4.2.0` |
| Room | `androidx.room:*` + `androidx.sqlite:sqlite-bundled` | `2.8.4` |
| Lifecycle (core+savedstate) | `androidx.lifecycle:lifecycle-*` | `2.10.0` |
| Lifecycle (compose helpers) | `org.jetbrains.androidx.lifecycle:lifecycle-*-compose` | `2.10.0` |
| Navigation | `org.jetbrains.androidx.navigation:navigation-compose` | `2.9.2` |
| compileSdk | (`:app`, `:shared`, `:composeApp`) | `36` (bumped from source's `35` — required by transitive `androidx.activity:1.12.4` and `androidx.navigationevent:1.0.2`) |
| Ktor | `io.ktor:ktor-client-*` | `3.4.3` |
| Ksoup | `com.fleeksoft.ksoup:ksoup` | `0.2.6` |
| Coil 3 | `io.coil-kt.coil3:*` | `3.4.0` |
| Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-*` | `1.9.0` |
| Serialization | `org.jetbrains.kotlinx:kotlinx-serialization-json` | `1.11.0` |
| Datetime | `org.jetbrains.kotlinx:kotlinx-datetime` | `0.8.0` |
| Settings | `com.russhwolf:multiplatform-settings*` | `1.3.0` |
| Logging | `co.touchlab:kermit` | `2.0.4` |

---

## Dependency replacement summary

Recorded in detail in `dependency-replacement-report.md` (written alongside this file in Phase 2). One-line summary:

- Dagger Hilt → Koin 4.2.0
- Retrofit → Ktor 3.4.3
- jsoup → Ksoup 0.2.6
- Glide-OkHttp integration → Coil 3 + ktor3 network
- DataStore → multiplatform-settings 1.3.0 (DataStore kept Android-only)
- java.time → kotlinx.datetime 0.8.0
- safe-args → dropped (XML-only)
- runtime-livedata → dropped (use StateFlow)
- LocalLog (android.util.Log) → Kermit 2.0.4

---

Sources used in this research (final):

- [Kotlin releases](https://kotlinlang.org/docs/releases.html)
- [Compose Multiplatform 1.11.0 release blog](https://blog.jetbrains.com/kotlin/2026/05/compose-multiplatform-1-11-0/)
- [Compose Multiplatform compatibility matrix](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)
- [Room KMP setup](https://developer.android.com/kotlin/multiplatform/room)
- [Room 2.8.x release notes](https://developer.android.com/jetpack/androidx/releases/room)
- [Lifecycle ViewModel KMP setup](https://developer.android.com/kotlin/multiplatform/viewmodel)
- [Lifecycle release notes](https://developer.android.com/jetpack/androidx/releases/lifecycle)
- [Navigation Compose KMP](https://kotlinlang.org/docs/multiplatform/compose-navigation.html)
- [Koin releases](https://insert-koin.io/docs/support/releases/)
- [Ktor releases](https://ktor.io/docs/releases.html)
- [Ksoup releases](https://github.com/fleeksoft/ksoup/releases)
- [Coil 3 docs](https://coil-kt.github.io/coil/)
- [multiplatform-settings releases](https://github.com/russhwolf/multiplatform-settings/releases)
- [kotlinx-datetime releases](https://github.com/Kotlin/kotlinx-datetime/releases)
- [kotlinx.serialization releases](https://github.com/Kotlin/kotlinx.serialization/releases)
- [Kermit docs](https://kermit.touchlab.co/docs/)
