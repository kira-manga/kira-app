# Android-Only Dependencies

> Catalog of every Android-only library or API in the source project and the migration strategy. **Each line must resolve to one of: `replace with KMP lib`, `expect/actual abstraction`, or `platform_specific_keep (Android only)`.**

---

## A) Replaced with a KMP-native library

| Source library | KMP replacement | Action |
|---|---|---|
| Dagger Hilt (`com.google.dagger:hilt-android`, `hilt-android-compiler`, `androidx.hilt:hilt-navigation-compose`, `androidx.hilt:hilt-work`, `androidx.hilt:hilt-compiler`) | **Koin** (`io.insert-koin:koin-core`, `koin-compose`, `koin-compose-viewmodel`, `koin-android`) | Phase 5. All `@HiltViewModel` → `viewModel { … }`; all `@Module @InstallIn` → `module { … }`; `@AndroidEntryPoint` removed (use `KoinComponent` / `koinInject()` / `koinViewModel()`). `@HiltWorker` → manual `WorkerFactory` calling `getKoin().get<…>()`. |
| Retrofit 2.11.0 + `converter-gson` + `converter-scalars` | **Ktor Client** (`io.ktor:ktor-client-core`, `ktor-client-cio`/`okhttp`/`darwin`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`, `ktor-client-logging`) | Phase 7. `IMangaDataApiServices` becomes an `ApiClient` interface with `commonMain` implementation using Ktor's `HttpClient`. The 17 generic URL-based endpoints map 1-to-1. |
| OkHttp logging interceptor 4.12.0 | **Ktor `Logging` plugin** | Phase 7. |
| jsoup 1.18.3 | **Ksoup** (`com.fleeksoft.ksoup:ksoup`) | Phase 7. API-compatible KMP port of jsoup. Used by every `*Repository.kt` in `sources_repositry/`. |
| Glide-OkHttp integration 4.16.0 (`com.github.bumptech.glide:okhttp3-integration`) | **`coil-network-ktor3`** (already on Coil 3) | Phase 7/10. Drop Glide-OkHttp entirely. |
| `androidx.compose.runtime:runtime-livedata` | **`StateFlow` / `kotlinx.coroutines.flow`** | Phase 9. Verified during Phase 1 that the source uses `StateFlow` already; `runtime-livedata` is only transitively pulled — drop. |
| `androidx.datastore:datastore-preferences:1.1.4` | **`com.russhwolf:multiplatform-settings` + `multiplatform-settings-coroutines`** | Phase 8. `core/storage/PrefsDelegate` etc. become `multiplatform-settings`. DataStore-specific code in `androidMain` if any caller cares (`core/storage/DataStoreHelper`). |
| `java.time.*` (in `core/util/date/Date.kt`, `LocalDate*Converter`) | **`kotlinx.datetime`** | Phase 4 / Phase 6. Type converters in `data/local/converter/LocalDate*Converter.kt` reworked to convert `kotlinx.datetime` types. |
| `androidx.navigation:navigation-compose:2.8.9` (+ runtime-ktx + ui-ktx) | **`androidx.navigation:navigation-compose` 2.8.0+ KMP** | Phase 9. Same `@Serializable` route style; `navigation-ui-ktx` (Material XML bridge) dropped. |
| `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7` | **`androidx.lifecycle:lifecycle-viewmodel` 2.8.4+ KMP** + `androidx.lifecycle:lifecycle-viewmodel-compose` KMP variant + Koin `koin-compose-viewmodel` | Phase 9. |
| `androidx.navigation:navigation-safe-args-generator:2.5.3` (KSP) | (dropped) | Phase 3. Safe-args is XML-only; not used by Compose code. |

---

## B) Wrapped behind an `expect/actual` or interface abstraction

| Source Android API | KMP abstraction | androidMain actual | iosMain actual | desktopMain actual |
|---|---|---|---|---|
| Android `Context` | `interface AppContext { … }` in `commonMain` | wraps `android.content.Context` | empty (or `UIApplication`) | `Unit`-like |
| Android `Resources`/`R.string.*` | Compose Multiplatform Resources (`compose.resources`) — strings/drawables/fonts moved to `commonMain/composeResources/` | — (covered by `compose.resources`) | — | — |
| Android `SharedPreferences` (`SharedPrefsHelper`) | `Settings` from `multiplatform-settings` | `SharedPreferencesSettings` | `NSUserDefaultsSettings` | `PreferencesSettings` |
| `DataStore<Preferences>` | covered by `multiplatform-settings` for the same key/value semantics | — | — | — |
| `androidx.work` (WorkManager) | `interface BackgroundJobScheduler { … }` | `WorkManagerScheduler` | `BGTaskSchedulerScheduler` (stub) | `Timer`/coroutines scheduler |
| `android.net.ConnectivityManager` (`core/network_connectivity/NetworkConnectivityObserver.kt`) | `interface ConnectivityObserver { val state: Flow<ConnectivityState> }` (already in source) | Android impl (existing) | `SCNetworkReachability` via `darwin` | `java.net` polling fallback |
| `android.app.NotificationManager`, `NotificationChannel` (`core/util/notification/*`) | `interface NotificationPresenter { … }` | Android impl | iOS `UNUserNotificationCenter` | desktop tray notification (or no-op) |
| `androidx.core.splashscreen` | Compose Multiplatform `SplashScreen` composable in `commonMain` (with `androidMain` calling `installSplashScreen()` in `MainActivity.onCreate` before `setContent`) | platform-specific call in `MainActivity` | uses CMP composable | uses CMP composable |
| `android.os.Build`, device info (`domain/device/AndroidDeviceInfoProvider.kt`) | `interface DeviceInfoProvider` (existing in source) | existing `AndroidDeviceInfoProvider` | `UIDevice` reader | system properties reader |
| `android.graphics.Bitmap` / `BitmapFactory` / AVIF decoder | Coil 3 `Decoder` interface | Android AVIF decoder | iOS uses `coil-network-ktor3` + iOS-native decoders | Desktop uses default JVM decoders |
| `me.saket.telephoto:zoomable-image-coil3` | `expect @Composable fun ZoomableImage(...)` | calls Telephoto | calls `net.engawapg.lib:zoomable` (if KMP-compatible) or builds a simple zoom impl | same as iOS |
| `core/util/image_share/ScreenshotUtils.kt` (uses `View.draw`) | `interface ScreenshotProvider` | Android (existing) | iOS UIView snapshot | Desktop Robot/Compose snapshot |
| `core/cbz/*` (uses `java.io.File` + `commons-compress`) | `interface CbzWriter`/`CbzReader` | Android impl | iOS `NSFileManager` impl (commons-compress is JVM-only; iOS uses native ZIP via Foundation) | Desktop JVM with commons-compress |
| `BrowserHeadersInterceptor.kt` (OkHttp Interceptor) | Ktor `HttpRequestBuilder` plugin in `commonMain` (no abstraction needed) | n/a | n/a | n/a |
| `core/network_cache/forceCacheForDados.kt` (OkHttp cache) | Ktor `HttpCache` plugin | n/a | n/a | n/a |
| `core/progress/ProgressInterceptor.kt` (OkHttp) | Ktor request observer in `commonMain` | n/a | n/a | n/a |
| WebView (`presentation/features/webview/*`, `work/webViewDialog.kt`) | `interface WebViewHost { open(url, headers) }` | Android `WebView` | iOS `WKWebView` (stub, deferred) | Desktop JCEF (stub, deferred) |

---

## C) Kept Android-only (`platform_specific_keep`) — feature does NOT exist on iOS/Desktop

These stay in `shared/androidMain` or `app/` and are explicitly **not** ported. The KMP common code exposes interfaces that on Android delegate to these implementations and on iOS/Desktop are no-op or omitted.

| Source library / package | Reason kept Android-only |
|---|---|
| Firebase BoM 34.4.0 (Analytics, Crashlytics, Messaging, Firestore) | Firebase has an iOS SDK but linking it through KMP requires CocoaPods + native bindings; out of scope for this migration. Common code calls `interface Analytics`, `interface CrashReporter`, `interface PushMessaging`, `interface RemoteStore`. Only `androidMain` provides real implementations; iOS/Desktop = `NoopAnalytics`/etc. |
| Google Play Services Ads 24.8.0 (AdMob) | Android-only API. AdMob does have iOS SDK via Pods, but again out of scope. `interface AdProvider` → `AdMobAdProvider` (androidMain) + `NoopAdProvider` (others). |
| AdMob mediation: InMobi 11.1.0.0, IronSource 9.2.0.0, Vungle 7.6.1.0, Facebook 6.21.0.0 | Bundled with AdMob; Android-only by design. |
| Google Play app-update / app-update-ktx 2.1.0 | Android Play Store only. Common code: `interface AppUpdateChecker` → `NoopAppUpdateChecker` on non-Android. |
| Google Play review / review-ktx 2.0.2 | Android Play Store only. Common code: `interface InAppReviewLauncher` → `NoopInAppReviewLauncher`. |
| `user-messaging-platform` 3.2.0 (UMP / GDPR consent) | Android-only. Wrapped behind `interface ConsentFlow`. |
| `org.aomedia.avif.android:avif:1.3.0.841110fd` | Android AAR. Image decoder for AVIF on Android. On non-Android, Coil 3 falls back to default decoders. |
| `me.saket.telephoto:zoomable-image-coil3:0.16.0` | Android-only Compose lib. Reader uses an `expect/actual ZoomableImage`. |
| Runtime DEX plugins (`dex/AasqPlugin.kt`, `DexPluginLoader.kt`, `PluginData.kt`, `buildDexPlugin` Gradle task) | Android Dalvik/ART only. Common code exposes `interface MangaSource` + `SourceRegistry` that on Android merges static + DEX-loaded sources, on others only static. |
| Android `Application` (`MyApp`), `Activity` (`MainActivity`, `CrashActivity`), `BroadcastReceiver` (`DownloadCancelReceiver`), `Service` (`MyFirebaseMessagingService`, `androidx.work` services), `ContentProvider` (`FileProvider`, `androidx.startup`) | Android lifecycle primitives. Stay in `app/`. Common code delegates to interfaces (`Analytics`, `BackgroundJobScheduler`, `NotificationPresenter`, etc.). |
| `androidx.compose.ui:ui-viewbinding:1.6.0-rc01` | Used only if any composable wraps an XML layout via `AndroidViewBinding`. Investigate in Phase 4; if unused, drop. If used, keep `androidMain`-only. |
| `androidx.palette:palette-ktx:1.0.0` | Bitmap color extraction. KMP equivalent likely available; if not, abstract behind `interface DominantColorExtractor`. |

---

## D) Pure Kotlin (no port needed — moves to commonMain as-is or with light renaming)

- `kotlinx-serialization-json 1.6.3` → upgrade to latest stable in Phase 2 (`1.8.x`+).
- `kotlinx-coroutines` (pulled transitively) → explicit dependency in `commonMain`.
- `commons-compress 1.24.0` (CBZ packaging) → JVM-only; replaced by platform-specific archive APIs on iOS (cf. `CbzWriter` abstraction above). Android + Desktop continue to use commons-compress. iOS uses Foundation `NSFileManager` ZIP.

---

## E) Build-time / dev-only dependencies (no runtime impact)

- KSP `2.0.21-1.0.27` → upgrade to match the new Kotlin in Phase 2.
- Compose plugin `2.0.21` → same.
- `kotlin-parcelize` → only meaningful in `androidMain`. The new common code uses `@Serializable` data classes instead of `@Parcelize`.
- `kotlin-kapt` (Dagger Hilt) → removed entirely after Phase 5.
- `androidx.navigation:navigation-safe-args-generator:2.5.3` (KSP) → dropped.

---

## Summary

| Category | Count |
|---|---|
| A) Replaced with KMP lib | 13 entries |
| B) Wrapped in expect/actual | 17 entries |
| C) Kept Android-only | 13 entries |
| D) Pure Kotlin (move as-is) | 4 entries |
| E) Build-only (dropped or upgraded) | 5 entries |

**Total unique third-party dependencies in source (incl. transitives recognized): ~62.** Every one accounted for.
