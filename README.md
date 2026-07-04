# Kira Manga

A **Kotlin Multiplatform** manga reader (Android + iOS + Desktop/JVM, single Compose Multiplatform codebase), originally a full-parity port of the native Android app "Yami Manga". **Android and iOS are the shipping targets**; Desktop builds and runs but is not part of the current release scope.

- Package root: `me.manga.kira.*` · Android `applicationId` / iOS bundle id: `me.manga.kira` · Display name: **Kira Manga**
- App version: 1.0.0

## Documentation map

| Doc | What it is |
|---|---|
| [`docs/HANDOFF.md`](docs/HANDOFF.md) | **Start here** — full project state: architecture, per-subsystem status, decisions, deferred work, risks |
| [`CLAUDE.md`](CLAUDE.md) | Working rules for AI-assisted development: module contract, build/test gates, conventions, gotchas |
| [`docs/ENGINEERING_NOTES.md`](docs/ENGINEERING_NOTES.md) | Subsystem deep-dives: iOS background downloads, native iOS reader, sources engine, device QA checklist |
| [`docs/ARCHITECTURE_REWORK_CONTRACT.md`](docs/ARCHITECTURE_REWORK_CONTRACT.md) | The owner's verbatim architecture contract — if any rule conflicts with habit, this document wins |

## Build / run

Host is macOS. Full gate cadence and gotchas: `CLAUDE.md` § "Build / test / run".

```bash
./gradlew :app:assembleDebug        # Android debug APK (needs app/google-services.json — copy the .example)
./gradlew :desktopApp:run           # Desktop app (JDK 17+, non-JBR)

# iOS (macOS + Xcode): the .xcodeproj is generated, never committed
( cd iosApp && xcodegen generate )
# then run the iosApp scheme from Xcode

# Standard pre-commit compile gate
./gradlew :composeApp:compileKotlinDesktop :composeApp:compileAndroidMain :composeApp:compileKotlinIosSimulatorArm64 --offline
```

## Branch & CI policy

- **`main`** — default branch. **GitHub Actions never run on `main`** (owner rule; encoded in `.github/workflows/ci.yml`).
- **`testing`** — push here (or use manual workflow dispatch) to get a full CI run: compile matrix, 11 module test suites, locale-parity gates, debug APK, iOS klib compiles, blocking ktlint/detekt.
- **`release`** — CI runs plus the `release-verify` job (signed R8 release build). Release signing secrets (`KEYSTORE_BASE64` etc.) are **not configured yet**; the job warn-skips until they are.

## Restricted paths

`native-app/` (the original native app, vendored as the read-only parity spec) and `sources_repositry/` (read-only spec for source conversions) must not be edited — see `CLAUDE.md`.
