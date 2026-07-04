# yami-kmp

Kotlin Multiplatform port of [Yami Manga](https://github.com/Apdelrahman1911/yami-manga-apk-main) targeting **Android, iOS, and Desktop (JVM)** with 100% behavior parity to the original Native Android app.

## Migration status

> **Current status (2026-05-29):** active work happens on the **`architecture-rework`** branch — a clean-architecture rework (`:core`/`:domain`/`:data`/`:platform`/`:presentation`/`:ui`/`:composeApp` modules with an MVI + strangler-fig migration off the legacy `:shared`/`:app` graph). For the authoritative, up-to-date picture see **[`ARCHITECTURE.md`](ARCHITECTURE.md)** (the rework contract) and **[`PHASE0_PROGRESS.md`](PHASE0_PROGRESS.md)** (current state + remaining-work plan). The `migration/` folder and the original-Native-port framing below are **historical** — they describe the earlier `kmp-migration` phase and should not be treated as current.

This repository began as an **in-progress** migration from a Native Android app to Kotlin Multiplatform; that earlier phase's progress and audit trail live under [`migration/`](migration/) (now historical — see banner above).

- Source project (read-only reference): `D:\yami manga\yami-manga-apk-main`
- Target KMP project: this repository
- Active branch: `architecture-rework` (earlier phase: `kmp-migration`)
- Default branch (protected): `main`

## Locked stack

| Concern | Library |
|---|---|
| Multiplatform | Kotlin Multiplatform |
| Shared UI | Compose Multiplatform |
| DI | Koin |
| Database | Room KMP (with `androidx.sqlite:sqlite-bundled`) |
| ViewModels | `androidx.lifecycle:lifecycle-viewmodel` 2.8.4+ KMP |
| Navigation | `androidx.navigation:navigation-compose` 2.8.0+ (type-safe routes via `kotlinx.serialization`) |
| Networking | Ktor Client (replacing Retrofit) |
| Image loading | Coil 3 (KMP) |
| Date/time | `kotlinx.datetime` |
| Settings | `multiplatform-settings` |
| Logging | TBD (Napier / Kermit — see `migration/library-decisions.md`) |

## Targets

- Android (preserves original `minSdk`, `targetSdk`, `compileSdk`)
- iOS: `iosArm64`, `iosSimulatorArm64`, `iosX64`
- Desktop JVM (Windows primary; macOS/Linux supported where Compose MP allows)

## Build

The development host is macOS (`./gradlew`); the tasks below are current. See `CLAUDE.md`
("Build / test / run") for the full gate cadence — this README's migration-era sections are
historical.

```bash
./gradlew :app:assembleDebug                    # Android debug APK
./gradlew :composeApp:compileKotlinDesktop      # Desktop/JVM compile
./gradlew :desktopApp:run                       # run the Desktop app (JDK 17+)
```

iOS builds run from `iosApp/` on macOS (Xcode, `xcodegen generate` first) — see `CLAUDE.md`.

## Migration docs

See [`migration/`](migration/) — the source of truth for what has been migrated, what is pending, library decisions, and verification evidence.
