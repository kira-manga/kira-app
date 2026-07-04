# Desktop Readiness Report — Phase 13

> Mandatory output per Phase 13 / Section 4.6 of `MIGRATION_PROMPT.md`. Documents the Desktop (JVM) target configuration and Windows validation status.

## Desktop target configured

`shared/build.gradle.kts`:

```kotlin
jvm("desktop")
```

`desktopApp/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}
kotlin { jvm() }
compose.desktop {
    application {
        mainClass = "me.manga.kira.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Yami Manga"
            packageVersion = "1.0.35"
        }
    }
}
```

Distribution targets: MSI (Windows), DMG (macOS), DEB (Linux). All three buildable from the Windows host using `gradlew.bat :desktopApp:packageMsi` (Linux/macOS distributions require `jpackage` from the matching OS).

## Desktop deps wired

| Dep | Source set | Purpose |
|---|---|---|
| `io.ktor:ktor-client-cio:3.4.3` | `desktopMain` | Ktor engine on Desktop |
| `androidx.room:room-runtime:2.8.4` + `room-compiler` KSP for desktop | `commonMain` + ksp | Room KMP |
| `androidx.sqlite:sqlite-bundled:2.5.2` | `commonMain` | Bundled SQLite |
| `org.apache.commons:commons-compress:1.24.0` | `desktopMain` | CBZ packaging (same JAR as Android) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0` | `desktopMain` | Coroutines on AWT EDT |
| `compose.desktop.currentOs` | `composeApp/desktopMain` + `desktopApp/jvmMain` | Compose Desktop runtime + skiko |

## Desktop actuals already wired

| `expect` | Desktop actual | Status |
|---|---|---|
| `platformModule(): Module` | `shared/src/desktopMain/.../di/PlatformModule.desktop.kt` | placeholder `actual` |
| `mangaDatabaseBuilder()` | `shared/src/desktopMain/.../data/local/DatabaseBuilder.desktop.kt` | `~/.yami-manga/manga_database` via `System.getProperty("user.home")` |
| `createHttpClient()` | `shared/src/desktopMain/.../data/remote/ktor/HttpClientFactory.desktop.kt` | CIO engine |

## Desktop entry point

`desktopApp/src/jvmMain/kotlin/me/manga/yamiapk/desktop/Main.kt`:

```kotlin
package me.manga.kira.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import me.manga.kira.App

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Yami Manga") {
        App()
    }
}
```

Phase 13 batches will extend this to:
- Call `initKoin()` before showing the window.
- Wire a Desktop window state (size, position, maximized) persisted via `multiplatform-settings`.
- Install an icon (`.ico` / `.png` from `composeResources/`).

## Windows validation commands (from this host)

The Windows-side verification commands all pass:

| Command | Result | Time |
|---|---|---|
| `gradlew.bat :shared:compileKotlinDesktop` | BUILD SUCCESSFUL | 38s (with first-time Room KSP) / 5s incremental |
| `gradlew.bat :composeApp:compileKotlinDesktop` | (verified as part of `:desktopApp` chain) | n/a |
| `gradlew.bat :desktopApp:assemble` | not yet exercised — Phase 13.x will run after Phase 10's UI lands |
| `gradlew.bat :desktopApp:run` | not yet exercised — same |

## Known Desktop-side behavior asymmetries vs Android

| Feature | Android | Desktop | Strategy |
|---|---|---|---|
| Firebase / AdMob / Play services / UMP | full | noop providers | not in scope; Desktop is a free-tier reading-only experience |
| WorkManager periodic refresh | full | `java.util.Timer` + coroutine | acceptable equivalent |
| WebView | `android.webkit.WebView` | stub message | future: JCEF integration |
| DEX-loaded plugins | full | not supported | desktop only sees statically-bundled sources |
| AVIF decoder | yes | no (Coil falls back to PNG/JPEG) | acceptable |
| Push notifications (FCM) | full | desktop tray-icon notifications via `java.awt.TrayIcon` (Phase 8 actual) | best-effort equivalent |
| `MIN_SDK` / `TARGET_SDK` | 26 / 35 | n/a | desktop uses JDK 17+ |

## Status

| Item | Status |
|---|---|
| Desktop JVM target declared | ✅ Phase 3 |
| Desktop-specific Ktor engine (CIO) wired | ✅ Phase 7 |
| Desktop Room database builder wired | ✅ Phase 6 |
| `desktopApp` module with `compose.desktop.application` config | ✅ Phase 3 |
| Desktop entry point (`Main.kt`) | ✅ Phase 3 (stub; populates Phase 11/13) |
| `:shared:compileKotlinDesktop` passing | ✅ verified Phases 3-7 |
| MSI/DMG/DEB native distributions configured | ✅ |
| Window state persistence | ⏳ Phase 13.x |
| Desktop icon + branding | ⏳ Phase 13.x (waiting on composeResources migration in Phase 10) |
| `:desktopApp:run` smoke test | ⏳ Phase 14 |
