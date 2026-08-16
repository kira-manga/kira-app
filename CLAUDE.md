# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A **Kotlin Multiplatform** (Android + iOS + Desktop/JVM) port of the native Android app **"Yami Manga"**, targeting **100% behavior/UI/feature parity** with the original. The original native source is vendored read-only at [`native-app/`](native-app/) as the parity spec (see "The `native-app/` standalone build" below).

The **strangler-fig migration is COMPLETE** (2026-07): the legacy `:shared` do-everything KMP module has been fully retired and **deleted**. Its contents were relocated into the clean-architecture module graph (details under "Where things stand"). The module graph is now:

- **Rework graph (clean architecture + strict MVI)** — `:core`, `:domain`, `:data`, `:data:local`, `:data:remote`, `:data:download`, `:platform`, `:presentation`, `:ui`, `:composeApp`; plus the Android host `:app` and Desktop entry `:desktopApp`.
- **Sources subsystem** — the config-driven `:sources:contracts`, `:sources:engine`, `:sources:config` (see its own section below) and the extracted legacy scrapers `:sources:legacy`.

> **Write all new code in the rework modules.** New DI bindings go in the rework feature modules (surfaced via `allReworkModules()` in `:composeApp`). `SharedModule.kt` still exists but now lives in `:composeApp` (`me.manga.kira.di`) holding only the few foundational bindings + the `platformModule()`/`initKoin()`/`doInitKoin()` bootstrap that came from `:shared`.

Package root is `me.manga.kira.*` everywhere (renamed 2026-06 from `me.manga.yamiapk`, along with the Android `applicationId` / iOS bundle id; the read-only `native-app/` reference still uses `me.manga.yamiapk`, on-disk folder `me/manga/yami`). The Android display name / iOS `CFBundleDisplayName` is **"Kira Manga"**. `iosApp/` is an Xcode host project (not a Gradle module) that wraps `:composeApp`'s `ComposeApp.framework`.

## Where things stand (source of truth, 2026-07)

- **AGP-10 new-DSL migration: DONE** (commit `3aba7cba`). Every KMP module is on `com.android.kotlin.multiplatform.library`, `:app` is on AGP built-in Kotlin, and the transitional `android.newDsl`/`android.builtInKotlin` flags are removed — full detail under "Build / test / run". Bumping AGP 9.2.1 → 10 itself (when AGP 10 releases) is the only remaining step there and has **not** been done.
- **Firebase / Crashlytics: DONE** — Analytics + Crashlytics on Android + iOS. iOS crash reporting is **Release/TestFlight-only** (Debug turns collection off, installs no crash hook, and skips the dSYM upload); iOS Kotlin/Native fatals report via CrashKiOS behind a custom unhandled-exception hook; the iOS dSYM upload is a build-time hard gate. Android uses the Crashlytics SDK default (inert under the placeholder config in local dev). See "Firebase / Crashlytics".
- **App version** is `1.0.5` (Android values come from `release/version.properties`; iOS `MARKETING_VERSION` is in `iosApp/project.yml`, with store build numbers selected by the release workflows).
- **Native iOS reader** — `iosApp/iosApp/NativeReader/` (Swift UIKit, driven by the shared reader MVI ViewModel in `:presentation`) is the shipping iOS reader; the Compose reader remains as a fallback.
- **`:shared` strangler-fig retirement — COMPLETE (all 6 phases), `:shared` DELETED**: Room → `:data:local` (P1), Ktor → `:data:remote` (P2), scrapers + legacy `domain.model.*` → `:sources:legacy` (P3), the chapter-download engine → new module `:data:download` (P4), the legacy presentation/feature layer redistributed (P5: complaint + settings/statistics/whatsnew → `:data`; `SourcesRepository`/`LibraryRepository` → `:sources:legacy`; `core.cbz` + `SharedPrefsHelper`/`FileService`/`UserIdProvider`/`DeviceInfoProvider`/`NotificationPermissionRequester` → `:platform`; `Admin` → `:core`; dead `MangaRepository` + `core.concurrency.IODispatcher` dup deleted), then the Koin bootstrap (`SharedModule`/`PlatformModule`+actuals/`KoinInitializer`/`KoinHelper`) → `:composeApp` and `:shared` deleted (P6). All verified-green on Android/Desktop/iOS. **Deferred (cosmetic, non-blocking):** the relocated files kept their original package names (e.g. `me.manga.kira.presentation.features.complaint.*` now lives in `:data`, `me.manga.kira.domain.auth`/`core.storage`/`core.cbz` in `:platform`) — a package-rename pass to match the new module namespaces was intentionally not done (high consumer-import churn, zero behavior value). The generic-sources conversion campaign is complete (12 sources generic — see `docs/HANDOFF.md` § Sources).
- **Verified-green build gates** (as of this doc): the compile gate (`:composeApp:compileAndroidMain` + `:composeApp:compileKotlinDesktop` + `:composeApp:compileKotlinIosSimulatorArm64`), `:app:assembleDebug`, and the iOS Debug `xcodebuild`.

## Module layering contract (never violate the dependency direction)

The authoritative contract is `docs/ARCHITECTURE_REWORK_CONTRACT.md` §4–§5 (the owner's words: "if a rule conflicts with session habits, this document wins"); the running `ARCHITECTURE.md`/`SOLID_AUDIT.md` decision logs were retired in the 2026-07-04 docs consolidation (their still-binding content lives in `docs/HANDOFF.md`). Allowed dependency directions, confirmed against the `build.gradle.kts` files:

| Module | Role | Depends on |
|---|---|---|
| `:core` | error types (`AppError`), `AppResult`, `DispatcherProvider`, logging SPI, pure utils | **nothing** (leaf) |
| `:domain` | immutable entities, repository **interfaces** (DIP ports), one-verb use cases — pure Kotlin, zero framework deps | `:core` |
| `:data` | repository **implementations** + DTO↔domain mappers; maps errors to `AppResult`/`AppError`. Also hosts the relocated legacy complaint feature + settings/statistics/whatsnew repos (`complaintModule()`/`legacyDataModule`). Has the kotlinx-serialization compiler plugin (complaint DTOs) + androidMain Firebase Firestore + Ktor client | `:core`, `:domain`, `:platform`, `:sources:contracts`, `:data:local`, `:data:remote`, `:data:download` |
| `:data:local` | **Room persistence** (extracted from `:shared`, strangler Phase 1): `MangaDatabase` v12, DAOs/entities/converters/migrations, per-target `DatabaseBuilder` actuals, `databaseModule()`. A leaf that `:data`/`:data:download`/`:sources:legacy` depend **down** onto | `:core` |
| `:data:remote` | **Ktor transport** (extracted from `:shared`, strangler Phase 2): `createHttpClient()` (expect + OkHttp/Darwin/CIO actuals), `ApiClient`, iOS `BoundedCacheStorage`, `remoteModule()`. Same leaf pattern as `:data:local` | `:core` |
| `:data:download` | **chapter-download engine** (extracted from `:shared`, strangler Phase 4): `DownloadRepository` interface + `DownloadManifest`/`DownloadState`, per-target impls (Android WorkManager `DownloadRepositoryImpl`/`DownloadWorkerV2`/`ChapterDownloadService`; iOS `BackgroundUrlSessionDownloadRepository`; non-Android `CoroutineDownloadRepositoryImpl`), `downloadModule()`. Has its own androidMain res (notification strings) + serialization plugin (manifest) | `:core`, `:domain`, `:platform`, `:data:local`, `:sources:legacy` |
| `:sources:legacy` | **legacy hand-written scrapers** (extracted from `:shared`, strangler Phase 3): ~50 per-source repos + `common/` bases + the `MangaSource` registry + 187 `@Serializable` DTOs + the legacy `domain.model.*` (`MangaItem`/`MangaInfo`/…) + `legacySourcesModule()`. Also hosts the relocated legacy `SourcesRepository` + `LibraryRepository` (Phase 5 — the module both `:data`/`:data:download` see without a cycle). Distinct from the config-driven `:sources:{contracts,engine,config}` — does **not** depend on them | `:core`, `:domain`, `:data:local`, `:data:remote`, `:platform` |
| `:platform` | platform-facade **interfaces** (filesystem, ads, push, update, review, consent, crash, locale, …) with one impl class per target | `:core` |
| `:presentation` | MVI ViewModels + State/Intent/Effect (no Compose types) | `:core`, `:domain` |
| `:ui` | Compose Multiplatform screens, theme, design system (no data/platform/nav-graph knowledge) | `:presentation` |
| `:sources:contracts` | stable sources API: `MangaSourceClient` (5 verbs over `:domain` models), `SourceRegistry`, the serializable source/catalog models, validator/strategy/update interfaces, and transport/storage/signature ports | `:core`, `:domain` |
| `:sources:engine` | config-driven execution: `GenericSourceClient` + named strategies (Ksoup; **no Ktor** — HTTP only via the `HttpExecutor` port) | `:sources:contracts` |
| `:sources:config` | signed incremental catalog lifecycle: trusted bundle, reverified cache, conditional manifest fetch, immutable per-source deltas, and atomic activation | `:sources:contracts` |
| `:composeApp` | **aggregator**: app assembly, navigation host, Coil singleton, per-platform DI, sources composition root. Also hosts the Koin bootstrap relocated from the deleted `:shared` (`me.manga.kira.di`: `sharedModule`/`allSharedModules`, `platformModule()` + 3 actuals, `initKoin`, `doInitKoin`, `applyDesktopLogFloor`) | `:ui`, `:data`, `:data:local`, `:data:remote`, `:data:download`, `:sources:legacy`, `:platform`, all three `:sources:*` |
| `:desktopApp` | Desktop entry point (`Main.kt`, KCEF init) | `:composeApp`, `:core`, `koin-core` |
| `:app` | Android application host (pure Android, no KMP) | `:composeApp` + `:core` + `:platform` + `:data:local` + `:data:download` + `:sources:legacy` |

Rules that bite if ignored:
- ViewModels depend on **use cases**, never repositories directly (DIP). Use cases are bound `factory`, repos `single`.
- Repository interfaces follow ISP hard — many narrow single-purpose ports, no god-repositories.
- `:shared` is **gone** — there is no more `:data → :shared` bridge. Room/Ktor/scrapers/download all live in their own leaf modules (`:data:local`/`:data:remote`/`:sources:legacy`/`:data:download`); `:data` depends **down** onto those + **up** onto `:data:download` (for the `DownloadRepository` interface it strangles — acyclic because `:data:download` never references `:data`). `:data:download → :sources:legacy` (for `SourcesRepository`/`LibraryRepository`).
- `:sources:engine` and `:sources:config` must **never depend on each other** — they meet only at `:contracts`. Among the sources modules, `:data` may see **only `:sources:contracts`** (plus `:sources:legacy`, its strangler counterpart); `:presentation`/`:ui` must never reference any of them.
- The iOS framework (`:composeApp`) no longer `export(...)`s `:shared` (deleted). The Swift host imports only `ComposeApp` and enters via `IosKoinKt.bootstrapIosKoin()` → `doInitKoin(allReworkModules())`, both now in `:composeApp` iosMain. Don't reintroduce a cross-module export unless Swift needs a symbol from a non-`:composeApp` module.
- `:platform` uses **plain interfaces + per-target impl classes**, not `expect`/`actual`. The `expect`/`actual` indirection that binds them is the Koin module `platformModule()` — now in **`:composeApp`** (`me.manga.kira.di.PlatformModule.{android,ios,desktop}.kt`, relocated from `:shared` in Phase 6). All three hosts wire it via `initKoin`/`doInitKoin`, which combine `allSharedModules() + platformModule() + allReworkModules()`.

## How features are written: strict MVI (`:presentation`)

This is the most important pattern for productivity. Base class `MviViewModel<S : MviState, I : MviIntent, E : MviEffect>` (extends `androidx.lifecycle.ViewModel`, KMP-portable) at `presentation/.../mvi/MviViewModel.kt`:

- Each feature lives in `presentation/<feature>/` with four core files: `<Feature>ViewModel.kt`, `<Feature>State.kt`, `<Feature>Intent.kt`, `<Feature>Effect.kt` (plus, where needed, presentation-model files like `ReaderFeedItem.kt`; subfeatures nest as subdirectories with their own quartet, e.g. `complaint/admin/`).
- **State** = immutable `data class` (all `val`, read-only collections). View derives everything from `state: StateFlow<S>`.
- **Intent** = `sealed interface` (one variant per user action); reducer `when` is exhaustive (compile-time enforced).
- **Effect** = `sealed interface` of one-shot side effects (navigate/show-error), delivered via a `Channel(UNLIMITED)` so they survive config changes and are never replayed. Effects carry only trigger data, never rendering data or i18n text.
- Subclasses override `suspend fun handle(intent: I)` (the sole abstract hook). Mutate via `updateState { it.copy(...) }` (atomic CAS), emit via `emit(effect)`. `submit(intent)` launches a **fresh coroutine per intent** (not a serialized queue) — when ordering matters, use the cancel-before-relaunch `Job?` pattern (see `ReaderViewModel`).
- `submit()` catches non-cancellation throwables from `handle()` and routes them to the overridable `onUnhandledError(throwable, intent?)` hook (default: Kermit log + swallow) — a reducer throw never crashes the app. **But fire-and-forget coroutines inside a handler must use the base-class `launchSafely { … }` helper, not bare `viewModelScope.launch`** — a bare launch escapes the safety net and an uncaught throw crashes the process.
- Errors are typed `AppError`, never `String` — `:ui` translates them to localized text at the call site.

`:ui` screens pair a stateless `XScreen(viewModel, ...navCallbacks)` with an `internal XScreenContent(state, effects, onIntent, ...)`. The outer `XScreen` only does `viewModel.state.collectAsState()` and delegates; the effects collector (`LaunchedEffect(effects){ effects.collect{} }`) and the `LaunchedEffect(Unit){ onIntent(OnEnter) }` kickoff live inside `XScreenContent` so previews/tests can drive them with canned flows. `OnEnter` is a per-feature choice, not mandatory: lazy-start screens (Library) use it; pure-display screens subscribe in the ViewModel's `init{}` and may have an empty Effect interface (see `StatisticsViewModel`'s KDoc for the decision rule).

Navigation lives **only in `:composeApp`**: `@Serializable sealed class Screen` routes in `navigation/Screen.kt`, the `NavHost` in `App.kt`, per-screen Koin-resolving adapters in `navigation/routes/*ScreenRoute.kt`.

## The generic sources subsystem (`:sources:*`)

A config-driven replacement for the hand-written per-source scrapers, **live in production** for piloted sources. How it hangs together:

- `:composeApp` hosts the source runtime (`KtorHttpExecutor`, `DataStoreHeaderStore`,
  `KtorRemoteSourceCatalog`, `RoomSourceCatalogStore`, `DefaultSourceRegistry`) and wires it through
  `di/SourcesGenericModule.kt`.
- The latest completely synchronized signed v2 manifest is authoritative. Its ordered entries
  commit to immutable per-source revisions, checksums, lifecycle, engine, and detached signatures.
  The client sends the manifest ETag, downloads only missing active revisions, re-verifies cached
  bytes after restart, and atomically moves the Room pointer and source projection.
- The revision-5 bundle contains exactly 12 approved generic sources: Azora, Mangamello,
  Mangamello Plus, SwatManga, Lekmanga, Team X, DilarV2, 3asq, Demonicscans, Mangabuddy,
  Zazamanga, and Tapas. It contains no legacy metadata.
- **Fail closed — do not weaken:** `DefaultSourceRegistry` resolves only active generic entries.
  `FallbackSourceClient`, `LegacyKotlinSourceClient`, and `SourceDebugFlags` are deleted; the legacy
  scraper Koin set is empty. A missing, disabled, retired, removed, non-generic, invalid, or
  partially downloaded source has no client. Network failure selects the complete reverified cache
  or exact-12 bundle, never a per-api union.
- The old `/source/35` registry endpoint remains deleted. Config projection owns source metadata,
  host migration, and deep-link trust. `previousHosts` remains append-only.
- Filters remain fully config-driven. A generic source never enters old filter or scraper code.
- To add a source, start from `docs/sources/ADDING_SOURCES.md`: complete the generic conversion,
  parity-test every verb, validate and review the stanza, then explicitly publish an immutable
  backend revision. Add it to the bundled floor only through a reviewed app release.
- Lifecycle and identity invariants: api strings never change or get reused; backend transitions are
  `active → disabled → retired → removed`; the manifest carries explicit lifecycle/tombstone state;
  activation is all-or-nothing; and only compiled strategy names are accepted.

## Build / test / run

The host is **macOS** — use `./gradlew`. No `buildSrc`/convention plugins; every module repeats its KMP config inline and aliases plugins from `gradle/libs.versions.toml`.

```bash
# --- Build / run ---
./gradlew :app:assembleDebug                 # Android debug APK (the live Android app host)
./gradlew :desktopApp:run                    # run Desktop app (needs JDK 17+; see KCEF note)
./gradlew :desktopApp:packageDmg             # native Desktop installer (or packageMsi / packageDeb)

# iOS (macOS + Xcode only): the .xcodeproj is NOT committed — generate it once, then run from Xcode (iosApp scheme)
( cd iosApp && xcodegen generate )           # one-time, from iosApp/project.yml
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode

# --- Compile-only gate (the standard pre-commit check; "GREEN" = compiles, not assembles/links) ---
# (Android task is compileAndroidMain — single-variant under the AGP-9 new-DSL; the old per-variant compileDebugKotlinAndroid no longer exists.)
./gradlew :composeApp:compileKotlinDesktop :composeApp:compileAndroidMain :composeApp:compileKotlinIosSimulatorArm64 --offline

# --- Tests ---
./gradlew test                                       # Android unit tests in every module ONLY (see caveat below)
./gradlew :presentation:allTests                     # one module, all targets
./gradlew :domain:desktopTest                        # one module, Desktop/JVM only (fast loop; richest behavioral tests live in :domain and :presentation)
./gradlew :domain:testDebugUnitTest                  # one module, Android unit tests
./gradlew :data:iosSimulatorArm64Test                # iOS sim tests (needs macOS)
./gradlew :presentation:desktopTest --tests "me.manga.kira.presentation.library.LibraryViewModelApplyViewTest"   # single class
./gradlew :presentation:desktopTest --tests "*.LibraryViewModelApplyViewTest.someMethod"                            # single method
./gradlew :app:testDebugUnitTest                     # KoinGraphRegistrationTest + KoinGraphResolutionTest (validate the merged legacy+rework Koin graph)
./gradlew :ui:checkLocaleKeyParity                   # i18n gate (also in `check`): fails if any values-<loc>/ lacks a key from values/
```

Test-command caveats: under the AGP-9 built-in-Kotlin setup `:app` has **no release unit-test variant** — `:app:testReleaseUnitTest` does not exist (only `testDebugUnitTest`), so root `./gradlew test` no longer reaches the release placeholder guard and needs no `-PallowPlaceholderGoogleServices` flag (verified 2026-07-03: `:app:test` is green against the placeholder `google-services.json`; the guard still fires where it should — `assemble/bundle/package*Release` artifact tasks). Root `test` still runs only the Android `testDebugUnitTest` targets — no `desktopTest`, no iOS. Use per-module `desktopTest`/`allTests` for the real suites.

Build-environment gotchas:
- **Prefer `--offline` for local gates.** A clean/cold `--refresh-dependencies` resolve has failed for AGP 8.13.0; offline (warm cache) is the reliable path. Exception: `:app:assembleRelease` needs an online resolve (release-only deps like kermit-crashlytics aren't in the debug cache).
- **AGP 9 / Gradle 9 / compileSdk 37 (toolchain since 2026-06-29).** The build is AGP 9.2.1 + Gradle 9.6.1 + `compileSdk = 37` + core-ktx 1.19.0 (Kotlin 2.4.0 / Compose-MP 1.11.1 unchanged — that exact pair is the JetBrains-documented AGP-9 combo, so no Compose alpha is needed). Notes:
  - **SDK 37 installs locally as `platforms/android-37.0`** (Google's minor-SDK naming) but **AGP expects `platforms/android-37`**. A fresh machine that fails with *"failed to find target with hash string 'android-37'"* (or "android-37 not found") needs the symlink recreated: `ln -s android-37.0 "$HOME/Library/Android/sdk/platforms/android-37"` — or install the matching `android-37` SDK package from the Android Studio SDK Manager. **This symlink is not in the repo.**
  - **The AGP-10 new-DSL migration is COMPLETE (commit `3aba7cba`); the project is AGP-10-ready.** Every KMP library module (`:core`, `:domain`, `:data`, `:data:local`, `:data:remote`, `:data:download`, `:platform`, `:presentation`, `:ui`, `:composeApp`, `:sources:{contracts,engine,config,legacy}`) applies **`com.android.kotlin.multiplatform.library`** (catalog alias `android-kotlin-multiplatform-library`) with a nested `kotlin { android { namespace; compileSdk; minSdk; compilerOptions{jvmTarget} } }` block — the old `com.android.library` + `kotlin.multiplatform` combo and the `android-library` alias are gone. `:app` is `com.android.application` on **AGP built-in Kotlin** (the `kotlin-android` plugin was removed). `:desktopApp` is a plain `jvm` KMP app (no Android plugin). The transitional **`android.newDsl=false` / `android.builtInKotlin=false` flags have been removed** from `gradle.properties` — there are no more "removed in AGP 10" deprecation warnings. Only bumping AGP 9.2.1 → 10 itself (when released) remains, and is not done.
  - **CMP-9547 (compose-resources packaging under the new plugin) — fix is in place, do not remove.** The new KMP-library plugin does **not** package Compose Multiplatform's `composeResources` (`.cvr`) into the Android APK by default → a **runtime** `MissingResourceException` that both compile and `assembleDebug` pass through. Every compose-resources module sets `experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true` inside its `kotlin { android { } }` block — currently `:ui` and `:composeApp`. (This is distinct from `androidResources { enable = true }`, which `:data:download` sets so its `me.manga.kira.data.download.R` — the WorkManager notification strings/drawable — compile: a compile-time Android-res concern, not `.cvr` packaging. Any *new* compose-resources module needs the `experimentalProperties` flag too.)
  - **New-DSL Android compile task is single-variant: `:<module>:compileAndroidMain`** (the pre-migration per-variant `compileDebugKotlinAndroid` no longer exists). The aggregate iOS/desktop compile tasks are unchanged.
- **CI exists**: `.github/workflows/ci.yml` — jobs `jvm-android` (compile gate + 11 module `desktopTest` suites + both locale-parity gates + `:app:testDebugUnitTest` + `assembleDebug`), `ios` (both iOS compiles on macos-latest), `release-verify` (`release`-branch pushes / manual only; skips without the `KEYSTORE_BASE64` secret), and `static-analysis` (ktlint 1.5.0 + detekt-cli 1.23.7 as standalone CLI jars — deliberately not Gradle plugins so `--offline` keeps working — both **BLOCKING against the committed baselines** since T3 2026-07-03, excluding `sources_repositry/`). **Actions policy (owner rule): CI never runs on `main`** — triggers are pushes to `testing`/`release` + manual dispatch only (no `pull_request`, no tag triggers). CI resolves online on temurin Java 21.
- **Desktop needs a non-JBR JDK 17+.** Android Studio's bundled JBR 21 SIGSEGVs during KCEF init; `:desktopApp` pins `jvmToolchain(17)`.
- The libraries' JVM target is named **`desktop`** (tasks `desktopTest`, `compileKotlinDesktop`; source set `desktopMain`). But **`:desktopApp` uses a plain `jvm` target** (tasks `:desktopApp:jvmTest`, source set `jvmMain`). Don't conflate them.
- Custom Maven repos in `settings.gradle.kts` are required (IronSource `android-sdk.is.com`, JitPack, `jogamp.org` for KCEF's JOGL natives). `FAIL_ON_PROJECT_REPOS` is on — **never add repositories in a module build file**.
- **`:app` (any variant, incl. `assembleDebug`) needs `app/google-services.json`** — the `com.google.gms.google-services` plugin hard-fails without it. It's gitignored (bring your own). For a local compile/run, copy the committed placeholder: `cp app/google-services.json.example app/google-services.json` — FirebaseApp initializes with dummy config and Firebase backends are inert. **Release-variant tasks hard-fail on the placeholder** (`app/build.gradle.kts` guard) unless `-PallowPlaceholderGoogleServices=true` is passed — that flag is for build-path validation only, never for shipping.
- **iOS needs `iosApp/iosApp/GoogleService-Info.plist`** (the Firebase config for Analytics + Crashlytics), also gitignored (bring your own); the committed `GoogleService-Info.plist.example` documents the required structure — for a compile/smoke build you may copy it verbatim to the real filename (its values are fake but **format-valid**: FirebaseInstallations format-checks `API_KEY` at `FirebaseApp.configure()` and a malformed key is an NSException → SIGABRT at launch, seen on-device 2026-07-05; never "sanitize" the example back to a `YOUR_…` literal). `AppDelegate` reads it via `FirebaseApp.configure()` at launch. A **Release** build additionally fails at the dSYM-upload build phase if the real plist isn't bundled (see "Firebase / Crashlytics"); Debug needs it present but keeps Crashlytics collection off.
- Release signing: env vars `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` (passwords/alias also fall back to Gradle properties; keystore path defaults to the uncommitted `app/yami-release.keystore`). **`gradle.properties` commits real production AdMob unit IDs** (`ADMOB_*`) — a local release build serves production ads by default; debug always uses Google test IDs.
- **iOS signing modes (2026-07-02):** Debug signs with `iosApp/iosApp-nopush.entitlements` (NO `aps-environment`) so local device builds work under a **Personal (free) team** — Personal teams cannot carry the Push Notifications capability and automatic signing derives capabilities from the entitlements, so the old shared entitlements file failed Debug provisioning outright. Release/TestFlight/App Store sign with `iosApp/iosApp.entitlements` (`aps-environment = production`) and keep full push; those builds require a real Developer Program team anyway. Consequence of Debug-no-push: `registerForRemoteNotifications()` fails (logged), FCM gets no APNs token, remote push is simply absent locally. Developer-Program members wanting push in a Debug device build: point the Debug `CODE_SIGN_ENTITLEMENTS` in `project.yml` at `iosApp.entitlements` + `xcodegen generate` (one line, documented there).
- **iOS run:** from `iosApp/`: `xcodegen generate`, then build/run the `iosApp` scheme from Xcode, or headless: `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' build`. The pre-build script runs `:composeApp:embedAndSignAppleFrameworkForXcode`. Verified launching on the iOS 26 simulator.

## Firebase / Crashlytics (Android + iOS)

Analytics + Crashlytics run on **both** platforms against the same Firebase project (Firebase BOM `34.15.0`; `:app` also pulls Messaging + Firestore). Config files are BYO / gitignored, with a committed sanitized `*.example` documenting the structure: Android `app/google-services.json` (+ the `com.google.gms.google-services` and `com.google.firebase.crashlytics` `3.0.7` Gradle plugins in `app/build.gradle.kts`); iOS `iosApp/iosApp/GoogleService-Info.plist`.

- **Android crash handling** (`MyApp.onCreate`): **no custom `UncaughtExceptionHandler`, by design** — Firebase's default handler records the real fatal. (A previous custom handler recorded crashes as *non-fatals* then `killProcess`'d before Crashlytics could persist them — losing them; don't reintroduce one.) Kermit output is routed into Crashlytics as breadcrumbs via `CrashlyticsLogWriter` (`kermit-crashlytics`) after `FirebaseApp.initializeApp`; in **release** the log floor is first raised to `Warn` because the legacy scrapers log request URLs / header maps (`cf_clearance`, cookies) / HTML bodies at Info — none of that may reach Logcat or the breadcrumb trail in a shipped build. Collection follows the Crashlytics SDK default (there is **no** programmatic Debug gate on Android; in local dev the placeholder `google-services.json` makes the backends inert).
- **iOS crash handling is Release/TestFlight-only** (`AppDelegate`): `FirebaseApp.configure()` always runs (Analytics), but `#if DEBUG` calls `setCrashlyticsCollectionEnabled(false)` and installs **no** Kotlin hook; only the `#else` (Release) path enables collection and calls `CrashSetupKt.setupCrashlytics()`.
- **iOS Kotlin/Native fatals via CrashKiOS** (`co.touchlab.crashkios:crashlytics:0.9.0`, in `:composeApp` iosMain — `composeApp/.../crash/CrashSetup.kt`): `setupCrashlytics()` = `enableCrashlytics()` + a **hand-rolled** `setUnhandledExceptionHook { CrashlyticsKotlin.sendFatalException(it); exitProcess(0) }`. The custom hook (rather than CrashKiOS's own `setCrashlyticsUnhandledExceptionHook`) is load-bearing: CrashKiOS's helper ends in `std::terminate`→`abort`, and Firebase's C++ terminate handler then records a **second**, generic `ExceptionObjHolderImpl` fatal that wins (Crashlytics keeps one fatal/session) — collapsing every Kotlin crash into a single issue. Recording the good fatal ourselves and then `exitProcess(0)` (no `abort`) keeps distinct Kotlin crashes distinct. **Do not** switch back to the CrashKiOS helper.
- **iOS dSYM upload is a build-time hard gate** (`iosApp/project.yml`, build phase "Firebase Crashlytics dSYM upload (required)"): Release-only, it verifies the plist is bundled, uploads the symbols, and **fails the build** unless the upload confirms — so an un-symbolicated build can't ship (bypass with `CRASHLYTICS_DSYM_UPLOAD_OPTIONAL=1` for a deliberate offline build). `DEBUG_INFORMATION_FORMAT` is per-config: Debug `dwarf` (no dSYM, fast); Release `dwarf-with-dsym`.

## Conventions and gotchas that aren't obvious from the code

- **Audit-trail postscripts are noise.** Almost every `.kt` carries enormous machine-generated "Phase 9.x / cluster / staleKdocSweep audit-trail postscript" KDoc blocks, frequently citing now-deleted legacy files. The real code is a few lines; treat the prose as historical lineage, not current spec. Assert tests against source, not KDoc.
- **`AppResult`/`AppError` at boundaries, not `kotlin.Result`** (contract §9/§10). The `:data` layer maps all exceptions to `AppError` subtypes (HTTP→`Network.Http`, Cloudflare interstitials re-surfaced as 403 to route to a WebView solver, etc.); `CancellationException` is always re-thrown. Legacy strangler slices intentionally still use `kotlin.Result` for `:shared` wire-format parity.
- **`expect`/`actual` is used sparingly** (contract §12) — e.g. `IoDispatcher` (`Dispatchers.IO` is internal on Kotlin/Native → iOS uses `Dispatchers.Default`), Room's `DatabaseBuilder.{android,ios,desktop}.kt` (in `:data:local`), Ktor's `HttpClientFactory.<platform>.kt` (in `:data:remote`).
- **Room (KMP) lives in `:data:local`** (extracted from `:shared`, strangler Phase 1): `MangaDatabase` v11, `BundledSQLiteDriver`, 10 migrations. The 4→5 migration is deliberately spelled `Migration_4_5` (mixed case, native-source parity) — don't "fix" it. KSP is registered **per target** in `:data:local` (`kspAndroid`/`kspIosArm64`/`kspIosSimulatorArm64`/`kspDesktop`) — a single common `ksp(...)` will not generate the actuals. Android requires `setAndroidAppContext(context)` (in `:core`'s `me.manga.kira.core.android` facade since Phase 2 — shared by the Room builder and the Ktor cache dir) in `MyApp.onCreate()` *before* Koin init or the DB builder `check()`-fails. The DB + DAOs are bound by `databaseModule()` (in `:data:local`, surfaced through `allSharedModules()`), not the legacy `PlatformModule.*`. `MangaDatabaseFactory` pins the query context to `:core`'s `platformIoDispatcher`.
- **Navigation + the lifecycle `-compose` helpers are JetBrains Compose-MP ports** (`org.jetbrains.androidx.navigation` 2.9.2 / `.lifecycle` 2.10.0) — Google's don't publish iOS klibs; the base `lifecycle-viewmodel` artifacts are still Google's. Contract §2 wanted Navigation 3, but §16 "prefer stable" wins.
- **There is no `iosX64` target** (Compose MP 1.11.0 dropped Apple x86_64, KT-81596) — only `iosArm64` + `iosSimulatorArm64`. The `:composeApp` iOS framework `baseName = "ComposeApp"` is load-bearing (must match `import ComposeApp` in Swift).
- **JVM target split is deliberate**: Android compiles to JVM 11, Desktop to JVM 17 (KCEF needs 17+), in every module.
- **New UI strings**: every batch goes in its **own new** `values/strings_*.xml` file, never the shared `strings.xml` (compose-resources merges all `values/*.xml`; avoids parallel-edit clobber). The live naming family is `strings_pfix_<topic>.xml` (the older `strings_np_<cluster>.xml` family is closed). Every new key must be added to **all 11 locale folders** (`values-ar`, `values-de`, `values-es`, `values-fr`, `values-id`, `values-in`, `values-it`, `values-ja`, `values-pt`, `values-ru`, `values-tr` — `values-in`/`values-id` are the legacy/modern Indonesian tags, both enforced) or `:ui:checkLocaleKeyParity` fails the build (`scripts/check_locale_parity.py` is the standalone mirror). Each generated string accessor needs its **own explicit import** — a missing one shows up as a misleading "Unresolved reference". `:ui`'s generated `Res` is `internal` — `:composeApp` route adapters resolve strings from their own `composeResources`.
- **Icons** (rule relaxed 2026-07-02, backlog L14 — the old "never `Icons.*`" wording was violated by ~25 of 30 `:ui` files and a sweep can't complete while `HomeScreen.kt` is owner-WIP): `:ui` ships `compose.materialIconsExtended` (as `implementation`, not re-exposed). **Prefer `KiraIcons` / `KiraIconButton` for shared semantic actions** (back/search/close/delete/refresh — keeps the action→glyph map retunable in one file); one-off screen-specific glyphs may use `Icons.*` directly. Screens read the 8-pt `LocalSpacing.current`, not dp literals.
- **Images**: `:ui` uses Coil's plain `AsyncImage(model = url)` — the `ImageLoader` is configured once in `App.kt` via `setSingletonImageLoaderFactory`: OkHttp fetcher and AVIF decoder on **Android only**; iOS/Desktop use `HighQualitySkiaImageDecoder`; per-source auth headers via `CoilSourceHeaderInterceptor`.
- **Restricted paths**: `native-app/` is read-only (parity spec), always. `sources_repositry/` (per-source scrapers in `:shared`) is the read-only *spec* for generic-source conversions — edit it only on explicit instruction. Standing owner-WIP files that must stay untouched even when dirty in git status: `ui/.../home/HomeScreen.kt`, `sources_repositry/ar/mangalek/MangaLekRepositoryv2.kt`, `sources_repositry/data/MangaSource.kt`.
- The historical campaign docs (audits, PLAN_* files, `migration/`, postscript logs) were consolidated and deleted on 2026-07-04 — see "Working docs" below. The full originals remain in the predecessor repo (`Apdelrahman1911/yami-kmp`).

## The `native-app/` standalone build

`native-app/` is a **separate, self-contained Gradle project** (own `gradlew`, `settings.gradle.kts`, version catalog; root name "Yami Manga") — it is **not** a module of the root project. It's the original native Android app (MVVM + Hilt + Retrofit + Room v8), kept as the behavior-parity reference.

```bash
cd native-app
chmod +x ./gradlew        # committed without the exec bit
./gradlew assembleDebug    # build from INSIDE native-app, NOT the repo root
```

Caveats: it has its own Gradle wrapper (`gradle-9.0-milestone-1`, Kotlin 2.0.21, AGP 8.9.3) and will fail at `process*GoogleServices` until a `google-services.json` is dropped into `native-app/app/`. Its on-disk folder is `me/manga/yami/` but the Kotlin package is `me.manga.yamiapk` (the read-only `native-app/` keeps the original package — it was NOT renamed when the main app moved to `me.manga.kira`); some DAO names are intentionally spelled `...Deo` (not typos).

## Working docs

The ~140 tracking docs from successive autonomous campaigns (audits, PLAN_* files, postscript status logs, `migration/`, `audit_workspace/`) were **consolidated and deleted on 2026-07-04**. The full originals live on in the predecessor repo (`Apdelrahman1911/yami-kmp`); this repo keeps exactly four docs:

- **`docs/HANDOFF.md`** — the project-state handoff: what the app is, per-subsystem status (sources, reader, downloads, Firebase), CI/branch policy, accepted gaps, deferred work, risks. **Read this first**; keep it updated when project state changes materially.
- **`docs/ENGINEERING_NOTES.md`** — durable subsystem deep-dives: iOS background downloads (+ device test plan), native iOS reader, iOS libwebp compression, source-conversion guide + per-source status, device QA checklist, the deferred local manga-sharing plan.
- **`docs/ARCHITECTURE_REWORK_CONTRACT.md`** — the owner's verbatim architecture contract (moved from `migration/`); it wins over habit.
- **`README.md`** — quick-start + doc map + branch/CI policy.
