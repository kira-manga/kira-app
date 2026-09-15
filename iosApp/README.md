# iosApp — iOS host application

This directory holds the iOS host app that mounts the shared Compose Multiplatform UI inside a
SwiftUI wrapper. The Kotlin/Native framework (`ComposeApp.framework`) is produced by
`:composeApp` and embedded by Xcode via the Gradle `embedAndSignAppleFrameworkForXcode` task.

## Files

- `iosApp/iOSApp.swift` — SwiftUI `@main` entry. Koin bootstraps via
  `IosKoinKt.bootstrapIosKoin()` (in `:composeApp` iosMain) before any Compose view mounts;
  `AppDelegate.swift` owns Firebase configure, notifications, and the background-download bridge.
- `iosApp/ContentView.swift` — Wraps `MainViewControllerKt.MainViewController()` (from the
  `ComposeApp` framework) inside a `UIViewControllerRepresentable`.
- `iosApp/NativeReader/` — the shipping native UIKit reader (see `docs/ENGINEERING_NOTES.md` §3).
- `iosApp/Info.plist` — Bundle metadata. `CFBundleShortVersionString`/`CFBundleVersion` mirror
  Android's `1.0.5`. Includes `NSPhotoLibraryAddUsageDescription` (required by
  `ScreenshotProvider.saveBitmapBytesToGallery`) and `NSAppTransportSecurity.NSAllowsArbitraryLoads`
  (manga sources mix HTTP and HTTPS).
- `project.yml` — [xcodegen](https://github.com/yonaskolb/XcodeGen) project spec. Run `xcodegen`
  on macOS to (re)generate `iosApp.xcodeproj`.

## One-time macOS bootstrap (required before "Run iOS" works in Android Studio)

The `.xcodeproj` is intentionally NOT committed — its `project.pbxproj` is full of absolute
paths and per-machine UUIDs that make it hostile to source control. Generate it once on macOS:

```bash
# Verify the committed bootstrap pins and install that exact XcodeGen on macOS.
cd "<repo-root>"
(
  set -e
  repo_root="$PWD"
  xcodegen=""
  trap 'KIRA_XCODEGEN="$xcodegen" bash "$repo_root/scripts/release/cleanup-xcodegen.sh"' EXIT
  xcodegen="$(bash scripts/release/install-xcodegen.sh)"

  # Generate iosApp.xcodeproj; the trap removes the tool even if generation fails.
  cd iosApp
  "$xcodegen" generate
)
```

`release/verified-tools.json` pins XcodeGen 2.46.0's official ZIP and executable.
The installer checks the archive before extraction and the binary checksum/version
before returning its private temporary path; it never selects Homebrew or a PATH
copy. Only the binary, required runtime presets, and license are extracted. CI
installs it before protected inputs and uses that explicit path for generation;
an always-run step immediately afterward removes the installer-owned directory,
including when generation or an earlier step fails. Local generation above uses
the same guarded cleanup on exit. Do not delete temporary directories by glob.
The same metadata pins the four Gradle wrapper files and Action
commits. Update these inputs together through review; do not edit a digest just
to silence a mismatch. Gradle/SwiftPM dependency locks and full runner/JDK/Xcode
reproducibility are separate requirements, not guarantees of this bootstrap check.

After this runs once, `iosApp/iosApp.xcodeproj` exists locally on the Mac, and the
`.idea/runConfigurations/iosApp.xml` run configuration that's already checked in will work in
Android Studio (Koala or newer with the Kotlin Multiplatform plugin enabled).

`project.yml` declares a **shared** `iosApp` scheme (under the top-level `schemes:` key), so
`xcodegen generate` writes `iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme`. This scheme
is what the Android Studio run configuration (`xcodeScheme = iosApp`) and `xcodebuild -scheme
iosApp` both resolve against — without it the generated project has **no** scheme and the iOS run
fails to launch in Android Studio. (Requires the **Kotlin Multiplatform** plugin enabled in AS:
Settings → Plugins → "Kotlin Multiplatform".)

If you'd rather skip xcodegen and create the Xcode project from scratch:
1. Open Xcode → New Project → iOS App.
2. Product Name: `iosApp`, Bundle Identifier: `me.manga.kira`, Interface: SwiftUI, Language: Swift.
3. Place it at `<repo-root>/iosApp/iosApp.xcodeproj`.
4. Drag `iosApp/iOSApp.swift`, `iosApp/ContentView.swift`, `iosApp/Info.plist` into the project.
5. In Build Settings, set `FRAMEWORK_SEARCH_PATHS = $(SRCROOT)/../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`.
6. Add a Run Script build phase (before Compile Sources):
   ```
   cd "$SRCROOT/.."
   ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
   ```
7. Link `ComposeApp.framework` (Embed & Sign).
8. iOS Deployment Target: 14.0.

## Running

### From Android Studio
After the one-time bootstrap above, select **iosApp** in the run-configuration dropdown,
pick a simulator or attached device, press **Run**. AS calls the bound Gradle pre-build task
(`:composeApp:embedAndSignAppleFrameworkForXcode`) and hands off to `xcodebuild`.

### From the command line
```bash
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17'
```

## What still requires manual work on the Mac

| Item | Why |
|---|---|
| Code-signing identity / provisioning profile | Apple-issued, machine-bound. Set in Xcode → Signing & Capabilities. |
| Physical-device selection in Run dropdown | Selected per-machine; not persisted in the project file. |
| `GoogleService-Info.plist` (Firebase — Analytics/Crashlytics/FCM are live on iOS) | Real config is a secret; gitignored. Copy your own next to `Info.plist`; the committed `.example` documents the structure. Release builds also hard-gate on the Crashlytics dSYM upload. |
| Push delivery (APNs) | Needs owner console steps: Push capability on the App ID + APNs `.p8` uploaded to Firebase. Debug signs with `iosApp-nopush.entitlements` (Personal-team friendly — remote push absent locally by design). |
| AdMob iOS SDK | Not integrated on iOS (Android-only stack; owner: keep as-is). |

## Compile-only verification without Xcode

The Kotlin side cross-compiles to iOS klibs on any host with a JDK:

```bash
./gradlew :composeApp:compileKotlinIosArm64 :composeApp:compileKotlinIosSimulatorArm64
```

Framework linking (`linkDebugFrameworkIos*`) and `xcodebuild` require macOS + Xcode.
