# Dependency Replacement Report

> Per Section 48 of `MIGRATION_PROMPT.md`. Every library replacement is recorded with: old library + version, new library + version, reason, official source verified, migration risk, affected files, behavior parity verification method.

---

## R1. Dagger Hilt → Koin

| Field | Value |
|---|---|
| Old | `com.google.dagger:hilt-android:2.57.1` + `hilt-android-compiler:2.57.1` (kapt) + `androidx.hilt:hilt-navigation-compose:1.3.0` + `androidx.hilt:hilt-work:1.0.0` + `androidx.hilt:hilt-compiler:1.0.0` |
| New | `io.insert-koin:koin-core:4.2.0` + `koin-android:4.2.0` + `koin-compose:4.2.0` + `koin-compose-viewmodel:4.2.0` |
| Reason | Hilt is JVM/Android-only and tied to kapt — not KMP-compatible. Koin is the locked DI by user policy (`MIGRATION_PROMPT.md` Section 6). |
| Official source | <https://insert-koin.io/docs/reference/koin-mp/kmp/> — verified 2026-05-22 |
| Risk | Singleton lifecycle differs (Koin is lazy by default; Hilt is eager via `@Singleton`). Mitigated by `single { … } eager` for app-startup-required singletons. |
| Files affected | All 16 files under `di/`, every `@HiltViewModel`-annotated VM (24), every `@AndroidEntryPoint`-annotated Activity/Service, every `@HiltWorker` worker, `MyApp.kt`. |
| Parity verification | Phase 5 Koin startup verification (`KoinApplication.verify()` in test) + Android runtime smoke test of every screen. |

## R2. Retrofit → Ktor

| Field | Value |
|---|---|
| Old | `com.squareup.retrofit2:retrofit:2.11.0` + `converter-gson:2.11.0` + `converter-scalars:2.11.0` |
| New | `io.ktor:ktor-client-core:3.4.3` + `ktor-client-okhttp:3.4.3` (Android) + `ktor-client-darwin:3.4.3` (iOS) + `ktor-client-cio:3.4.3` (Desktop) + `ktor-client-content-negotiation:3.4.3` + `ktor-serialization-kotlinx-json:3.4.3` + `ktor-client-logging:3.4.3` |
| Reason | Retrofit is JVM/Android-only. Ktor is KMP-native. |
| Official source | <https://ktor.io/docs/releases.html> — verified 2026-05-22 |
| Risk | Status code & body access patterns differ — Retrofit's `Response<String>` ≈ Ktor's `HttpResponse` + `bodyAsText()`. Header maps map 1-to-1. Generic `@Url` parameters map to `HttpRequestBuilder.url(...)`. |
| Files affected | `data/remote/api/IMangaDataApiServices.kt` (17 endpoints), every consumer (40+ source repositories), `di/network/NetworkModule.kt`. |
| Parity verification | Per-source HTML fixture replay in Phase 7. Android runtime smoke test. |

## R3. jsoup → Ksoup

| Field | Value |
|---|---|
| Old | `org.jsoup:jsoup:1.18.3` |
| New | `com.fleeksoft.ksoup:ksoup:0.2.6` (port of jsoup 1.20.1) |
| Reason | jsoup is JVM-only. Ksoup is the KMP-native port with API-compatible semantics. |
| Official source | <https://github.com/fleeksoft/ksoup> — verified 2026-05-22 |
| Risk | Minor parser differences possible between jsoup 1.18.3 and 1.20.1. Per-source smoke test required. |
| Files affected | Every file under `sources_repositry/` that imports `org.jsoup.*` (likely all 56 `*Repository.kt` files + parser helpers). |
| Parity verification | Spot-check parsing output against saved HTML fixture per source. Any diverging source flagged blocked in `pending-work.md` (Android can fall back to jsoup via androidMain). |

## R4. Glide-OkHttp integration → Coil 3 + Ktor3 network

| Field | Value |
|---|---|
| Old | `com.github.bumptech.glide:okhttp3-integration:4.16.0` (and any transitive Glide deps) |
| New | `io.coil-kt.coil3:coil-compose:3.4.0` + `coil-network-ktor3:3.4.0` (commonMain) + `coil-network-okhttp:3.4.0` (androidMain only) |
| Reason | Coil 3 is KMP-native; the project is already on Coil 3 — drop Glide entirely. |
| Official source | <https://coil-kt.github.io/coil/> — verified 2026-05-22 |
| Risk | Image caching behavior may differ slightly (Coil uses Okio, Glide uses its own cache). Smoke test covers it. |
| Files affected | Every `AsyncImage` call, image preloading helpers (`BlurredImageCoil.kt`, `ImageWithGradientOverlay.kt`), reader image rendering. |
| Parity verification | Reader smoke test on Android (load 10+ chapter pages) + library cover smoke test. |

## R5. DataStore preferences → multiplatform-settings (Android keeps DataStore as bridge)

| Field | Value |
|---|---|
| Old | `androidx.datastore:datastore-preferences:1.1.4` |
| New | `com.russhwolf:multiplatform-settings:1.3.0` + `multiplatform-settings-no-arg:1.3.0` + `multiplatform-settings-coroutines:1.3.0` |
| Reason | DataStore is JVM/Android. `multiplatform-settings` is KMP-native and covers the same key-value semantics. |
| Official source | <https://github.com/russhwolf/multiplatform-settings> — verified 2026-05-22 |
| Risk | DataStore is async/transactional; settings is synchronous on most platforms. We use the coroutines module to keep `Flow<T>` APIs. |
| Files affected | `core/storage/PrefsDelegate.kt`, `core/storage/SharedPrefsHelper.kt`, `core/storage/DataStoreHelper.kt` (kept in androidMain as a bridge), `core/storage/StorageKeys.kt`. |
| Parity verification | All user preferences round-trip on Android via runtime smoke test (theme, language, repo toggles, reading mode). |

## R6. java.time → kotlinx.datetime

| Field | Value |
|---|---|
| Old | `java.time.*` (JDK 8+ Time API) |
| New | `org.jetbrains.kotlinx:kotlinx-datetime:0.8.0` (+ `kotlin.time.Instant`) |
| Reason | `java.time` is JVM-only. `kotlinx.datetime` is KMP-native. |
| Official source | <https://github.com/Kotlin/kotlinx-datetime> — verified 2026-05-22 |
| Risk | Breaking changes in 0.8.0 (`Instant` moved to `kotlin.time.Instant`; `dayOfMonth` → `day`, etc.) handled by rewriting source code in Phase 4. |
| Files affected | `core/util/date/Date.kt`, `data/local/converter/LocalDateConverter.kt`, `data/local/converter/LocalDateTimeConverter.kt`, plus any other `java.time.*` users surfaced in Phase 4 audit. |
| Parity verification | Room migration test (read existing DB; ensure all dates render identically) + chapter notification timestamp display. |

## R7. Navigation safe-args → dropped

| Field | Value |
|---|---|
| Old | `androidx.navigation:navigation-safe-args-generator:2.5.3` (KSP) + `androidx.navigation.safeargs.kotlin:2.8.9` (plugin) |
| New | (dropped) — type-safe routes are provided by `@Serializable` route classes + `androidx.navigation:navigation-compose` 2.9.2 |
| Reason | safe-args is XML-only. The project is fully Compose; no XML nav graph exists. |
| Official source | <https://developer.android.com/guide/navigation/design/type-safety> — verified 2026-05-22 |
| Risk | None. |
| Files affected | `app/build.gradle.kts` (drop plugin + KSP). |
| Parity verification | Build + runtime nav smoke test. |

## R8. runtime-livedata → dropped

| Field | Value |
|---|---|
| Old | `androidx.compose.runtime:runtime-livedata` (from Compose BoM) |
| New | (dropped) — use `StateFlow` + `collectAsState()` |
| Reason | `LiveData` is Android-only. Source ViewModels already expose `StateFlow`. Phase 4 will audit for any first-party `LiveData<*>` declarations. |
| Official source | <https://developer.android.com/jetpack/androidx/releases/compose> — verified 2026-05-22 |
| Risk | If any unreported `LiveData<*>` exists, Phase 4 audit catches it and rewrites it as `StateFlow`. |
| Files affected | `app/build.gradle.kts`. |
| Parity verification | Build + smoke test. |

## R9. android.util.Log → Kermit

| Field | Value |
|---|---|
| Old | `android.util.Log` (Android SDK) |
| New | `co.touchlab:kermit:2.0.4` (wrapped behind `interface AppLogger` in commonMain) |
| Reason | Android `Log` is Android-only. Kermit gives composable platform-aware log writers and Crashlytics integration. |
| Official source | <https://kermit.touchlab.co/docs/> — verified 2026-05-22 |
| Risk | Bulk rewrite affects many files; controllable via a single facade interface. |
| Files affected | Every file currently calling `android.util.Log.*`. Catalog produced in Phase 4. |
| Parity verification | Android runtime smoke test (logs flow to Crashlytics + Logcat). |

## R10. Hilt navigation-compose → Koin compose-viewmodel + nav arguments

| Field | Value |
|---|---|
| Old | `androidx.hilt:hilt-navigation-compose:1.3.0` |
| New | `io.insert-koin:koin-compose-viewmodel:4.2.0` + `koin-compose-viewmodel-navigation:4.2.0` |
| Reason | Hilt's `hiltViewModel<T>()` is replaced by `koinViewModel<T>()` (same call site shape). Navigation argument injection uses `koinViewModel { parametersOf(navBackStackEntry.toRoute<T>()) }`. |
| Official source | <https://insert-koin.io/docs/reference/koin-mp/kmp/> — verified 2026-05-22 |
| Risk | Composable retrieval idiom changes at every call site (find/replace). |
| Files affected | Every screen composable that retrieves a ViewModel (~24 ViewModels × 1+ caller). |
| Parity verification | Build + smoke test. |

## R11. Hilt-work → manual WorkerFactory + Koin

| Field | Value |
|---|---|
| Old | `androidx.hilt:hilt-work:1.0.0` + `androidx.hilt:hilt-compiler:1.0.0` |
| New | A custom `KoinWorkerFactory : WorkerFactory` in `androidMain` that resolves dependencies from Koin |
| Reason | Hilt-work is Hilt-specific. |
| Official source | <https://developer.android.com/topic/libraries/architecture/workmanager/advanced/custom-configuration> — verified 2026-05-22 |
| Risk | Manual factory plumbing in `MyApp.onCreate()`; tested via Android workers smoke test (CBZ migration, library refresh, downloads, notifications). |
| Files affected | `MyApp.kt`, `di/workmanager/WorkManagerModule.kt`, 4 workers in `work/`. |
| Parity verification | Trigger each worker from a release build. |

---

## Replacements that did NOT happen (preserved verbatim)

- All Firebase / AdMob / Google Play / UMP libraries — version-preserved (Android-only).
- `org.apache.commons:commons-compress:1.24.0` — kept for CBZ packaging (Android + Desktop both have JVM). iOS uses native ZIP via Foundation.
- `org.aomedia.avif.android:avif` — kept Android-only.
- `me.saket.telephoto:zoomable-image-coil3` — kept Android-only behind expect/actual.
- `androidx.work:work-runtime-ktx`, `androidx.work:work-gcm` — kept Android-only.
- `androidx.compose.material3.adaptive:adaptive` — verify Phase 10 (CMP equivalent likely available).
- All Compose UI deps (`material`, `material3`, `material-icons-*`, `material:material`) — moved to CMP.

---

## Tracking matrix

| Original | New | Status |
|---|---|---|
| Hilt | Koin | planned |
| Retrofit | Ktor | planned |
| jsoup | Ksoup | planned |
| Glide-OkHttp | Coil 3 / Ktor3 net | planned |
| DataStore | multiplatform-settings (+ DataStore Android-only bridge) | planned |
| java.time | kotlinx.datetime + kotlin.time.Instant | planned |
| navigation-safe-args | dropped | planned |
| runtime-livedata | dropped | planned |
| android.util.Log | Kermit | planned |
| hilt-navigation-compose | koin-compose-viewmodel | planned |
| hilt-work | KoinWorkerFactory | planned |
