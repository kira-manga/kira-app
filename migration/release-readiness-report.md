# Release Readiness Report — Phase 14 / Section 44

> Mandatory output per `MIGRATION_PROMPT.md` Section 44.

## Android release configuration preserved

`app/build.gradle.kts` (committed Phase 3, unchanged through later phases):

| Field | Source value | New value | Match |
|---|---|---|---|
| `applicationId` | `me.manga.kira` | `me.manga.kira` | ✅ |
| `minSdk` | 26 | 26 | ✅ |
| `targetSdk` | 35 | 35 | ✅ |
| `compileSdk` | 35 | 36 (bumped — see L-1 in audit; required by transitive deps) | ⚠️ documented |
| `versionCode` | 35 | 35 | ✅ |
| `versionName` | `1.0.35` | `1.0.35` | ✅ |
| `namespace` | `me.manga.kira` | `me.manga.kira` | ✅ |
| Signing config (release) | env-driven (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) | env-driven, same names | ✅ |
| R8 minify | release: enabled | release: enabled (isMinifyEnabled = true) | ✅ |
| Resource shrinking | release: enabled | release: enabled (isShrinkResources = true) | ✅ |
| ProGuard rules | `proguard-rules.pro` | `proguard-rules.pro` (preserved verbatim) | ✅ |
| `viewBinding = true` | yes | yes | ✅ |
| `buildConfig = true` | yes | yes (needed for AdMob ID BuildConfig fields) | ✅ |
| `compose = true` | yes | yes | ✅ |

## ProGuard / R8 rules preserved

`app/proguard-rules.pro` copied verbatim from source (Phase 3). Same R8 full-mode flag (`android.enableR8.fullMode=true` in `gradle.properties`).

Additional R8 keep rules emitted automatically by:
- kotlinx.serialization plugin (keep rules for `@Serializable` classes)
- Room KSP-generated code
- Hilt → removed
- Koin (no R8 rules needed; Koin uses no reflection on JVM/Android)

## Manifest entries preserved

All preserved verbatim in `app/src/main/AndroidManifest.xml`:
- 7 `<uses-permission>` (INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, AD_ID, POST_NOTIFICATIONS optional, WRITE_EXTERNAL_STORAGE maxSdk=28)
- `<application>` attributes (icon, theme, supportsRtl, enableOnBackInvokedCallback, networkSecurityConfig, dataExtractionRules, fullBackupContent, largeHeap, windowSoftInputMode)
- `.MainActivity` launcher activity + intent filter
- `.crash.CrashActivity`
- AdMob `<meta-data>` (`com.google.android.gms.ads.APPLICATION_ID = ca-app-pub-6540850069916280~1205773408`)
- `FileProvider` (`${applicationId}.fileprovider`)
- `androidx.work.impl.foreground.SystemForegroundService` override (`foregroundServiceType="dataSync"`)
- `androidx.startup.InitializationProvider` removal (`tools:node="remove"`)
- `.presentation.features.download.ui.test2.DownloadCancelReceiver` (with `ACTION_CANCEL_DOWNLOAD`, `ACTION_CANCEL_CHAPTER_DOWNLOAD` intent filters)
- `.firebase_cores.messaging.MyFirebaseMessagingService` (with `com.google.firebase.MESSAGING_EVENT` intent filter)
- FCM default notification icon meta-data

## Signing config

Source `app/build.gradle.kts` reads keystore from env vars + falls back to `gradle.properties`. Preserved verbatim:

```kotlin
signingConfigs {
    create("release") {
        val envKeystoreFile = env("KEYSTORE_FILE")
        val localKeystore = "yami-release.keystore"
        storeFile = file(envKeystoreFile ?: localKeystore)
        storePassword = env("KEYSTORE_PASSWORD") ?: (findProperty("KEYSTORE_PASSWORD") as String?)
        keyAlias = env("KEY_ALIAS") ?: (findProperty("KEY_ALIAS") as String?)
        keyPassword = env("KEY_PASSWORD") ?: (findProperty("KEY_PASSWORD") as String?)
    }
}
```

## AdMob IDs

| Build type | Source | New (preserved) |
|---|---|---|
| debug | Google test IDs (`ca-app-pub-3940256099942544/…`) | same |
| release | from `gradle.properties` (`ADMOB_REWARDED_ID`, `ADMOB_NATIVE_ID`, `ADMOB_BANNER_ID`) with test-IDs as fallback | same wiring; same `gradle.properties` ids (`ca-app-pub-6540850069916280/…` preserved) |

## Firebase

`google-services.json` copied to `app/google-services.json` (Phase 3). Configures the same Firebase project as source. Same `com.google.gms:google-services:4.4.4` plugin applied to `:app`.

## CI workflow

Source has `.github/workflows/release.yml`. This file was NOT carried into yami-kmp during Phase 3 — needs to be brought across in Phase 14 with any path updates required for the multi-module layout. Action item.

## Validation commands

| Command | Status | Notes |
|---|---|---|
| `gradlew.bat :app:assembleDebug` | ✅ verified Phases 3-7 | All passing |
| `gradlew.bat :app:assembleRelease` | ⏳ Phase 14 | Requires keystore env vars to be set in the build environment |
| `gradlew.bat :app:bundleRelease` (AAB for Play Store) | ⏳ Phase 14 | Same env requirement |
| `gradlew.bat :app:lintRelease` | ⏳ Phase 14 | |
| `gradlew.bat :composeApp:run` (Desktop sanity) | ⏳ Phase 14 | |
| `gradlew.bat :desktopApp:packageMsi` (Windows MSI) | ⏳ Phase 13/14 | |

## Pending Phase 14 follow-ups

1. Bring across `.github/workflows/release.yml` with path updates for the multi-module layout.
2. Run `:app:assembleRelease` with real keystore + AdMob IDs. Verify the APK size hasn't ballooned (Koin + Ktor + Ksoup add some weight vs Hilt + Retrofit + jsoup — expected ~+2 MB).
3. Smoke test the release APK on a physical Android device (Sections 47, 31).
