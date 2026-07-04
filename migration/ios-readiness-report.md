# iOS Readiness Report — Phase 12

> Mandatory output per Phase 12 / Section 4.5 of `MIGRATION_PROMPT.md`. Documents iOS target configuration and the macOS-only validation commands that the project owner must run after Windows-side work completes.

## iOS targets configured

`shared/build.gradle.kts` declares all three required iOS targets:

```kotlin
listOf(
    iosX64(),
    iosArm64(),
    iosSimulatorArm64(),
).forEach { iosTarget ->
    iosTarget.binaries.framework {
        baseName = "shared"
        isStatic = true
    }
}
```

Same setup in `composeApp/build.gradle.kts` for the UI framework.

## iOS deps wired

| Dep | Source set | Purpose |
|---|---|---|
| `io.ktor:ktor-client-darwin:3.4.3` | `iosMain` | Ktor engine for iOS (NSURLSession-backed) |
| `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0` | `commonMain` (resolves iOS klibs) | KMP ViewModels |
| `org.jetbrains.androidx.navigation:navigation-compose:2.9.2` | `commonMain` (resolves iOS klibs) | KMP Compose navigation |
| `androidx.room:room-runtime:2.8.4` (+ KSP for iosArm64/iosX64/iosSimulatorArm64) | `commonMain` + per-target ksp | Room KMP |
| `androidx.sqlite:sqlite-bundled:2.5.2` | `commonMain` | Bundled SQLite for iOS Room |
| `com.fleeksoft.ksoup:ksoup:0.2.6` | `commonMain` | HTML parsing |
| `co.touchlab:kermit:2.0.4` | `commonMain` | Logging |
| `com.russhwolf:multiplatform-settings:1.3.0` | `commonMain` | Settings → NSUserDefaults on iOS |
| `io.coil-kt.coil3:coil-compose:3.4.0` + `coil-network-ktor3` | `composeApp/commonMain` | Image loading |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0` | `commonMain` | Coroutines |
| `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0` | `commonMain` | Serialization |
| `org.jetbrains.kotlinx:kotlinx-datetime:0.8.0` | `commonMain` | Date/time |
| `org.jetbrains.kotlinx:atomicfu:0.27.0` | `commonMain` | Cross-platform locks |
| `io.insert-koin:koin-core:4.2.0` | `commonMain` | DI |

## iOS actuals already wired

| `expect` | iOS actual file | Status |
|---|---|---|
| `platformModule(): Module` | `shared/src/iosMain/.../di/PlatformModule.ios.kt` | placeholder `actual` (populates as later phases land) |
| `mangaDatabaseBuilder()` | `shared/src/iosMain/.../data/local/DatabaseBuilder.ios.kt` | NSFileManager → NSDocumentDirectory |
| `createHttpClient()` | `shared/src/iosMain/.../data/remote/ktor/HttpClientFactory.ios.kt` | Darwin engine |

Plus the iOS Koin bootstrap:

- `shared/src/iosMain/.../di/KoinHelper.kt` — `doInitKoin()` callable from Swift (e.g., `KoinHelperKt.doInitKoin()`).

## What macOS validation requires (project owner runs these)

On a macOS host with Xcode 26+ installed (per Compose-MP 1.11.0 support matrix):

```bash
cd "/path/to/yami-kmp"

# 1. Validate the iOS klibs compile for every iOS target
./gradlew :shared:compileKotlinIosX64
./gradlew :shared:compileKotlinIosArm64
./gradlew :shared:compileKotlinIosSimulatorArm64

# 2. Validate the iOS frameworks build
./gradlew :shared:linkDebugFrameworkIosX64
./gradlew :shared:linkDebugFrameworkIosArm64
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64

# 3. (Optional) Build the iOS app bundle once Phase 12.x scaffolds iosApp/iosApp.xcodeproj
cd iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp \
    -configuration Debug -destination 'generic/platform=iOS Simulator' \
    -showBuildSettings | head -20

# 4. Run unit tests
./gradlew :shared:iosX64Test
./gradlew :shared:iosSimulatorArm64Test
```

## Iteration commands (Windows-side already passing)

The Kotlin compile against iOS targets succeeds from Windows (Kotlin/Native cross-compiles to iOS klibs without Xcode). The successful Windows-side commands so far:

| Command | Result | Time |
|---|---|---|
| `gradlew.bat :shared:compileKotlinIosArm64` | BUILD SUCCESSFUL | 1m 47s (first run, with toolchain download) / 3s incremental |
| `gradlew.bat :shared:compileKotlinIosSimulatorArm64` | BUILD SUCCESSFUL | 42s / 3s incremental |
| `gradlew.bat :shared:compileKotlinIosX64` | not yet exercised — toolchain available but the Phase 3-13 verification used only Arm64 + SimulatorArm64 |

**Windows cannot perform**:
- Framework linking (the `:link*Framework*Ios*` tasks need Apple linker)
- iOS device-simulator builds (need Xcode)
- Distribution archives (need Xcode + signing)

## iOS application host (Xcode project)

Not yet scaffolded as of commit `89092db`. Phase 12 batch 12.x will produce:

```
iosApp/
├── iosApp.xcodeproj/
│   └── project.pbxproj
└── iosApp/
    ├── Info.plist
    ├── ContentView.swift       — calls ComposeUIViewController { App() } from `composeApp`
    ├── iOSApp.swift            — @main; calls KoinHelperKt.doInitKoin() before showing ContentView
    └── Assets.xcassets/        — app icon + launch image (iOS-side equivalent of mipmap-*)
```

Recommended approach: use the `kotlin-multiplatform` Xcode integration that the Compose Multiplatform IntelliJ wizard generates. Run `./gradlew :composeApp:syncFramework -Pkotlin.native.cocoapods.platform=iphonesimulator -Pkotlin.native.cocoapods.archs=arm64` to generate the framework that Xcode will embed.

## Known iOS-side behavior asymmetries vs Android

| Feature | Android | iOS | Strategy |
|---|---|---|---|
| Firebase Analytics / Crashlytics / Messaging / Firestore | full | noop providers (Phase 8 documents) | iOS deferred — owner must add Firebase iOS SDK via Pods + write `actual class IosAnalytics` etc. |
| AdMob (rewarded/banner/native) + mediation (InMobi/IronSource/Vungle/Facebook) | full | noop providers | iOS deferred — owner must add Google Mobile Ads SDK for iOS + mediation pods |
| Play app-update / in-app review / UMP consent | full | noop providers | iOS doesn't have App Store equivalents in the same form |
| `WebView` (in webview feature + `core/work/webViewDialog.kt`) | `android.webkit.WebView` | stub showing "WebView not yet supported on iOS" | iOS implementation should use `WKWebView`; deferred |
| DEX-loaded runtime source plugins (`dex/AasqPlugin.kt` + `buildDexPlugin` Gradle task) | full | not supported — DEX is Dalvik/ART only | iOS has access only to statically-bundled sources. Asymmetry documented for user. |
| AVIF decoder (`org.aomedia.avif.android`) | yes (Android JNI) | no — Coil falls back to PNG/JPEG | acceptable; AVIF is rare in manga sources |
| WorkManager periodic refresh | yes | `BGTaskScheduler` actual (deferred to Phase 8) | iOS background tasks are more restricted than Android WorkManager |

## Status

| Item | Status |
|---|---|
| iOS targets declared (iosArm64, iosSimulatorArm64; iosX64 dropped per CMP 1.11.0) | ✅ Phase 3 |
| iOS-specific Ktor engine (Darwin) wired | ✅ Phase 7 |
| iOS Room database builder (NSFileManager) wired | ✅ Phase 6 |
| iOS Koin Swift entry (`KoinHelper.doInitKoin()`) wired | ✅ Phase 5 |
| iOS expect/actual coverage for all in-scope `expect` declarations | ✅ all 32 expects implemented |
| `iosApp/iosApp.xcodeproj` scaffolded | ⏳ generation deferred — `iosApp/project.yml` (xcodegen) committed instead; user runs `xcodegen` once on macOS |
| `iosApp/Info.plist` audited for runtime gaps | ✅ 2026-05-24 — `armv7` → `arm64`, added `NSPhotoLibraryAddUsageDescription`, `CFBundleDisplayName=Yami`, `ITSAppUsesNonExemptEncryption=false` |
| `ComposeApp` framework `baseName` matches Swift `import ComposeApp` | ✅ 2026-05-24 — fixed from `composeApp` → `ComposeApp` |
| Shared symbols exported through composeApp framework | ✅ 2026-05-24 — `export(project(":shared"))` added so `KoinHelperKt` is visible from Swift |
| AS run configuration for iOS | ✅ 2026-05-24 — `.idea/runConfigurations/iosApp.xml` committed; activates after first `xcodegen` on macOS |
| Firebase / AdMob / Play services iOS SDK integration | ❌ deferred (noop providers); not in migration scope, none of them block first-launch |
| `WKWebView` iOS WebView impl | ✅ Phase 9 — `WebViewHost.ios.kt` wraps WKWebView via UIKitView |
| macOS validation commands documented (above) | ✅ |
| Kotlin/Native distribution downloaded + cached (Windows-side test) | ✅ |
| iOS Kotlin code compiles on Windows for arm64 + SimulatorArm64 | ✅ re-verified 2026-05-24 after baseName + export edits: `:shared` 25s, `:composeApp` 1m 16s, both BUILD SUCCESSFUL |
| Desktop tall-page image blur | ⏳ deferred per owner instruction 2026-05-24 — see `pending-work.md` Future work item 0 |
