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
  `ScreenshotProvider.saveBitmapBytesToGallery`). The obsolete HTTP exception is removed;
  no `NSAppTransportSecurity` override is present, so URL Loading System ATS defaults apply.
  This source policy is not a universal socket firewall or signed-build validation.
- `project.yml` — [xcodegen](https://github.com/yonaskolb/XcodeGen) project spec. Run `xcodegen`
  on macOS to (re)generate `iosApp.xcodeproj`.
- `iosApp/Info-Debug.plist` — isolated Debug metadata: **Kira Manga Debug**, no production URL
  schemes or remote-push background mode, and Firebase explicitly disabled.
- `Package.resolved` — reviewed shipping SwiftPM resolution, retained outside the generated project.

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
  cd "$repo_root"
  /usr/bin/ruby scripts/release/verify-toolchain-inputs.rb --restore-swiftpm
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
The same metadata pins the four Gradle wrapper files, Action commits, and shipping
SwiftPM lock. Update these inputs together through review; do not edit a digest just
to silence a mismatch. Gradle dependency verification/locks and full runner/JDK/Xcode
reproducibility remain separate requirements, not guarantees of this bootstrap check.

### Locked SwiftPM inputs for TestFlight

TestFlight generates the shipping project and resolves its reviewed SwiftPM lock
**before any protected input is exposed**. This ordering requires App63's project
semantics: Firebase is excluded from source discovery and copied only by the
existing Release-only build phase. No real or placeholder Firebase file is needed
for generation; Debug remains service-isolated. Do not use the early-generation
workflow with the older unconditional Firebase-resource project.

The fixed `--restore-swiftpm` mode copies `iosApp/Package.resolved` only to
`iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved`.
It refuses symlinked paths and a stale existing generated lock rather than silently
refreshing it. `--check-swiftpm` is read-only and rejects missing or changed bytes.
Neither mode resolves packages or invokes Xcode/Gradle. A stale disposable project
must be deliberately regenerated; never replace the reviewed canonical lock just
to make a check pass.

The credential-free Xcode preflight uses resolved-file-only flags with signing
disabled. Archive uses the same flags, DerivedData/package/cache paths, and checks
the exact lock both before and after Xcode. It cannot silently choose another
allowed version. Existing real Firebase/signing validation and the mandatory
post-archive Crashlytics/upload gates still apply.

For an intentional package update, retain the actual shipping project's raw
`Package.resolved` plus source/project/Xcode identity from an authorized resolution,
review every changed revision, and update the canonical bytes and manifest digest
together. Do not reconstruct a lock from logs, reuse a UIKit-test lock, or add an
automatic unlocked-resolution fallback. A checked-in lock alone is not evidence
that the real clean-checkout pre-secret preflight or locked archive has passed.

After this runs once, `iosApp/iosApp.xcodeproj` exists locally on the Mac, and the
`.idea/runConfigurations/iosApp.xml` run configuration that's already checked in will work in
Android Studio (Koala or newer with the Kotlin Multiplatform plugin enabled).

`project.yml` declares a **shared** `iosApp` scheme (under the top-level `schemes:` key), so
`xcodegen generate` writes `iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme`. This scheme
is what the Android Studio run configuration (`xcodeScheme = iosApp`) and `xcodebuild -scheme
iosApp` both resolve against — without it the generated project has **no** scheme and the iOS run
fails to launch in Android Studio. (Requires the **Kotlin Multiplatform** plugin enabled in AS:
Settings → Plugins → "Kotlin Multiplatform".)

Use the committed XcodeGen specification rather than hand-creating a target: it owns the Debug
isolation, Store-only resource selection/signing and Release validation gates.

## Running

Ordinary Run uses Debug: bundle ID `me.manga.kira.debug`, display name **Kira Manga Debug**.
No Firebase plist or production signing team is required. Choose your own local team for a device;
Debug has no push/association/shared-keychain capability. Firebase/Analytics/Crashlytics/FIAM/FCM
and complaint networking are disabled, while reading, downloads and local notifications work.
Archive remains Release with the unchanged Store ID, Firebase configuration and signing gates.

### From Android Studio
After the one-time bootstrap above, select **iosApp** in the run-configuration dropdown,
pick a simulator or attached device, press **Run**. AS calls the bound Gradle pre-build task
(`:composeApp:embedAndSignAppleFrameworkForXcode`) and hands off to `xcodebuild`.

### From the command line
```bash
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17' \
  -onlyUsePackageVersionsFromResolvedFile -disableAutomaticPackageResolution -skipPackageUpdates
```

## What still requires manual work on the Mac

| Item | Why |
|---|---|
| Code-signing identity / provisioning profile | Apple-issued, machine-bound. Set in Xcode → Signing & Capabilities. |
| Physical-device selection in Run dropdown | Selected per-machine; not persisted in the project file. |
| Release `GoogleService-Info.plist` | Real Store config stays gitignored and is copied only for Release, which also hard-gates the Crashlytics dSYM upload. Debug neither needs nor bundles it. |
| Release push delivery (APNs) | Existing Store capability/APNs registration is unchanged. Debug never registers for remote notifications and claims no associated domains or production URL schemes. |
| AdMob iOS SDK | Not integrated on iOS (Android-only stack; owner: keep as-is). |

## Compile-only verification without Xcode

The Kotlin side cross-compiles to iOS klibs on any host with a JDK:

```bash
./gradlew :composeApp:compileKotlinIosArm64 :composeApp:compileKotlinIosSimulatorArm64
```

Framework linking (`linkDebugFrameworkIos*`) and `xcodebuild` require macOS + Xcode.

## Physical side-by-side check (not replaced by configuration/host tests)

Keep the Store app installed, install Debug with your local team, and confirm both names/icons open
separate libraries/settings. Exercise reading, downloads, background completion and local
notifications in Debug; verify complaints fail rather than report submission success. Open a
production activation link and confirm only the Store app claims it. Confirm no Debug Firebase/APNs
registration or production analytics/crash/FIAM activity. Repeat the corresponding Android check.
