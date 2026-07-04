# Project Inventory — Yami Manga (Native Android)

> Source path (read-only): `D:\yami manga\yami-manga-apk-main`
> Target KMP path: `D:\yami manga\yami-kmp`
> Generated: Session 1, Phase 0.
> This file satisfies Section 8 of `MIGRATION_PROMPT.md`.

---

## 1. Project metadata

| Field | Value |
|---|---|
| Project name | `Yami Manga` (`rootProject.name`) |
| `applicationId` | `me.manga.kira` |
| `namespace` | `me.manga.kira` |
| `versionName` | `1.0.35` |
| `versionCode` | `35` |
| `minSdk` | `26` |
| `targetSdk` | `35` |
| `compileSdk` | `35` |
| Kotlin | `2.0.21` |
| AGP | `8.9.3` |
| Gradle wrapper | `9.0-milestone-1` (pre-release; flagged for upgrade decision in Phase 3) |
| Java target | `11` (sourceCompatibility / targetCompatibility / jvmTarget) |
| Kotlin code style | `official` |
| AndroidX | `true`, `nonTransitiveRClass=true`, `enableR8.fullMode=true` |

### Build types

- **debug**: `buildConfig` enabled with AdMob test IDs (rewarded `…/5224354917`, native `…/2247696110`, banner `…/6300978111`).
- **release**: signed via release keystore (env-driven: `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); `isMinifyEnabled=true`, `isShrinkResources=true`, `proguard-android-optimize.txt + proguard-rules.pro`. AdMob IDs come from `gradle.properties` (`ADMOB_REWARDED_ID`, `ADMOB_NATIVE_ID`, `ADMOB_BANNER_ID`), with the AdMob test IDs as fallback.

### Build features

- `buildConfig = true`
- `viewBinding = true`
- `compose = true`

### Custom Gradle tasks

- **`buildDexPlugin`** (in `app/build.gradle.kts`): compiles a single Kotlin file → JAR → DEX using `org.jetbrains.kotlin:kotlin-stdlib:1.9.0`, `org.json:json:20231013`, `org.jsoup:jsoup:1.18.3`. Input is `src/main/java/me/manga/yami/dex/AasqPlugin.kt`. **This is a runtime-pluggable manga source mechanism. Android-only by design; not portable to iOS/Desktop.** Will be retained as `platform_specific_keep` for Android with abstraction documentation in Phase 8.

---

## 2. Existing modules

Only one Gradle module currently: `:app`. The `settings.gradle.kts` declares `rootProject.name = "Yami Manga"` and `include(":app")`. The KMP target will introduce additional modules — `shared`, `composeApp` (or merged), `iosApp`, `desktopApp` — see `kmp-migration-plan.md`.

Custom maven repos: `google()`, `mavenCentral()`, `https://android-sdk.is.com/` (IronSource ad mediation), `https://jitpack.io`.

---

## 3. Existing libraries (all dependencies, exact versions)

From `gradle/libs.versions.toml`, root `build.gradle.kts`, and `app/build.gradle.kts`:

### Plugins

| Plugin | Version |
|---|---|
| `com.android.application` (AGP) | `8.9.3` |
| `org.jetbrains.kotlin.android` | `2.0.21` |
| `org.jetbrains.kotlin.plugin.compose` | `2.0.21` |
| `kotlin-parcelize` | (bundled) |
| `kotlin-kapt` | (bundled) |
| `com.google.dagger.hilt.android` | `2.57.2` (root plugin), `2.57.1` (app dependencies) — version mismatch flagged |
| `com.google.devtools.ksp` | `2.0.21-1.0.27` |
| `org.jetbrains.kotlin.plugin.serialization` | `1.9.0` |
| `androidx.navigation.safeargs.kotlin` | `2.8.9` (apply); `2.5.3` KSP processor (mismatch flagged) |
| `com.google.gms.google-services` | `4.4.4` |
| `com.google.firebase.crashlytics` | `3.0.6` |

### AndroidX / Compose

| Library | Version |
|---|---|
| `androidx.core:core-ktx` | `1.16.0` |
| `androidx.core:core-i18n` | `1.0.0` |
| `androidx.core:core-splashscreen` | `1.0.1` |
| `androidx.lifecycle:lifecycle-runtime-ktx` | `2.9.0` |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | `2.8.7` |
| `androidx.activity:activity-compose` | `1.10.1` (libs.versions.toml) / `1.10.0` (override at `dependencies` block) |
| `androidx.activity:activity-ktx` | `1.10.1` |
| `androidx.compose:compose-bom` | `2025.10.01` (libs.versions.toml) / `2025.06.01` (override) — mismatch flagged |
| `androidx.compose.ui:ui` | from BoM |
| `androidx.compose.ui:ui-graphics` | from BoM |
| `androidx.compose.ui:ui-tooling`, `ui-tooling-preview`, `ui-test-manifest`, `ui-test-junit4` | from BoM |
| `androidx.compose.foundation:foundation` | `1.8.2` explicit |
| `androidx.compose.material3:material3` | from BoM |
| `androidx.compose.material3.adaptive:adaptive` | from BoM |
| `androidx.compose.material:material` | `1.8.0` explicit |
| `androidx.compose.material:material-icons-core`, `material-icons-extended` | from BoM |
| `androidx.compose.runtime:runtime-livedata` | from BoM |
| `androidx.compose.animation:animation` | from BoM |
| `androidx.compose.ui:ui-viewbinding` | `1.6.0-rc01` (RC version — flagged) |
| `androidx.constraintlayout:constraintlayout-compose` | `1.0.1` |
| `androidx.palette:palette-ktx` | `1.0.0` |
| `androidx.navigation:navigation-compose` | `2.8.9` |
| `androidx.navigation:navigation-runtime-ktx` | `2.8.9` |
| `androidx.navigation:navigation-ui-ktx` | `2.8.9` |
| `androidx.navigation:navigation-safe-args-generator` (KSP) | `2.5.3` |
| `androidx.datastore:datastore-preferences` | `1.1.4` |
| `androidx.room:room-runtime` | `2.8.4` |
| `androidx.room:room-ktx` | `2.8.4` |
| `androidx.room:room-compiler` (KSP) | `2.8.4` |
| `androidx.room:room-paging` | `2.6.1` |
| `androidx.paging:paging-runtime` | `3.3.6` |
| `androidx.paging:paging-compose` | `3.3.6` |
| `androidx.hilt:hilt-navigation-compose` | `1.3.0` |
| `androidx.hilt:hilt-work` | `1.0.0` |
| `androidx.hilt:hilt-compiler` (kapt) | `1.0.0` |
| `androidx.work:work-runtime-ktx` | `2.10.1` |
| `androidx.work:work-gcm` | `2.10.1` |
| `com.google.android.material:material` | `1.13.0` |

### Dagger / Hilt

| Library | Version |
|---|---|
| `com.google.dagger:hilt-android` | `2.57.1` |
| `com.google.dagger:hilt-android-compiler` (kapt) | `2.57.1` |

### Networking

| Library | Version |
|---|---|
| `com.squareup.retrofit2:retrofit` | `2.11.0` |
| `com.squareup.retrofit2:converter-gson` | `2.11.0` |
| `com.squareup.retrofit2:converter-scalars` | `2.11.0` |
| `com.squareup.okhttp3:logging-interceptor` | `4.12.0` |
| `com.github.bumptech.glide:okhttp3-integration` | `4.16.0` |
| `org.jsoup:jsoup` | `1.18.3` (HTML scraping for manga sources) |

### Serialization / coroutines

| Library | Version |
|---|---|
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | `1.6.3` |
| `kotlinx-coroutines-*` | (provided transitively by Kotlin/AndroidX) |

### Image loading

| Library | Version |
|---|---|
| `io.coil-kt.coil3:coil-compose` | `3.1.0` |
| `io.coil-kt.coil3:coil-network-okhttp` | `3.1.0` (declared twice) |
| `me.saket.telephoto:zoomable-image-coil3` | `0.16.0` |
| `net.engawapg.lib:zoomable` | `2.8.0` |
| `org.aomedia.avif.android:avif` | `1.3.0.841110fd` |

### Firebase / Google Play

| Library | Version |
|---|---|
| `com.google.firebase:firebase-bom` | `34.4.0` |
| `firebase-analytics` | (BoM) |
| `firebase-crashlytics` | (BoM) |
| `firebase-messaging` | (BoM) |
| `firebase-firestore` | (BoM) |
| `com.google.android.gms:play-services-ads` | `24.8.0` |
| `com.google.android.play:app-update` | `2.1.0` |
| `com.google.android.play:app-update-ktx` | `2.1.0` |
| `com.google.android.play:review` | `2.0.2` |
| `com.google.android.play:review-ktx` | `2.0.2` |
| `com.google.android.ump:user-messaging-platform` | `3.2.0` |

### AdMob mediation

| Library | Version |
|---|---|
| `com.google.ads.mediation:inmobi` | `11.1.0.0` |
| `com.google.ads.mediation:ironsource` | `9.2.0.0` |
| `com.google.ads.mediation:vungle` | `7.6.1.0` |
| `com.google.ads.mediation:facebook` | `6.21.0.0` |

### Other

| Library | Version |
|---|---|
| `com.airbnb.android:lottie-compose` | `6.6.6` |
| `com.composables:core` | `1.32.0` |
| `com.facebook.shimmer:shimmer` | `0.5.0` |
| `com.facebook.infer.annotation:infer-annotation` | `0.18.0` |
| `org.apache.commons:commons-compress` | `1.24.0` (used by CBZ packaging) |

### Test

| Library | Version |
|---|---|
| `junit:junit` | `4.13.2` |
| `androidx.test.ext:junit` | `1.2.1` |
| `androidx.test.espresso:espresso-core` | `3.6.1` |
| Compose UI test (`ui-test-junit4`, `ui-test-manifest`) | from BoM |

---

## 4. DI framework currently used

**Dagger Hilt** (kapt-based) + AndroidX Hilt Worker for WorkManager + `androidx.hilt:hilt-navigation-compose` for ViewModel-in-Compose injection.

Hilt modules under `me.manga.yami.di`:

- `di/app/AppBindings.kt`
- `di/app/AppModule.kt`
- `di/coli/CoilEntryPoint.kt`
- `di/coli/CoilModule.kt`
- `di/complaint/ComplaintRepositoryModule.kt`
- `di/database/DatabaseModule.kt`
- `di/download/DownloadModule.kt`
- `di/firebase/FirebaseModule.kt`
- `di/network/ConnectivityMudule.kt` (typo in source name)
- `di/network/NetworkModule.kt`
- `di/notification/NotificationModule.kt`
- `di/sources/module/ActiveRepoModule.kt`
- `di/sources/module/RepositoryModule.kt`
- `di/sources/provider/ActiveRepoProvider.kt`
- `di/whatsnew/WhatsNewModule.kt`
- `di/workmanager/WorkManagerModule.kt`

ViewModels are annotated `@HiltViewModel` and resolved via `hiltViewModel<T>()` in composables. Workers use `@HiltWorker` + `AndroidEntryPoint`.

**Migration target**: Koin (KMP). All 16 Hilt modules become Koin modules. `@HiltViewModel` → `viewModel { … }`. `@HiltWorker` is Android-only and stays in `androidMain`.

---

## 5. Networking library

**Retrofit 2.11.0** with `converter-gson` and `converter-scalars`. The actual interface (`me.manga.kira.data.remote.api.IMangaDataApiServices`) is intentionally generic — most methods take `@Url url: String` and return `Response<String>` (raw HTML/JSON for parsing by jsoup or kotlinx-serialization). 23 endpoint annotations total; mostly `@GET`/`@POST` variants with optional `@HeaderMap`, `@Header("Referer")`, `@Field*`, `@Body`, and `@Headers("Content-Type: …")`.

OkHttp logging interceptor 4.12.0 + Glide-OkHttp 4.16.0 integration. **`jsoup 1.18.3` is used for HTML parsing across every per-source repository under `sources_repositry/`** — it has a KMP port (`korlibs/krypto`-free) and we'll need an alternative or expect/actual abstraction (see `library-decisions.md`).

**Migration target**: Ktor Client (KMP). Each source repository's `Response<String>` call becomes a Ktor `httpClient.get/post(...).bodyAsText()`. Jsoup → use `com.fleeksoft.ksoup:ksoup` (KMP-compatible jsoup port) or platform abstraction.

---

## 6. Image loading library

**Coil 3** (`io.coil-kt.coil3:coil-compose:3.1.0`, `coil-network-okhttp:3.1.0`) + **Telephoto** (`me.saket.telephoto:zoomable-image-coil3:0.16.0`) + `net.engawapg.lib:zoomable:2.8.0` (separate zoomable Compose helper) + `org.aomedia.avif.android:avif:1.3.0.841110fd` for AVIF decoding (Android-only).

**Migration target**: Coil 3 already supports KMP. Replace `coil-network-okhttp` with `coil-network-ktor3` for non-Android targets. AVIF decoder is Android-only — expect/actual wrapper. Telephoto is **Android-only** — must find KMP zoomable or build expect/actual abstraction.

---

## 7. Navigation approach

**Jetpack Navigation Compose 2.8.9** with type-safe (`@Serializable`) routes already in use. 19 routes live under `me.manga.yami.navigation.routes.*`:

- `HomeRoute`, `LibraryRoute`, `LibraryMangaRoute`, `HistoryRoute`, `MangaDetailsRoute`, `ReadingScreenRoute`, `SettingsRoute`, `LanguageScreenRoute`, `NotificationsRoute`, `RepoSettingsScreenRoute`, `SourcesScreenRoute`, `DownloadsScreenRoute`, `StatisticsRoute`, `ComplaintScreenRoute`, `AdminComplaintScreenRoute`, `ThemeSelectionScreenRoute`, `WhatsNewRoute`, `WelcomeScreenRoute`, `WebViewRoute`. The `NavGraphV2.kt` file is the top-level NavHost. `me.manga.yami.navigation.double_click` provides a navigation utility.

Safe-args 2.5.3 KSP processor is present but mostly legacy (the project is fully Compose-based; XML nav is unused).

**Migration target**: `androidx.navigation:navigation-compose` Compose Multiplatform 2.8.0+ (KMP) with the same `@Serializable` routes. Drop `safeargs` (XML-only and irrelevant).

---

## 8. ViewModel approach

`androidx.lifecycle.ViewModel` from `lifecycle-viewmodel-compose:2.8.7` + `lifecycle-runtime-ktx:2.9.0`. All 24 ViewModels extend `ViewModel`; state is exposed via `StateFlow` (no `LiveData` in shared logic, only `runtime-livedata` artifact pulled in transitively). `viewModelScope` is used.

24 ViewModels:

- `MainActivity.kt`'s `TextViewModel` (in root package)
- `ad_mob/AdViewModel`
- `admin/complaint/AdminComplaintViewModel`
- `core/cbz/CbzConversionViewModel`
- `presentation/common/viewmodel/{ChaptersViewModel, MangaViewModel, SharedChaptersViewModel}`
- `presentation/features/complaint/viewmodes/ComplaintViewModel`
- `presentation/features/details/ui/viewmodel/MangaDerailsViewModel` (typo in source — preserve)
- `presentation/features/download/ui/test2/DownloadViewModelv2`
- `presentation/features/history/ui/viewmodel/HistoryViewModel`
- `presentation/features/home/ui/viewmodel/HomeViewModel`
- `presentation/features/language/ui/viewmodel/LanguageViewModel`
- `presentation/features/library/ui/viewmodel/LibraryViewModel`
- `presentation/features/library_details/ui/viewmodel/LibraryDetailsViewModel`
- `presentation/features/notifications/ui/viewmodel/NotificationsViewModel`
- `presentation/features/onboarding/viewmodel/OnboardingViewModel`
- `presentation/features/reader/ui/viewmodel/ReaderViewModel`
- `presentation/features/refresh/ui/viewmodel/RefreshViewModel`
- `presentation/features/repo_settings/ui/viewmodel/RepoSettingsViewModel`
- `presentation/features/settings/ui/viewmodel/SettingsViewModel`
- `presentation/features/statistics/ui/viewmodel/StatisticsViewModel`
- `presentation/features/webview/ui/viewmodel/WebViewViewModel`
- `presentation/features/whatsnew/viewmodel/WhatsNewViewModel`

**Migration target**: `androidx.lifecycle:lifecycle-viewmodel` 2.8.4+ KMP. `WebViewViewModel` likely needs `androidMain` keep — Android-only WebView. `MainActivity.kt`'s inline `TextViewModel` will be evaluated case-by-case.

---

## 9. Database

**Room 2.8.4** + `room-paging:2.6.1`.

| Field | Value |
|---|---|
| Database class | `me.manga.kira.data.local.MangaDatabase` |
| Database name | `manga_database` |
| `version` | **8** |
| `exportSchema` | **false** (will be flipped to `true` per Section 37) |
| Entities (6) | `SavedMangaEntity`, `SavedChapterEntity`, `HistoryItemD`, `ChapterNotification`, `ChapterDownloadEntity`, `SourcesEntity` |
| DAOs (8) | `HistoryDao`, `LibraryDeo`, `NotificationDao`, `StatisticsDeo`, `MangaDao`, `ChapterDao`, `ChapterDownloadDao`, `SourcesDao` |
| Migrations (7) | `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`, `Migration_4_5`, `MIGRATION_5_6`, `MIGRATION_6_7`, `MIGRATION_7_8` (defined in `data/local/Migrations.kt`) |
| Type converters (5) | `DownloadingStateConverter`, `StringListConverter`, `Converters`, `LocalDateConverter`, `LocalDateTimeConverter` |
| Tables touched by migrations | `chapter_downloads` (created in 1→2), `sources` (created in 2→3; rebuilt with `name` as PK in 7→8), `saved_manga`, `saved_chapters` |

**Migration target**: Room KMP `2.8.4+` (Room is KMP-stable since 2.7.0) with `androidx.sqlite:sqlite-bundled`. All entities, DAOs, migrations, indices preserved. `exportSchema=true` flipped, but DB version stays at 8 (no destructive migration). Type converters using `java.time.*` will be ported to `kotlinx.datetime` for KMP (documented per converter).

---

## 10. Async

**Kotlin Coroutines** + `Flow`/`StateFlow`. No RxJava. WorkManager uses `CoroutineWorker` via Hilt.

**Migration target**: `kotlinx.coroutines` (KMP). Direct port.

---

## 11. Existing tests

| Path | Tests |
|---|---|
| `app/src/test/java/me/manga/yami/ExampleUnitTest.kt` | 1 stub |
| `app/src/androidTest/java/me/manga/yami/ExampleInstrumentedTest.kt` | 1 stub |

There is effectively **no test coverage** in the source project. Phase 4+ will not introduce mandatory new tests beyond what's required for KMP compile verification; preserved stubs land in `androidUnitTest`.

---

## 12. Number of screens / composables

- **148 files** contain `@Composable` declarations.
- **296 total `@Composable` annotations** (i.e., reusable composables + screen entry points).
- **24 distinct ViewModels** map roughly to **24 feature screens** plus shared composables under `presentation/common/`.

Feature areas (under `presentation/features/`):

`about`, `complaint`, `details`, `download`, `history`, `home`, `language`, `library`, `library_details`, `notifications`, `onboarding` (`sources`, `theme_selection`, `welcome`), `reader`, `refresh`, `repo_settings`, `settings`, `statistics`, `webview`, `whatsnew`.

Plus root areas: `admin/api_test`, `admin/complaint`, `crash`, `ad_mob/{bannars,native_ads,rewarded,util}`.

---

## 13. Number of API endpoints / repository methods

- **1 Retrofit interface** (`IMangaDataApiServices`) with **~17 annotated methods** (`@GET`/`@POST` variants). All take `@Url url: String` at call time and return `Response<String>`.
- **50 repository classes** matching `*Repository.kt` — of which **~40 are per-language manga source scrapers** under `sources_repositry/{ar,en,es,fr,in,it,pt,ru,tr}/…` (56 `*Repository*.kt` files total). The other 10 are feature repositories (`MangaRepository`, `HistoryRepository`, `LibraryRepository`, `NotificationRepository`, `SettingsRepository`, `StatisticsRepository`, `SourcesRepository`, `UpdateSourcesRepository`, `DownloadRepository[Impl]`, `Complaint…Repository*`).
- **5 use cases** in `presentation/features/complaint/usecase/` (`Send`, `Update`, `Delete`, `GetAll`, `GetUser` Complaint). Other features use repositories directly from ViewModels (no formal use-case layer).

Source language coverage (manga websites scraped):

- `ar/` — azora (Aasq / Azora v2), comick_io, dilar (+ v2), lavatoon, mangalek, mangamello (+ plus), mangapark, mangatuk, promanga (Pro + Prochan), teamx
- `en/` — batcave, batoto_en, demonicscans, mangabuddy, manhwatop, tapastic, zazamanga
- `es/` — inmanga, mangapark (+ _la), manhwaweb, taurusfansub
- `fr/` — manga_origine, raijinscan
- `in/` — komikcast, komiku
- `it/` — mangapark, mangaworld
- `pt/` — flowermanga, manhastro, mediocretoons, sussytoons
- `ru/` — desu, mangahub
- `tr/` — timenaight, webtoonatti, webtoontr
- `common/` — `MangaSource.kt` (the abstract base for parsed/dynamic sources, referenced by `buildDexPlugin` and `dex/AasqPlugin.kt`)

---

## 14. Existing CI/CD setup

`.github/workflows/release.yml` — a GitHub Actions release pipeline (likely signed APK build). Not yet re-read in detail; will be inventoried as part of `release-readiness-report.md`. The migration **must preserve** the signing config + AdMob property forwarding so the existing workflow keeps working unchanged.

---

## 15. Localization

12 resource bundles under `app/src/main/res/`:

- `values/` (default, English) — `strings.xml` has **~800 lines** (large translation table)
- `values-ar/` (Arabic)
- `values-de/` (German)
- `values-es/` (Spanish)
- `values-fr/` (French)
- `values-in/` (Indonesian)
- `values-it/` (Italian)
- `values-ja/` (Japanese)
- `values-night/` (dark theme)
- `values-pt/` (Portuguese)
- `values-ru/` (Russian)
- `values-tr/` (Turkish)
- `values-v26/` (API 26+ specifics)

RTL is enabled via `android:supportsRtl="true"` in the manifest. `androidx.core:core-i18n:1.0.0` and Arabic-first scraping (Arabic sources are the largest group) confirm RTL is load-bearing.

**Migration target**: Compose Multiplatform Resources (`compose.resources`) for shared strings/drawables/fonts. Each `values-*` bundle becomes a `commonMain/composeResources/values-*/strings.xml` equivalent. `values-night` is theme-folded; `values-v26` is Android-only resource (kept in `androidMain`).

---

## 16. Theme

- Light and dark theme via `values/` and `values-night/`.
- Material3 + Material2 both pulled in (the project mixes both).
- Custom theme entry: `me.manga.yami.theme.Theme.kt` (Compose `@Composable` theme wrapper).
- Splash: `androidx.core:core-splashscreen:1.0.1`, theme `@style/Theme.App.Starting` declared on both `MainActivity` and the manifest `<application>`.
- `androidx.palette:palette-ktx:1.0.0` — likely for cover-driven accent extraction.
- Fonts: `app/src/main/res/font/`.
- Theme selection feature: `presentation/features/onboarding/theme_selection/` (ThemeSelector + ThemeSelectionScreen).

---

## 17. Manifest highlights (Android-only behavior to preserve)

Permissions:

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `com.google.android.gms.permission.AD_ID`
- `POST_NOTIFICATIONS` (`required="false"`, runtime on Android 13+)
- `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"` — legacy)

Components:

- `<application name=".MyApp" android:largeHeap="true" supportsRtl="true" enableOnBackInvokedCallback="true">`
- Activities: `.MainActivity` (launcher), `.crash.CrashActivity`
- AdMob `<meta-data>`: `com.google.android.gms.ads.APPLICATION_ID = ca-app-pub-6540850069916280~1205773408`
- `FileProvider` (`${applicationId}.fileprovider`, paths in `res/xml/file_paths.xml`)
- WorkManager `SystemForegroundService` overridden as `foregroundServiceType="dataSync"`
- `androidx.startup.InitializationProvider` removed via `tools:node="remove"` (manual startup)
- Broadcast receiver: `.presentation.features.download.ui.test2.DownloadCancelReceiver` (actions: `ACTION_CANCEL_DOWNLOAD`, `ACTION_CANCEL_CHAPTER_DOWNLOAD`)
- FCM service: `.firebase_cores.messaging.MyFirebaseMessagingService` (intent filter `com.google.firebase.MESSAGING_EVENT`)
- FCM default notification icon: `@drawable/ic_message`
- `android:networkSecurityConfig="@xml/network_security_config"`
- `android:dataExtractionRules="@xml/data_extraction_rules"`, `android:fullBackupContent="@xml/backup_rules"`
- `windowSoftInputMode="stateVisible|adjustResize"`

All of these stay in `app/src/main/AndroidManifest.xml` post-migration (the Android target keeps the same Application class, Activity, services, receivers, providers — nothing here is portable).

---

## 18. Source file counts

| Bucket | Files |
|---|---|
| Total under `app/src/` | 812 |
| Kotlin under `app/src/main/java/me/manga/yami/` | 632 |
| `@Composable`-bearing | 148 |
| ViewModels | 24 |
| Repositories (`*Repository*.kt`) | 56 |
| Entities (`@Entity(`) | 6 + 1 migrations file = 7 hits |
| DAOs (`@Dao`) | 9 (8 DAO classes + 1 migrations-file hit) |
| Databases (`@Database(`) | 1 (`MangaDatabase`) |
| Use cases | 5 |

---

## 19. Decisions documented for downstream phases

These are deliberate decisions made by the autonomous agent in Phase 0 — log entries also live in `migration-log.md`:

1. **Gradle wrapper `9.0-milestone-1` will be downgraded to the latest 8.x stable** in Phase 3 (KMP plugins are flaky on Gradle 9 milestones). Documented in `library-decisions.md`.
2. **Two Compose BoM versions (`2025.10.01` vs `2025.06.01`)**: we adopt the newer `2025.10.01` in the KMP catalog and Compose MP equivalent. Documented in `dependency-replacement-report.md`.
3. **Hilt version mismatch (root `2.57.2` vs app `2.57.1`)**: irrelevant — Hilt is being fully replaced by Koin in Phase 5.
4. **Safe-args plugin (`2.5.3` KSP) + nav-compose (`2.8.9`)**: safe-args is XML-only and unused for the Compose code; **dropped** in Phase 3.
5. **`ui-viewbinding:1.6.0-rc01` (RC version)**: viewBinding is used (`buildFeatures.viewBinding = true`). Need to scan callers in Phase 1; if it's only legacy, drop it.
6. **`buildDexPlugin` (DEX-from-Kotlin task) + `dex/AasqPlugin.kt`**: This is an **Android-DEX-loaded runtime plugin** mechanism for adding new manga sources without releasing the app. **Kept Android-only (`platform_specific_keep`)** — iOS/Desktop cannot load DEX. Phase 8 documents the abstraction: source registry interface in `commonMain`, DEX-plugin loader in `androidMain` only; iOS/Desktop load the same sources via static registration.
7. **`jsoup` (HTML scraping)**: replaced by **`com.fleeksoft.ksoup:ksoup`** (KMP port of jsoup) — research confirmed in Phase 2. Documented in `dependency-replacement-report.md`.
8. **`telephoto:zoomable-image-coil3`**: Android-only. Will be wrapped in a `ZoomableImage` expect/actual composable; on iOS/Desktop we use `coil-compose` + `net.engawapg.lib:zoomable` (Phase 2 will verify zoomable's KMP status; if not, the reader screen on non-Android uses a simpler implementation, with the Android implementation kept identical to the original).
9. **AVIF decoder (`org.aomedia.avif.android`)**: Android-only. Abstract behind a Coil `Decoder` factory expect/actual.
10. **Firebase / AdMob / Play Services / `play-services-ads-mediation-*`**: all Android-only. Wrapped in `androidMain` analytics/crash/ads abstractions; iOS/Desktop use no-op or platform equivalents (e.g., Firebase iOS SDK via cocoapods later, AdMob is Android-only for this app). Phase 8 documents.
11. **Database stays at version 8 with current schema.** `exportSchema` flips to `true` and we commit `shared/schemas/` from Phase 6 onward. No destructive migration.

---

## 20. Phase 0 status

| Item | Status |
|---|---|
| New KMP repo created at `D:\yami manga\yami-kmp` | done |
| Git init + first commit on `main` | done |
| Remote `origin` set to `https://github.com/Apdelrahman1911/yami-kmp.git` | done |
| `main` pushed to remote | done |
| `kmp-migration` branch created + pushed | done |
| `migration/project-inventory.md` written | done (this file) |
| User confirmation of inventory | **autonomy override** — see `migration-log.md` |

Next: Phase 1 (project graph + module/feature maps).
