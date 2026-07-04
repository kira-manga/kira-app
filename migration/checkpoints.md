# Checkpoints

One entry per phase + intra-phase commit.

---

## CHECKPOINT 0 — Phase 0 complete (Session 1)

- **Timestamp**: 2026-05-22 (Session 1)
- **Phase**: Phase 0 — Initial Inventory
- **Files created**: README.md, .gitignore, migration/project-inventory.md, migration/migration-log.md, migration/progress-state.json, migration/checkpoints.md, migration/pending-work.md
- **Compile command**: none (no Kotlin yet)
- **Compile result**: n/a
- **Git tag**: (none — will tag after Phase 3 lands)
- **Next**: Phase 1 outputs.

## CHECKPOINT 1 — Phase 1 complete (Session 1)

- **Timestamp**: 2026-05-22
- **Phase**: Phase 1 — Project graph + module/feature/Android-only maps + KMP migration plan
- **Files created**: migration/project-graph.md, migration/project-graph.json, migration/module-map.md, migration/feature-map.md, migration/android-only-dependencies.md, migration/kmp-migration-plan.md
- **Compile command**: none
- **Compile result**: n/a
- **Commit**: `[phase-1] add project graph, module/feature/Android-only maps, and KMP migration plan` (585957f)
- **Next**: Phase 2 (library research).

## CHECKPOINT 2 — Phase 2 complete (Session 1)

- **Timestamp**: 2026-05-22
- **Phase**: Phase 2 — Library research and version locks
- **Files created**: migration/library-decisions.md, migration/dependency-replacement-report.md, migration/renames.md, migration/discovered-issues.md (+ updated progress-state.json, migration-log.md)
- **Compile command**: none (no buildable project yet)
- **Compile result**: n/a
- **Commit**: `[phase-2] add library-decisions, dependency-replacement-report, renames, discovered-issues` (f5598db)
- **Next**: Phase 3 (KMP Gradle scaffolding).

## CHECKPOINT 3 — Phase 3 KMP scaffolding (Session 1)

- **Timestamp**: 2026-05-22
- **Phase**: Phase 3 — KMP project structure + Gradle
- **Files created**:
  - `gradle/libs.versions.toml` — full KMP version catalog (locked versions from Phase 2)
  - `settings.gradle.kts` — includes `:app`, `:shared`, `:composeApp`, `:desktopApp`
  - `build.gradle.kts` — root build, plugin declarations
  - `gradle.properties` — JVM + Android + Kotlin + Compose-MP settings, AdMob IDs preserved
  - `gradle/wrapper/gradle-wrapper.properties` — updated to Gradle `8.13` (downgraded from source `9.0-milestone-1`)
  - `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` — copied from source
  - `shared/build.gradle.kts` — Kotlin Multiplatform module (Android + iOS x86/Arm/SimulatorArm + Desktop JVM) with Koin, Lifecycle, Navigation, Ktor, Room (KMP), kotlinx coroutines/serialization/datetime, multiplatform-settings, Kermit, Ksoup; Room KSP for all 5 targets; Room schema export configured
  - `composeApp/build.gradle.kts` — KMP module with Compose Multiplatform plugin, Koin compose, Navigation, Coil 3 KMP, Lottie/Telephoto/Shimmer/AVIF/Composables-core in androidMain only
  - `app/build.gradle.kts` — Android application module; preserves signing config, AdMob IDs, viewBinding/buildConfig/compose, all Firebase + Play services + AdMob mediation; depends on `:shared` and `:composeApp`
  - `desktopApp/build.gradle.kts` — JVM-only module hosting `me.manga.yami.desktop.MainKt` with Compose MP native distribution targets (Msi/Dmg/Deb)
  - `app/src/main/AndroidManifest.xml` — preserved verbatim from source (permissions, activities, services, receivers, providers, AdMob app ID, FCM service, FileProvider)
  - `app/src/main/res/{xml,mipmap-*,drawable,values/strings.xml,values/themes.xml,values/colors.xml}` — copied from source (xml descriptors, mipmaps, drawables) or stubbed (strings/themes/colors — full set lands in Phase 10)
  - `app/proguard-rules.pro`, `app/google-services.json` — copied verbatim
  - `composeApp/src/commonMain/kotlin/me/manga/yami/App.kt` — stub `@Composable App()` entry point
  - `shared/src/{commonMain,androidMain,iosMain,desktopMain}/kotlin/me/manga/yami/{Greeting,Platform.*}.kt` — placeholder classes so each source set compiles
  - `app/src/main/java/me/manga/yamiapk/{MyApp,MainActivity}.kt` — stub Application + Activity
  - `app/src/main/java/me/manga/yamiapk/crash/CrashActivity.kt`, `firebase_cores/messaging/MyFirebaseMessagingService.kt`, `presentation/features/download/ui/test2/DownloadCancelReceiver.kt` — empty subclasses so the manifest's component references resolve
  - `desktopApp/src/jvmMain/kotlin/me/manga/yami/desktop/Main.kt` — `application { Window { App() } }`
- **Compile command attempted (Session 1)**: NONE — Session 1 lacked JDK/Android SDK.
- **Compile result (Session 1)**: not_run.
- **Compile commands run in Session 2 (2026-05-22)**:
  - `gradlew.bat :shared:compileKotlinDesktop` → BUILD SUCCESSFUL in 43s
  - `gradlew.bat :shared:compileKotlinIosArm64` → BUILD SUCCESSFUL in 1m 47s
  - `gradlew.bat :shared:compileKotlinIosSimulatorArm64` → BUILD SUCCESSFUL in 42s
  - `gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL in 3m 24s
- **Compile result (Session 2)**: VERIFIED. See Session 2 entry in `migration-log.md` for the full list of fixes applied during verification.
- **Files NOT yet copied (deferred to Phase 10/4/11)**:
  - All Kotlin source under `app/src/main/java/me/manga/yami/` beyond the stubs above (632 files)
  - All localized string resources under `composeApp/src/commonMain/composeResources/values-*/`
  - The font / raw / layout resources from source (need to be split between `composeApp/composeResources/` and `app/res/`)
  - `.github/workflows/release.yml` — preserved post-Phase-11 once the new build is green
- **Git status**: clean working tree before commit; all new files staged.
- **Commit**: `[phase-3] add KMP Gradle scaffolding (catalog, root/settings build, shared/composeApp/app/desktopApp modules, manifest, stub sources)` (next commit)
- **Next**: Phase 4 — start moving pure Kotlin code (models, mappers, utilities, use cases) from source into `shared/src/commonMain/`. After each small batch, verify with `gradlew.bat :shared:compileKotlinDesktop && :shared:compileKotlinIosArm64 && :app:assembleDebug`.

---

## CHECKPOINT 4 — Phase 3 VERIFIED (Session 2, 2026-05-22)

- **Timestamp**: 2026-05-22 (Session 2)
- **Phase**: Phase 3 — KMP scaffolding build verification
- **Files changed**:
  - `gradle/libs.versions.toml` (KSP, lifecycle groups, nav group/version)
  - `app/build.gradle.kts` (kotlinOptions → compilerOptions DSL, compileSdk 36)
  - `shared/build.gradle.kts` (compileSdk 36)
  - `composeApp/build.gradle.kts` (compileSdk 36)
  - `gradle.properties` (removed deprecated `kotlin.mpp.androidGradlePluginCompatibility.nowarn`)
  - `migration/library-decisions.md` (notes + summary table updates)
  - `migration/progress-state.json` (phase-3 = verified, build_status_at_end = passing, session_2_corrections logged)
  - `migration/pending-work.md` (verification results recorded)
  - `migration/migration-log.md` (Session 2 entry)
  - `migration/checkpoints.md` (this entry)
- **Files created (not committed)**: `local.properties` (gitignored — points at local Android SDK)
- **Compile commands run**:
  - `gradlew.bat :shared:compileKotlinDesktop` → SUCCESSFUL (43s)
  - `gradlew.bat :shared:compileKotlinIosArm64` → SUCCESSFUL (1m 47s)
  - `gradlew.bat :shared:compileKotlinIosSimulatorArm64` → SUCCESSFUL (42s)
  - `gradlew.bat :app:assembleDebug` → SUCCESSFUL (3m 24s)
- **Commit**: `[phase-3] verify KMP scaffolding build` (1648ee5).
- **Next**: Phase 4 begins — move pure Kotlin code in small batches with per-batch verification.

---

## CHECKPOINT 5 — Phase 4 COMPLETED (Session 2, 2026-05-23)

- **Timestamp**: 2026-05-23 (Session 2)
- **Phase**: Phase 4 — Move pure Kotlin code
- **Files moved**: 237 source files into `shared/src/commonMain/kotlin/me/manga/yamiapk/`
- **Batches**: 4.0 .. 4.7 (see migration-log.md Session 2 entry for per-batch detail and commit SHAs).
- **Catalog change**: added `org.jetbrains.kotlinx:atomicfu:0.27.0` in batch 4.5.
- **Compile commands** (run after every batch):
  - `:shared:compileKotlinDesktop` → BUILD SUCCESSFUL
  - `:shared:compileKotlinIosArm64` → BUILD SUCCESSFUL
  - `:app:assembleDebug` → BUILD SUCCESSFUL
- **Commit refs**: `cf9fe55`, `3c9b20c`, `b023930`, `0dbf0d5`, `da0e49b`, `7833dc3`, `84ef336`.
- **Next**: Phase 5 — Hilt → Koin DI migration.

---

## CHECKPOINT 6 — Phase 5 scaffold + 5 bindings (Session 2, 2026-05-23)

- **Timestamp**: 2026-05-23 (Session 2)
- **Phase**: Phase 5 — Hilt → Koin DI migration (structural scaffold + first 5 bindings)
- **Files created**:
  - `shared/src/commonMain/kotlin/me/manga/yamiapk/di/SharedModule.kt`
  - `shared/src/commonMain/kotlin/me/manga/yamiapk/di/PlatformModule.kt`
  - `shared/src/commonMain/kotlin/me/manga/yamiapk/di/KoinInitializer.kt`
  - `shared/src/androidMain/kotlin/me/manga/yamiapk/di/PlatformModule.android.kt`
  - `shared/src/iosMain/kotlin/me/manga/yamiapk/di/PlatformModule.ios.kt`
  - `shared/src/desktopMain/kotlin/me/manga/yamiapk/di/PlatformModule.desktop.kt`
  - `shared/src/iosMain/kotlin/me/manga/yamiapk/di/KoinHelper.kt`
  - `migration/di-migration-report.md`
  - `migration/koin-graph-report.md`
- **Bindings registered**: 5 (the complaint use cases moved in Phase 4 batch 4.4).
- **Compile commands**:
  - `:shared:compileKotlinDesktop` → BUILD SUCCESSFUL
  - `:shared:compileKotlinIosArm64` → BUILD SUCCESSFUL
  - `:app:assembleDebug` → BUILD SUCCESSFUL
- **Commit**: `cb44274`.
- **Status**: phase-5 = `scaffolded` (not `completed` — full Koin graph fills in across batches 5.2/5.3/5.4/5.5 as Room/Ktor/expect-actual/ViewModels land in commonMain). The structural work (modules, expect/actual, helpers, deps, docs) is done.
- **Next**: Phase 6 — Room → Room KMP. After Room lands, return for Phase 5 batch 5.2 to add DB + DAO bindings.

---

## CHECKPOINT F1 — compose-resources foundation (Session 3, 2026-05-23)

- **Timestamp**: 2026-05-23 (Session 3, autonomous /goal run)
- **Phase**: F1 — Compose Resources foundation gate
- **Files copied** (143 total):
  - `composeApp/src/commonMain/composeResources/values/strings.xml` (default English)
  - `composeApp/src/commonMain/composeResources/values/arrays.xml` (string-array `supported_languages`)
  - `composeApp/src/commonMain/composeResources/values-{ar,de,es,fr,in,it,ja,pt,ru,tr}/strings.xml` (10 locales)
  - `composeApp/src/commonMain/composeResources/font/*.ttf` + `alba.TTF` (17 files: gellix×7, gilroy×3, poppins×7, alba×1)
  - `composeApp/src/commonMain/composeResources/drawable/*` (109 files: 91 XML vectors + 3 png + 11 jpg + 3 jpeg + 1 webp)
  - `composeApp/src/commonMain/composeResources/files/*` (6 files: 5 lottie + 1 download_animation.json)
- **Skipped (Android-only)**: `values-night/` (theme overrides — fold into Theme.kt in Phase 10), `values-v26/` (Android API qualifier), `values/{colors,dimens,ids,themes}.xml` (Android resource system — Compose handles theming in code).
- **Compile commands**:
  - `:composeApp:generateComposeResClass` → BUILD SUCCESSFUL
  - `:composeApp:compileKotlinDesktop` → BUILD SUCCESSFUL (codegen produced `Res` accessors for common + per-platform)
  - `:shared:compileKotlinDesktop` → BUILD SUCCESSFUL
  - `:shared:compileKotlinIosArm64` → BUILD SUCCESSFUL
  - `:composeApp:compileKotlinIosArm64` → BUILD SUCCESSFUL
  - `:app:assembleDebug` → BUILD SUCCESSFUL (40s)
- **Status**: F1 = `completed`. Compose-resources foundation in place; ~125 UI files now have `Res.string.*` / `Res.drawable.*` / `Res.font.*` available for Phase 10 batches.
- **Next**: Phase 7.0 — sources base classes + BrowserHeadersInterceptor (Ktor plugin) + forceCacheForDados (HttpCache) + ProgressInterceptor (observer) + State.kt java.net remap + homeV2/Search ArrayList fix.

---

## CHECKPOINT 7.0 — sources base classes + Ktor plugin ports (Session 3, 2026-05-23)

- **Timestamp**: 2026-05-23 (Session 3, autonomous /goal run)
- **Phase**: Phase 7 batch 7.0 — sources foundation
- **Files added/finalized** (22 total under `shared/src/commonMain/kotlin/me/manga/yamiapk/`):
  - `core/states/State.kt` — `java.net.*` exception remap → platform-agnostic `ApiError` sealed type
  - `sources_repositry/common/BaseManga.kt` (already ported; verified)
  - `sources_repositry/common/NormalSites.kt` (edited: DataStoreHelper param dropped)
  - `sources_repositry/common/NormalSitesv2.kt` (edited: DataStoreHelper param dropped)
  - `sources_repositry/common/SeparatedDetailsSites.kt` (edited: DataStoreHelper param dropped)
  - `sources_repositry/common/SeparatedDetailsSitesv2.kt` — **NEW** mirroring the Retrofit source class
  - `sources_repositry/BaseMangaRepository.kt` (already ported; verified)
  - `sources_repositry/EmptyMangaRepository.kt` (already ported; verified)
  - `sources_repositry/data/MangaSource.kt` (already ported; verified)
  - `BrowserHeadersInterceptor.kt` — OkHttp interceptor → Ktor `createClientPlugin`
  - `core/network_cache/forceCacheForDados.kt` — OkHttp cache → Ktor `HttpCache`
  - `core/progress/ProgressInterceptor.kt` — OkHttp source-wrap → Ktor `ResponseObserver`
  - `sources_repositry/ar/dilar/CryptoUtils.kt` — expect declaration
  - `sources_repositry/ar/dilar/CryptoUtils.android.kt` — actual using `android.util.Base64` + `javax.crypto.Cipher`
  - `sources_repositry/ar/dilar/CryptoUtils.desktop.kt` — actual using `java.util.Base64` + `javax.crypto.Cipher`
  - `sources_repositry/ar/dilar/CryptoUtils.ios.kt` — actual using `kotlin.io.encoding.Base64` + `platform.CoreCrypto.CCCrypt` / `CC_SHA256`
  - `sources_repositry/en/comick_io/models/homev2/homeV2.kt` — `ArrayList<HomeItem>` extension → kotlinx.serialization-friendly wrapper
  - `sources_repositry/en/comick_io/models/search/Search.kt` — same ArrayList-extension fix
- **Real port decisions logged** (not stubs):
  - `DataStoreHelper` constructor param dropped from 4 base classes (was unused at base-class level; Phase 8 will port the KMP DataStoreHelper, then concrete subclasses declare their own field).
  - iOS Base64 swapped from Foundation `NSString.create + base64DecodedDataWithOptions` to `kotlin.io.encoding.Base64.decode` (KMP-portable stdlib API; identical RFC4648 semantics; defensive try/catch preserves Foundation's lenient malformed-input behavior).
- **Errors fixed during verification**:
  - `:shared:compileKotlinDesktop` first run → 8× `Unresolved reference 'DataStoreHelper'` → resolved by dropping the unused param.
  - `:shared:compileKotlinIosArm64` first run → 2× `Unresolved reference 'base64DecodedDataWithOptions'` + 1× `Int/UInt comparison` (kCCKeySizeAES256) → resolved via stdlib Base64 swap + `.toInt()` on both comparison sides.
  - `:composeApp:compileKotlinIosArm64` → `KMP Dependencies Resolution Failure: Unresolved platforms: [iosX64]`. Compose Multiplatform 1.11.0 dropped Apple x86_64 support (Kotlin KT-81596). Latent config bug from Phase 3 scaffolding. Removed `iosX64()` from composeApp/build.gradle.kts iOS target list; kept `iosArm64()` + `iosSimulatorArm64()`. shared/build.gradle.kts still has `iosX64()` because shared has no Compose deps.
- **Compile commands** (all passing):
  - `:shared:compileKotlinDesktop` → BUILD SUCCESSFUL
  - `:shared:compileKotlinIosArm64` → BUILD SUCCESSFUL
  - `:composeApp:compileKotlinDesktop` → BUILD SUCCESSFUL
  - `:composeApp:compileKotlinIosArm64` → BUILD SUCCESSFUL
  - `:app:assembleDebug` → BUILD SUCCESSFUL
- **Status**: phase-7 batch 7.0 = `completed`. Phase 7 overall remains `scaffolded` (concrete per-source repos for 9 language folders land in batches 7.1-7.9).
- **Next**: Phase 7.1-7.9 — fan out parallel agents per language folder (`ar/en/es/fr/in/it/pt/ru/tr`), each porting all `*Repository.kt` + `*Parser.kt` + crypto helpers + deferred Models files. Use Phase 7.0 base classes; replace Retrofit/OkHttp/jsoup with `ApiClient`/`Map<String,String>`/Ksoup; replace `android.util.Log` with Kermit; drop `@Inject`; use `kotlinx.datetime` + `Clock.System.now()`.
