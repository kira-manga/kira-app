# Kira Manga app — agent instructions

This is the KMP/Compose Multiplatform app in the `yami-kmp` Gradle build. It is a behavior and UI
parity port of the read-only `native-app/` reference, now branded **Kira Manga**. Production
package/application/bundle identity is `me.manga.kira`; the relocated source files still contain
some historical package names and should not be renamed casually.

The code is in an architecture-rework transition. `:shared` is no longer included in
`settings.gradle.kts`; new code belongs in the rework modules. Historical KDoc and comments may
still say `:shared` or describe retired fallback behavior. Verify behavior against Kotlin source,
build files, DI registration, and tests.

## Read first

| Document | Use |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Owner rules, layering contract, coding conventions, restricted paths, and build gates |
| [`docs/HANDOFF.md`](docs/HANDOFF.md) | Feature/platform status and deferred work; confirm claims against source when it conflicts |
| [`docs/ARCHITECTURE_REWORK_CONTRACT.md`](docs/ARCHITECTURE_REWORK_CONTRACT.md) | Owner’s architecture contract |
| [`docs/ENGINEERING_NOTES.md`](docs/ENGINEERING_NOTES.md) | Downloads, reader, sources, image pipeline, and device QA |
| [`docs/sources/ADDING_SOURCES.md`](docs/sources/ADDING_SOURCES.md) | Add, convert, edit, validate, test, or retire a source |

## Verified build facts

Source of truth is [`gradle/libs.versions.toml`](gradle/libs.versions.toml) and each module’s
`build.gradle.kts`:

- Kotlin 2.4.0, AGP 9.2.1, Gradle wrapper 9.6.1, Compose MP 1.11.1, Koin 4.2.2, Ktor 3.5.1,
  Coil 3.5.0, Room 2.8.4, kotlinx-serialization 1.11.0, lifecycle 2.10.0, and navigation 2.9.2.
- Android `compileSdk 37`, `targetSdk 35`, `minSdk 26`, JVM target 11. Desktop uses JVM/JDK 17.
- KMP Apple targets are `iosArm64` and `iosSimulatorArm64`; `iosX64` is intentionally absent.
  `:composeApp` produces the static `ComposeApp` framework consumed by the Xcode host in
  [`iosApp/`](iosApp/), which is not a Gradle module.
- Firebase/Crashlytics, Play services, ads/mediation, WorkManager, and Android-only integrations
  are host/platform dependencies. The real Firebase config is required for a shipping build.

## Modules and dependency direction

There are 16 included Gradle modules:

```text
:core
  ↑ :domain
  ↑ :presentation
  ↑ :ui
  ↑ :composeApp          (composition root: navigation, DI, source runtime, platform wiring)

:data:local   → :core                         Room/SQLite persistence
:data:remote  → :core                         Ktor transport/API client
:platform     → :core                         expect/actual platform facades
:sources:contracts → :core, :domain            public source ports and config model
:sources:engine    → :sources:contracts        generic request/extraction engine
:sources:config    → :sources:contracts        signed manifest/delta synchronization lifecycle
:sources:legacy    → :core, :domain, :data:local, :data:remote, :platform
:data:download     → :platform, :data:local, :sources:legacy
:data             → :core, :domain, :platform, :data:local, :data:download,
                     :sources:legacy, :sources:contracts
```

`:app` is the Android host and `:desktopApp` is the Desktop launcher. `:composeApp` deliberately
assembles the modules directly because `implementation` dependencies do not leak all types to the
composition root. Confirm an edge in the owning build script before adding a dependency; in
particular, `:data` does not depend on `:data:remote`, and `:sources:engine` and `:sources:config`
must remain independent of one another.

## Feature and source conventions

- Rework presentation uses `presentation/.../mvi/MviViewModel.kt`: immutable state, sealed intent,
  one-shot effect, `StateFlow`, and an unlimited effect channel. New feature ViewModels should use
  use cases rather than repositories, typed `AppResult`/`AppError`, and `launchSafely {}` for
  fire-and-forget work. `CancellationException` must be rethrown.
- This is not yet true of every file: legacy/transition ViewModels and adapters still exist and
  some use direct `viewModelScope.launch`. Do not “fix” those mechanically while implementing an
  unrelated feature.
- Navigation and Koin composition live in `:composeApp`; `:ui` renders state and should not own
  navigation or data access.
- The generic source contract is in `:sources:contracts`; the engine is declarative and has no
  HTTP-library dependency; Ktor and platform implementations are wired at the composition root.
  Config validation is all-or-nothing per document.
- The bundled document is revision **5** and contains exactly the **12 approved `generic` sources**.
  It contains no legacy stanza. `IncrementalSourceCatalogManager` conditionally fetches a signed v2
  manifest, downloads only missing immutable source revisions, and atomically activates a complete
  verified catalog in Room. `DefaultSourceRegistry` has no legacy adapter or inference path: an api
  absent from the active catalog has no client. The same active catalog feeds source metadata, host
  trust, and download routing.
- New UI strings must be added to a topic-specific `strings_pfix_*.xml` file in the default and all
  11 locale folders. Both `:ui:checkLocaleKeyParity` and `:composeApp:checkLocaleKeyParity` are
  wired into their module `check` tasks.

## Verification

Run from `Kira manga/`; prefer the warm-cache/offline form when appropriate:

```bash
./gradlew :composeApp:compileKotlinDesktop \
  :composeApp:compileAndroidMain \
  :composeApp:compileKotlinIosSimulatorArm64 --offline

./gradlew :core:desktopTest :domain:desktopTest :data:desktopTest \
  :data:local:desktopTest :data:download:desktopTest :platform:desktopTest \
  :presentation:desktopTest :composeApp:desktopTest :ui:desktopTest \
  :sources:engine:desktopTest :sources:config:desktopTest :sources:legacy:desktopTest

./gradlew :ui:checkLocaleKeyParity :composeApp:checkLocaleKeyParity
```

The CI Android/JVM job runs those 12 `desktopTest` suites, app unit/DI tests, and a debug APK
assembly. iOS CI compiles both Apple targets. `:app` debug tasks need
[`app/google-services.json.example`](app/google-services.json.example) copied to
`app/google-services.json`; release tasks reject the placeholder unless the explicit path-validation
flag `-PallowPlaceholderGoogleServices=true` is supplied. Static analysis is CI-only standalone
ktlint 1.5.0 and detekt 1.23.7 against committed baselines.

## Restricted paths

Do not edit [`native-app/`](native-app/) or
`sources/legacy/src/commonMain/kotlin/me/manga/kira/sources_repositry/` without explicit instruction.
The latter is the old hand-written scraper implementation (the package name is intentionally
misspelled). Run the native reference’s own `native-app/gradlew` from inside `native-app/`; never
assume the app wrapper builds it.
