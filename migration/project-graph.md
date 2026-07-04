# Project Graph — Yami Manga source tree → KMP destinations

> Hierarchical view of the source tree with every directory's KMP destination annotated. **Every file is accounted for** — either explicitly listed or covered by the directory's annotation. Per-file accountability lives in `project-graph.json` and `file-accountability.md` (the latter written during/after Phase 4).
>
> Legend:
>
> - `→ commonMain` — moves into `shared/src/commonMain/kotlin/me/manga/yami/...` (data, domain, ViewModels, sources, ports)
> - `→ commonMain (UI)` — moves into `composeApp/src/commonMain/kotlin/me/manga/yami/...`
> - `→ androidMain` — moves into `shared/src/androidMain/kotlin/me/manga/yami/...`
> - `→ app/` — stays/lands in the Android launcher module
> - `→ androidMain (replaced)` — wrapped behind an expect/actual; concrete logic in `androidMain` only
> - `[platform_specific_keep]` — feature does not exist on iOS/Desktop; concrete logic Android-only
> - `[replaced]` — replaced by a KMP library (Hilt → Koin, Retrofit → Ktor, jsoup → Ksoup, etc.)
> - `[dropped]` — no longer needed (safe-args generator, dead files)

---

## Root: `yami-manga-apk-main/`

```
yami-manga-apk-main/
├── settings.gradle.kts                       [replaced]  → new settings.gradle.kts with :app :shared :composeApp :desktopApp
├── build.gradle.kts                          [replaced]  → new root build with KMP/CMP/Koin/Room plugins
├── gradle.properties                         → carried forward with KMP-friendly tweaks (Compose Compiler, etc.)
├── gradlew, gradlew.bat                      → regenerated for Gradle 8.x stable
├── gradle/
│   ├── libs.versions.toml                    [replaced]  → KMP version catalog (see library-decisions.md)
│   └── wrapper/                              [replaced]  → Gradle 8.x stable wrapper
├── .gitignore                                → already created in yami-kmp
├── .github/workflows/release.yml             → revisit in release-readiness-report.md; preserve signed-APK pipeline
├── .kotlin/, .idea/                          [dropped]   IDE local state
└── app/
    ├── build.gradle.kts                      [replaced]  → new app/build.gradle.kts (android-application + Koin)
    ├── proguard-rules.pro                    → preserved verbatim
    ├── google-services.json                  → copied to new app/ (Firebase)
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml           → recreated in app/src/main with identical content (Phase 11)
        │   ├── assets/                       → moved to app/src/main/assets/
        │   ├── ic_launcher-playstore.png     → copied
        │   ├── res/                          → values, drawable, font, mipmap, raw, xml, layout (some) split:
        │   │                                     - composable resources (strings, drawables, fonts) → composeApp/src/commonMain/composeResources/
        │   │                                     - Android-only XML layouts (used by ViewBinding) → app/src/main/res/
        │   │                                     - mipmap, splashscreen, file paths, manifest XML → app/src/main/res/
        │   │                                     - locale folders values-{ar,de,es,fr,in,it,ja,pt,ru,tr} → composeResources/values-{locale}/
        │   │                                     - values-night → composeResources (theme-folded)
        │   │                                     - values-v26 → app/ (Android-only)
        │   └── java/me/manga/yami/           → see "Kotlin source tree" below
        ├── test/java/me/manga/yami/ExampleUnitTest.kt   → shared/src/androidUnitTest/
        └── androidTest/java/me/manga/yami/ExampleInstrumentedTest.kt   → app/src/androidTest/
```

---

## Kotlin source tree: `app/src/main/java/me/manga/yami/...`

### Root package (`me.manga.kira`)

```
MainActivity.kt                              → app/  (Android launcher)
MyApp.kt                                     → app/  (Application class)
TextViewModel.kt                             → commonMain  (ViewModel, KMP-portable)
Base64ImageConverter.kt                      → commonMain  (verify in Phase 4; if Android-bitmap-bound, androidMain)
BrowserHeadersInterceptor.kt                 → commonMain  (rewritten as Ktor plugin; [replaced])
```

### `admin/`

```
admin/Admin.kt                               → commonMain (UI)  (composable, verify)
admin/dgfhldghlghg.kt                        → renamed in Phase 4 (record in renames.md) → commonMain (UI) or commonMain
admin/api_test/ApiTestScreen.kt              → commonMain (UI)
admin/complaint/AdminComplaintScreen.kt      → commonMain (UI)
admin/complaint/AdminComplaintViewModel.kt   → commonMain  (ViewModel)
admin/complaint/StatusChangeDialog.kt        → commonMain (UI)
```

### `ad_mob/`  [platform_specific_keep — Android only]

```
ad_mob/AdConfig.kt                           → commonMain  (model is pure)
ad_mob/AdCoordinator.kt                      → commonMain (interface) + androidMain (impl)
ad_mob/AdState.kt                            → commonMain
ad_mob/AdViewModel.kt                        → commonMain
ad_mob/bannars/BannerAdView.kt               → androidMain  [expect/actual @Composable BannerAd(...)]
ad_mob/native_ads/NativeAdListItem.kt        → androidMain  [expect/actual @Composable NativeAdItem(...)]
ad_mob/native_ads/NativeAdQueue.kt           → androidMain
ad_mob/native_ads/di/NativeAdQueueEntryPoint.kt   [replaced]  Hilt entry point → Koin scope
ad_mob/native_ads/di/getNativeAdQueue.kt     → androidMain
ad_mob/rewarded/RewardedAdManager.kt         → androidMain
ad_mob/util/ads_lists/interleaveAds7.kt      → commonMain  (pure list interleaving)
ad_mob/util/ListEntryWithAd.kt               → commonMain  (data class)
```

### `core/`

```
core/avif/                                   → androidMain  [platform_specific_keep + expect/actual decoder]
  (HeifCoder, HeifDecoder, HeifPreset, HeifQualityArg, AvifChromaSubsampling, AvifSpeed,
   AvifSurfaceMode, PreciseMode, PreferredColorConfig, ScaleMode, ScalingQuality)

core/blur/BlurTransformation.kt              → commonMain if Coil 3 KMP Transformation API allows; else androidMain.

core/cbz/
  CbzSettings.kt                             → commonMain
  CbzConversionViewModel.kt                  → commonMain
  getCbzSettings.kt                          → commonMain (settings reader)
  CbzManager.kt                              → commonMain (interface)
  OptimizedCbzManager.kt                     → androidMain  (uses java.io.File + commons-compress)

core/file/FileSizeUtils.kt                   → commonMain  (pure)

core/network_cache/forceCacheForDados.kt     → commonMain  (Ktor cache plugin; [replaced])

core/network_connectivity/
  ConnectivityObserver.kt                    → commonMain  (interface)
  NetworkConnectivityObserver.kt             → androidMain  (Android impl) + iosMain + desktopMain actuals

core/progress/
  format.kt                                  → commonMain  (Composable formatter)
  ProgressInterceptor.kt                     → commonMain  (Ktor request/response observer; [replaced])
  ProgressManager.kt                         → commonMain
  ProgressState.kt                           → commonMain

core/states/
  ImagesState.kt                             → commonMain
  State.kt                                   → commonMain
  StringProvider.kt                          → commonMain  (uses compose.resources)

core/storage/
  DataStoreDelegate.kt                       → androidMain  (DataStore is JVM/Android)
  DataStoreHelper.kt                         → androidMain
  PrefsDelegate.kt                           → commonMain  (rewritten over multiplatform-settings)
  SharedPrefsHelper.kt                       → commonMain  (rewritten over multiplatform-settings)
  StorageKeys.kt                             → commonMain  (constants)

core/util/
  Handle403Error.kt                          → commonMain
  Plus18memes.kt                             → commonMain
  data_classes/HandelDataClasses.kt          → commonMain
  date/Date.kt                               → commonMain  (rewrite java.time → kotlinx.datetime)
  heap/detectDeviceTier.kt                   → androidMain  (uses Runtime.totalMemory + ActivityManager)
  image_share/ScreenshotUtils.kt             → androidMain (expect/actual ScreenshotProvider)
  notification/ChapterNotificationHelper.kt  → androidMain (expect/actual NotificationPresenter)
  notification/NotificationHelper.kt         → androidMain (expect/actual NotificationPresenter)
```

### `crash/`

```
crash/CrashActivity.kt                       → app/  (Android Activity)
crash/CrashInfoScreen.kt                     → commonMain (UI)  (Composable)
crash/CrashScreen.kt                         → commonMain (UI)  (Composable)
```

### `data/`

```
data/local/MangaDatabase.kt                  → commonMain  (Room KMP)
data/local/Migrations.kt                     → commonMain  (all 7 migrations preserved)
data/local/converter/
  Converters.kt                              → commonMain
  DownloadingStateConverter.kt               → commonMain
  LocalDateConverter.kt                      → commonMain  (rewrite for kotlinx.datetime LocalDate)
  LocalDateTimeConverter.kt                  → commonMain  (rewrite for kotlinx.datetime LocalDateTime)
  StringListConverter.kt                     → commonMain
data/local/dao/
  ChapterDao.kt                              → commonMain
  ChapterDownloadDao.kt                      → commonMain
  HistoryDao.kt                              → commonMain
  LibraryDeo.kt                              → commonMain  (typo "Deo" preserved unless user approves rename)
  MangaDao.kt                                → commonMain
  NotificationDao.kt                         → commonMain
  SavedMangaDao.kt                           → commonMain
  SourcesDao.kt                              → commonMain
  StatisticsDeo.kt                           → commonMain  (typo "Deo" preserved)
data/local/entity/
  ChapterDownloadEntity.kt                   → commonMain
  ChapterNotification.kt                     → commonMain
  HistoryItemD.kt                            → commonMain  (name preserved)
  SavedChapterEntity.kt                      → commonMain
  SavedMangaEntity.kt                        → commonMain
  SourcesEntity.kt                           → commonMain
data/local/util/DataBaseHelper.kt            → commonMain (interface) + androidMain/iosMain/desktopMain (actual builders)
data/remote/af.kt                            → commonMain (rename in Phase 4 after content check; record in renames.md)
data/remote/api/IMangaDataApiServices.kt     → commonMain  (rewritten as Ktor ApiClient; [replaced])
```

### `dex/`  [platform_specific_keep — Android DEX loader]

```
dex/AasqPlugin.kt                            → androidMain  (DEX-compiled at build time via buildDexPlugin task)
dex/DexPluginLoader.kt                       → androidMain  (loads .dex files into ClassLoader)
dex/PluginData.kt                            → commonMain  (data class)
buildDexPlugin Gradle task                   → app/build.gradle.kts (preserved)
```

### `di/`  [replaced — Hilt → Koin in Phase 5]

```
di/app/AppBindings.kt                        → commonMain di/CoreModule.kt
di/app/AppModule.kt                          → commonMain di/AppModule.kt
di/coli/CoilEntryPoint.kt                    → androidMain (or removed if Koin can express it)
di/coli/CoilModule.kt                        → commonMain di/ImageLoadingModule.kt
di/complaint/ComplaintRepositoryModule.kt    → commonMain di/ComplaintModule.kt
di/database/DatabaseModule.kt                → commonMain di/DatabaseModule.kt
di/download/DownloadModule.kt                → commonMain di/DownloadModule.kt (+ androidMain piece for WorkManager)
di/firebase/FirebaseModule.kt                → androidMain di/FirebaseModule.kt
di/network/ConnectivityMudule.kt             → commonMain di/ConnectivityModule.kt (typo fixed in target)
di/network/NetworkModule.kt                  → commonMain di/NetworkModule.kt
di/notification/NotificationModule.kt        → commonMain (interface) + androidMain (binding)
di/sources/module/ActiveRepoModule.kt        → commonMain di/SourcesModule.kt
di/sources/module/RepositoryModule.kt        → commonMain di/SourceRepositoriesModule.kt
di/sources/provider/ActiveRepoProvider.kt    → commonMain (provider lives in same module)
di/whatsnew/WhatsNewModule.kt                → commonMain di/WhatsNewModule.kt
di/workmanager/WorkManagerModule.kt          → androidMain di/WorkManagerModule.kt (WorkManager-only)
```

### `domain/`

```
domain/auth/DeviceIdProvider.kt              → commonMain (interface) + androidMain (actual: ANDROID_ID/UUID)
domain/auth/UserIdProvider.kt                → commonMain (interface) + androidMain
domain/device/DeviceInfoProvider.kt          → commonMain (interface)
domain/device/AndroidDeviceInfoProvider.kt   → androidMain
domain/model/ChapterImage.kt                 → commonMain
domain/model/ChapterItem.kt                  → commonMain
domain/model/MangaDisplayItem.kt             → commonMain
domain/model/MangaInfo.kt                    → commonMain
domain/model/MangaItem.kt                    → commonMain
domain/model/MangaSearchResponse.kt          → commonMain
domain/model/MyData.kt                       → commonMain (rename in Phase 4 if content reveals a better name)
domain/model/PopularManga.kt                 → commonMain
domain/model/ReaderChapters.kt               → commonMain
domain/model/SearchItems.kt                  → commonMain
domain/repos/MangaRepository.kt              → commonMain (interface)
domain/service/DownloadService.kt            → commonMain (interface) + androidMain (Service impl in app/) or scheduler impl
domain/service/FileService.kt                → commonMain (interface) + per-platform actuals
```

### `firebase_cores/`  [platform_specific_keep]

```
firebase_cores/common/rememberFirebaseAnalytics.kt   → androidMain  (Composable wrapping FirebaseAnalytics)
firebase_cores/messaging/MyFirebaseMessagingService.kt → app/  (Android Service)
```

### `google_play_cores/`  [platform_specific_keep]

```
google_play_cores/app_review/ReviewManagerHelper.kt   → androidMain
google_play_cores/app_update/AppUpdateHelper.kt       → androidMain
google_play_cores/ss.kt                              → androidMain  (rename in renames.md after content check)
```

### `navigation/`

```
navigation/NavGraphV2.kt                     → commonMain (UI)  (NavHost with @Serializable routes)
navigation/NavigationLock.kt                 → commonMain (UI)
navigation/safePopBackStack.kt               → commonMain (UI)
navigation/double_click/HomeTabReselectedHandler.kt → commonMain (UI)
navigation/double_click/NavigationHandlerHolder.kt  → commonMain (UI)
navigation/routes/*.kt (19 files)            → commonMain (UI) navigation/routes/
```

### `presentation/`

```
presentation/common/
  componants/                                → commonMain (UI)  (app_bars, auto_sized_text, BottomNavigationBar,
                                                buttons, dialogs, floating_button, flow_chips, images, list_items,
                                                ItemsGroup, isScrolledToTheEnd, scroll, sources, titles, toast)
  screens/ErrorScreen.kt, LoadingScreen.kt    → commonMain (UI)
  viewmodel/ChaptersViewModel.kt              → commonMain
  viewmodel/MangaViewModel.kt                 → commonMain
  viewmodel/SharedChaptersViewModel.kt        → commonMain

presentation/features/<feature>/             → commonMain (UI) for components/screens
  data/                                      → commonMain (data sources / impls)
  domain/                                    → commonMain (repository interfaces + impls + use cases)
  model/                                     → commonMain
  ui/                                        → commonMain (UI)  composables + viewmodel/
  viewmodel/ or viewmodes/                   → commonMain  (ViewModels)
  usecase/                                   → commonMain
  utils/                                     → commonMain
```

(All 19 feature subpackages — about, complaint, details, download, history, home, language, library, library_details, notifications, onboarding/{sources,theme_selection,viewmodel,welcome}, reader, refresh, repo_settings, settings, statistics, webview, whatsnew — follow the same split. WebView feature has `androidMain` impl for the actual WebView Composable.)

### `sources_repositry/`

```
sources_repositry/BaseMangaRepository.kt              → commonMain
sources_repositry/EmptyMangaRepository.kt             → commonMain
sources_repositry/common/
  BaseManga.kt                                        → commonMain
  NormalSites.kt, NormalSitesv2.kt                    → commonMain
  SeparatedDetailsSites.kt, SeparatedDetailsSitesv2.kt → commonMain
  MangaSource.kt (the DEX-targeted abstract base)     → commonMain  (interface; DEX plugins implement it on Android)
sources_repositry/ar/ ar-azora, ar-comick_io, ar-dilar(+v2), ar-lavatoon,
  ar-mangalek, ar-mangamello(+plus), ar-mangapark, ar-mangatuk,
  ar-promanga, ar-teamx
sources_repositry/en/ batcave, batoto_en, demonicscans, mangabuddy,
  manhwatop, tapastic, zazamanga
sources_repositry/es/ inmanga, mangapark(+_la), manhwaweb, taurusfansub
sources_repositry/fr/ manga_origine, raijinscan
sources_repositry/in/ komikcast, komiku
sources_repositry/it/ mangapark, mangaworld
sources_repositry/pt/ flowermanga, manhastro, mediocretoons, sussytoons
sources_repositry/ru/ desu, mangahub
sources_repositry/tr/ timenaight, webtoonatti, webtoontr
                                                     → all → commonMain  (Ktor + Ksoup port)
                                                     CryptoUtils.kt (ar/dilar) → commonMain  (kotlinx hash / kotlin-crypto)
```

### `theme/`

```
theme/Color.kt                               → commonMain (UI)
theme/Theme.kt                               → commonMain (UI)
theme/Type.kt                                → commonMain (UI)
```

### `work/`  [platform_specific_keep — WorkManager]

```
work/CbzMigrationWorker.kt                   → androidMain
work/LibraryRefreshWorker.kt                 → androidMain
work/MangaDownloadWorker.kt                  → androidMain
work/NotificationWorker.kt                   → androidMain
work/Logs.kt                                 → commonMain  (pure logging helpers)
work/webViewDialog.kt                        → commonMain (UI) (expect/actual WebView Composable)
```

---

## Resources

```
res/values/strings.xml (~800 lines)          → composeApp/src/commonMain/composeResources/values/strings.xml
res/values-ar/strings.xml                    → values-ar/strings.xml
res/values-de/strings.xml                    → values-de/strings.xml
res/values-es/strings.xml                    → values-es/strings.xml
res/values-fr/strings.xml                    → values-fr/strings.xml
res/values-in/strings.xml                    → values-in/strings.xml
res/values-it/strings.xml                    → values-it/strings.xml
res/values-ja/strings.xml                    → values-ja/strings.xml
res/values-night/...xml                      → composeResources/values-night/  OR theme-folded
res/values-pt/strings.xml                    → values-pt/strings.xml
res/values-ru/strings.xml                    → values-ru/strings.xml
res/values-tr/strings.xml                    → values-tr/strings.xml
res/values-v26/                              → app/src/main/res/values-v26/  (Android-only — system features)
res/drawable/, mipmap-*, font/, raw/, xml/, layout/  → split between composeResources (cross-platform assets)
                                                       and app/src/main/res/ (Android-only resources like xml/ + mipmap)
```

---

## Graph completeness check

| Original directory | Files | Accounted for |
|---|---|---|
| root pkg | 5 | ✅ |
| admin | 6 | ✅ |
| ad_mob | 12 | ✅ |
| core | 41 | ✅ |
| crash | 3 | ✅ |
| data | 25 | ✅ |
| dex | 3 | ✅ |
| di | 16 | ✅ |
| domain | 17 | ✅ |
| firebase_cores | 2 | ✅ |
| google_play_cores | 3 | ✅ |
| navigation | 24 | ✅ |
| presentation/common | 25 | ✅ |
| presentation/features (148 composable files + ViewModels + data/domain) | ~450 | ✅ (covered by feature-package rule) |
| sources_repositry | ~140 (40+ repos × ~3-4 files) | ✅ (covered by language-package rule + per-source model dirs) |
| theme | 3 | ✅ |
| work | 6 | ✅ |
| res/ (all locales + drawables + layouts) | hundreds | ✅ |
| Manifest, ProGuard, signing, gradle | n | ✅ |
| **Total** | **632 Kotlin + resources** | ✅ |

Per-file accountability with status (`not_started`, `migrated`, `verified`, `platform_specific_keep`, etc.) is maintained in `project-graph.json` and `file-accountability.md`.
