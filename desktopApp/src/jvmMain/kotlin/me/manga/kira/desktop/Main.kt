package me.manga.kira.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.datlag.kcef.KCEF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.manga.kira.App
import me.manga.kira.admin.Admin
import me.manga.kira.core.webview.KcefState
import me.manga.kira.di.allReworkModules
import me.manga.kira.di.applyDesktopLogFloor
import me.manga.kira.di.initKoin
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop entry point. Bootstraps Koin (`initKoin()` -> shared + desktop platform module) and
 * the KCEF (Chromium Embedded Framework) runtime, then mounts the [App] composable. The
 * `WebViewHost` actual on Desktop relies on KCEF being (or becoming) initialized; how that init is
 * driven differs per OS — see below.
 *
 * **KCEF init pattern — per OS.**
 *  - **Windows / Linux (ASYNC, never blocks the window).** `KCEF.init` is launched on a background
 *    coroutine (`Dispatchers.IO`) just before `application { … }`, so the Compose window opens
 *    IMMEDIATELY (finding r7-cc-3 / owner decision A6 — the previous blocking `runBlocking` init
 *    kept the window invisible for minutes on first run while ~150-200 MB of CEF + JetBrains
 *    Runtime binaries downloaded, which looked like a hung app; subsequent launches still resolve
 *    in milliseconds, just off the startup path now). A WebView composable that mounts before init
 *    finishes shows the in-flight spinner and recovers through the [KcefState] handshake below.
 *    First-run download progress is logged in 10% steps via the builder's `onDownloading` callback.
 *    `KCEF.init` is idempotent and thread-safe per upstream contract.
 *  - **macOS (HARD-SKIPPED).** KCEF init is never attempted on macOS — the embedded JCEF/CEF
 *    bring-up is non-viable in this project's run setups (JBR jcef / CEF-framework layout) and a
 *    native loader failure there can prevent the Compose window from ever opening. So on macOS we
 *    call no `KCEF.init` at all, publish `KcefState.markUnavailable()`, and `application { … }`
 *    opens normally; `WebViewHost.desktop.kt` renders its placeholder (KcefState.initialized stays
 *    `false`). `-XstartOnFirstThread` is deliberately NOT set in build.gradle.kts for this reason —
 *    see the macOS else-branch below and build.gradle.kts for the full rationale.
 *
 * **Late-init recovery handshake ([KcefState]).** On Windows/Linux the launcher publishes success
 * through `KcefState.markInitialized()` the moment the async init completes. `WebViewHost.desktop.kt`
 * collects that flag, shows a progress spinner while it is still `false`, and re-keys its
 * `KCEF.newClientOrNullBlocking` acquisition on it — so a WebView screen that mounted mid-download
 * mounts the real browser as soon as init lands, with no restart. On macOS the launcher instead
 * publishes `KcefState.markUnavailable()` (init is hard-skipped), so the Desktop `WebViewHost`
 * actual shows the "unavailable" placeholder rather than an in-flight spinner — same code path, no
 * special-casing in the host.
 *
 * **Helper sub-process path, per OS.** JCEF's `CefApp` derives `browser_subprocess_path` from
 * `java.home` when not pinned, which on a non-JetBrains JDK points at a `jcef_helper` binary that
 * doesn't exist there, crashing the GPU/helper process. We therefore pin the helper to our unpacked
 * bundle — but the filename differs per OS:
 *  - Windows: `jcef_helper.exe`.
 *  - Linux: `jcef_helper` (no extension). (We pin it for the same `java.home` reason as Windows.)
 *  - macOS: we DO NOT pin a helper path. On macOS the helper is a signed `.app` bundle inside the
 *    framework layout, and overriding it with a bare path breaks code-signing / NSBundle resolution;
 *    JCEF must derive it from the framework bundle itself. We only pin `cachePath` on macOS.
 *
 * **Install location & bundling-prep.** [resolveKcefInstallDir] prefers a CEF bundle shipped inside
 * the packaged app (under `compose.application.resources.dir`) when present, falling back to the
 * runtime-download dir `$user.home/.kira/kcef-bundle`. See that function's KDoc for how to populate
 * the bundled dir per OS/arch for a true offline-capable distribution. When no bundle is shipped,
 * behaviour is unchanged from before: KCEF downloads to `~/.kira/kcef-bundle` on first run.
 *
 * **Fail-safe contract.** EVERY KCEF code path here is wrapped so that any failure (including the
 * documented macOS `icudtl.dat`/`NSBundle` upstream bug) logs and degrades — it NEVER crashes
 * startup. On failure `KcefState.initialized` stays `false` and `WebViewHost.desktop.kt` renders its
 * graceful placeholder/progress UI. Windows/Linux behaviour is preserved bit-for-bit when init
 * succeeds there.
 *
 * **Shutdown.** `KCEF.disposeBlocking()` runs after `application { … }` returns, but only if init
 * actually COMPLETED successfully (`KcefState.initialized`). The guard is deliberately stricter than
 * "attempted": upstream `KCEF.dispose()` WAITS for an in-flight init, so disposing while the async
 * first-run download is still running would stall process exit for minutes — and disposing a runtime
 * that never started (macOS hard-skip, failed init) is a no-op anyway.
 *
 * **JDK requirement.** KCEF 2025.03.23 targets JetBrains Runtime 17.0.14 → JDK 17+. The
 * `composeApp` Desktop target, the `:shared` Desktop target and this module are all pinned to
 * `JvmTarget.JVM_17` in their respective Gradle files for that reason.
 */
fun main() {
    // SECURITY: raise the Kermit floor to Warn in packaged/release runs (mirrors the Android
    // MyApp.kt floor) so the legacy scrapers' Info diagnostics — request URLs and header maps
    // including Cookie/cf_clearance/User-Agent values — never print to stdout. Dev runs (`-ea`)
    // keep verbose logs.
    applyDesktopLogFloor()

    // C1 (2026-07-03): debug-only admin. Admin.isAdmin defaults to false (fail-closed); a Desktop
    // dev run opts in via JVM assertions (`-ea` — the same dev-run probe the log floor's
    // isHttpLoggingEnabled uses) or the explicit `-Dkira.debug=true` property. Packaged
    // distributions set neither, so release users never see the admin complaint console.
    Admin.isAdmin = object {}.javaClass.desiredAssertionStatus() ||
        System.getProperty("kira.debug") == "true"

    // `allReworkModules()` layers the architecture-rework feature graph on top of the legacy
    // bindings. Both graphs coexist until the Phase 8.y route swap takes the rework Library
    // screen user-facing. Duplicate-binding diagnostics fire here at startup if anything collides
    // with the legacy SharedModule bindings.
    initKoin(allReworkModules())

    val osName = System.getProperty("os.name").lowercase()
    val isMac = osName.contains("mac")
    val isWindows = osName.contains("win")

    // Resolved once and shared by init + dispose. Prefers a bundled CEF dir if the packaged app
    // ships one; otherwise the historic runtime-download path under the user home.
    val kcefInstallDir = resolveKcefInstallDir()

    if (!isMac) {
        // ---- Windows / Linux: ASYNC init on a background coroutine — the window opens FIRST. ----
        // (Finding r7-cc-3 / owner decision A6.) The old path ran the whole init inside
        // `runBlocking(Dispatchers.IO)` BEFORE `application { … }`, so a first-run user stared at NO
        // window for the entire ~150-200 MB CEF download. Startup must never block on KCEF: launch
        // the init and fall straight through to `application { … }`. WebViewHost recovers via the
        // KcefState handshake — while `initialized` is false it shows the in-flight spinner, and it
        // re-keys its client acquisition the moment the flag flips true. Errors print to stderr but
        // DO NOT crash — on failure `KcefState.markUnavailable()` flips the host to its terminal
        // placeholder, and `newClientOrNullBlocking()` keeps returning null gracefully.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // KCEF reports init failures via onError / onRestartRequired and RETURNS NORMALLY
            // (its internal state goes to Error, not Initialized). Track that so we publish the
            // terminal "unavailable" state instead of falsely flipping `initialized` true.
            // AtomicBoolean (not a plain var): the callbacks may fire on a KCEF-internal thread,
            // and the post-init read below runs on the coroutine's IO thread — the atomic makes
            // the write visible without relying on KCEF's undocumented threading.
            val initFailed = AtomicBoolean(false)
            runCatching {
                KCEF.init(
                    builder = {
                        installDir(kcefInstallDir)

                        // First-run install feedback: the window is already open and interactive, so
                        // surface download progress to the console in 10% steps (the raw callback
                        // fires once per network chunk — unthrottled it would flood stdout). Upstream
                        // PackageDownloader reports percent on a 0-100 scale. `onInitialized` fires
                        // for log visibility only — the KcefState flip happens on the post-init
                        // success path below, same as before.
                        var lastLoggedDecile = -1
                        progress {
                            onDownloading { percent ->
                                val decile = (percent.toInt() / 10) * 10
                                if (decile > lastLoggedDecile) {
                                    lastLoggedDecile = decile
                                    println(
                                        "KCEF first-run download: $decile% — the app stays usable; " +
                                            "WebView screens show a spinner until init completes.",
                                    )
                                }
                            }
                            onInitialized {
                                println("KCEF initialized — embedded WebView screens are live.")
                            }
                        }

                        settings {
                            // CRITICAL (Windows + Linux): JCEF's `CefApp` derives
                            // `browser_subprocess_path` from `java.home` when not set — which on a
                            // non-JetBrains JDK points at a `jcef_helper` binary that doesn't exist,
                            // crashing the helper/GPU process. Pin it to our unpacked bundle.
                            // The filename differs per OS: Windows ships `jcef_helper.exe`, Linux
                            // ships `jcef_helper` (no extension). macOS is handled in the else-branch
                            // (it must NOT pin a helper path — see Main.kt KDoc).
                            // See: KevinnZou/compose-webview-multiplatform#289.
                            val helperName = if (isWindows) "jcef_helper.exe" else "jcef_helper"
                            browserSubProcessPath = File(kcefInstallDir, helperName).absolutePath
                            resourcesDirPath = kcefInstallDir.absolutePath
                            localesDirPath = File(kcefInstallDir, "locales").absolutePath
                            cachePath = File(kcefInstallDir, "cache").absolutePath
                            // SwingPanel embedding needs windowed rendering, not the offscreen path
                            // (CefRendering.DEFAULT in the WebViewHost actual relies on the OS
                            // compositor, not CEF's windowless paint loop).
                            windowlessRenderingEnabled = false
                            noSandbox = true
                        }

                        // Disable Chromium's GPU process. JCEF's GPU sub-process consistently fails
                        // to launch on Windows (error_code=63 — missing/incompatible swiftshader,
                        // sandbox conflicts, or the helper-path issue described above). Software
                        // rendering is more than enough for the auth/CAPTCHA flows this WebView
                        // powers, and the visual fidelity loss is invisible at typical page sizes.
                        // `--disable-extensions` shaves a few seconds off startup and avoids a
                        // separate extension-host process crash path observed on JBR 17 + Windows 11.
                        addArgs(
                            "--disable-gpu",
                            "--disable-gpu-compositing",
                            "--disable-software-rasterizer",
                            "--disable-extensions",
                        )
                    },
                    onError = { throwable ->
                        initFailed.set(true)
                        throwable?.printStackTrace()
                    },
                    onRestartRequired = {
                        initFailed.set(true)
                        System.err.println(
                            "KCEF reports a JVM restart is required to finish initialization. " +
                                "WebViewHost will run in degraded mode this session — relaunch Kira to recover.",
                        )
                    },
                )
                // init() returned without throwing. If a failure callback fired, KCEF is in its
                // Error state (not Initialized) — publish the terminal "unavailable" state so
                // WebViewHost shows its placeholder instead of an eternal spinner. Otherwise flip
                // the recovery flag: any WebView screen already mounted (the window opened while we
                // were downloading/initializing) re-keys its client acquisition and mounts the
                // browser now.
                if (initFailed.get()) KcefState.markUnavailable() else KcefState.markInitialized()
            }.onFailure { throwable ->
                // Never swallow coroutine cancellation (structured-concurrency contract).
                if (throwable is CancellationException) throw throwable
                KcefState.markUnavailable()
                System.err.println(
                    "KCEF init failed on ${System.getProperty("os.name")} — WebView screens will " +
                        "render the degraded-mode placeholder this session.",
                )
                throwable.printStackTrace()
            }
        }
    } else {
        // ---- macOS: KCEF init is SKIPPED (the app must reliably open). ---------------------------
        // Embedded JCEF/CEF cannot be initialized safely on macOS in this project's run setups, so
        // we do NOT call KCEF.init at all — `WebViewHost.desktop.kt` degrades to its placeholder
        // (KcefState.initialized stays false) and every other screen works normally.
        //
        // WHY (observed): when launched under a JetBrains Runtime (e.g. Android Studio's bundled JBR,
        // which Android Studio's Run button uses as java.home), JCEF's `libjcef.dylib` is loaded
        // FROM the JBR and hard-codes the CEF framework path to `<JBR>/Contents/Frameworks/Chromium
        // Embedded Framework.framework` — which the plain JBR does not ship. The native loader fails
        // (`cef_load_library: dlopen … no such file`) inside `CefApp` startup; that failure is in
        // native code (not catchable by Kotlin try/catch) and, together with the macOS main-thread
        // CEF requirement, prevents the Compose window from ever opening. Skipping init is the only
        // way to guarantee the window opens.
        //
        // To actually get the embedded WebView on macOS you must NOT run under a JBR: launch with a
        // plain JDK 17 (e.g. `JAVA_HOME=<jdk17> ./gradlew :desktopApp:run` from a terminal, or a
        // packaged distributable) so KCEF's own downloaded bundle (~/.kira/kcef-bundle) is used
        // instead of the JBR's jcef. Even then, KCEF 2025.03.23 has a known macOS icudtl.dat/NSBundle
        // layout bug — so macOS WebView remains best-effort. Until that's resolved upstream (or we
        // ship a manually-bundled, layout-correct CEF), macOS uses the graceful placeholder.
        System.err.println(
            "Skipping KCEF init on macOS (embedded WebView unavailable — see Main.kt KDoc). " +
                "WebView screens render the degraded-mode placeholder; the app opens normally.",
        )
        // Signal the WebView actual that init will NOT run this session, so it shows the
        // "unavailable" placeholder rather than an eternal in-flight spinner.
        KcefState.markUnavailable()
    }

    application {
        val windowState = rememberWindowState(
            size = DpSize(1280.dp, 800.dp),
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "Kira Manga",
            state = windowState,
        ) {
            App()
        }
    }

    // After all windows close, tear down the CEF native process. Skipping this leaves zombie helper
    // processes. Guarded on a SUCCESSFUL init (`KcefState.initialized`), deliberately stricter than
    // the old "attempted" guard: upstream `KCEF.dispose()` WAITS for an in-flight init, so disposing
    // while the async first-run download is still running would stall process exit for minutes.
    // A runtime that never started (macOS hard-skip, failed/still-running init) has nothing to
    // dispose — `disposeBlocking()` is a no-op for the New/Error states anyway.
    if (KcefState.initialized.value) {
        runCatching { KCEF.disposeBlocking() }
            .onFailure { it.printStackTrace() }
    }
}

/**
 * Resolve the directory KCEF unpacks (or finds a pre-shipped) CEF + JetBrains Runtime bundle into.
 *
 * **Bundling-prep.** A packaged distribution can SHIP the CEF bundle so the app works fully offline
 * with no ~150-200 MB first-run download. To use that path, place a populated bundle under the
 * packaged app's resources dir in a folder named `kcef-bundle`; at runtime Compose exposes that dir
 * via the `compose.application.resources.dir` system property, and this function prefers it when it
 * exists. Otherwise it falls back to the historic runtime-download location
 * `$user.home/.kira/kcef-bundle`, leaving the download behaviour exactly as before.
 *
 * **How to populate the bundled dir (per OS / arch).** The bundle is platform- AND architecture-
 * specific (CEF ships different native binaries for win-x64, linux-x64, macos-x64, macos-arm64,
 * etc.), so a cross-platform installer must ship the matching bundle for each target it builds:
 *  1. Run the app once on the target OS/arch with the default download path so KCEF downloads and
 *     unpacks the correct bundle into `~/.kira/kcef-bundle`.
 *  2. Copy that unpacked bundle into the per-target packaging input as `kcef-bundle/`, wired into
 *     `nativeDistributions` via an `appResourcesRootDir` layout so Compose places it under the
 *     packaged resources dir.
 *  3. The shipped bundle must match the JVM/JBR the app runs on; mixing CEF and runtime versions
 *     re-triggers the helper-path / icudtl.dat failures described in [main]'s KDoc.
 *
 * The `kcef-bundle` subfolder name keeps the download out of the repo and out of the volatile
 * `compose.application.resources.dir` root (which is overwritten on each `./gradlew run`).
 */
private fun resolveKcefInstallDir(): File {
    val bundled = System.getProperty("compose.application.resources.dir")
        ?.let { File(it, "kcef-bundle") }
        ?.takeIf { it.exists() }
    if (bundled != null) {
        return bundled
    }
    return File(System.getProperty("user.home"), ".kira/kcef-bundle")
}

/*
 * §253 audit-trail postscript — cluster285 §253 sweep (2026-05-29)
 *
 * Classification token: DESKTOP-HOST / LIVE-HOST (thin entry-point that bootstraps DI plus
 * the native CEF runtime, then delegates ALL UI into the rework :composeApp graph).
 *
 * LIVE evidence (cited, not assumed):
 *  - desktopApp build.gradle.kts:54 — compose.desktop { application { mainClass =
 *    "me.manga.kira.desktop.MainKt" } }. The JVM launches the synthetic MainKt facade
 *    generated for the top-level fun main() in THIS file; this is the one and only Desktop
 *    process entry. Confirmed sole match across the repo (no rival mainClass).
 *  - Main.kt:57 — initKoin(allReworkModules()) is the host's first action.
 *  - KoinInitializer.kt:36-43 (:shared/commonMain) — fun initKoin(extraModules, appDeclaration)
 *    runs startKoin { appDeclaration(); modules(allSharedModules() + platformModule() +
 *    extraModules) }. On Desktop, platformModule() resolves to the jvm/desktop actual; the
 *    extraModules slot receives the rework feature graph.
 *  - ReworkModules.kt:16 (:composeApp/commonMain) — allReworkModules() returns the 15-element
 *    rework slice list (library + details + reader + ... + downloads). This is the LIVE-WIRED
 *    consumer the cluster150 FULFILLED-PREDICTION named: Desktop Main is one of the three hosts
 *    threading the rework graph through initKoin.
 *  - App.kt:294 (:composeApp/commonMain) — fun App() is the shared Compose root mounted inside
 *    the single Window here, so this host carries zero feature UI of its own.
 *
 * Status: LIVE-HOST. Not legacy, not a fulfilled-and-retired port. The KDoc above documents real,
 * current runtime behaviour — no stale references to retired symbols were found.
 *
 * Delta-axes (host concerns that differ from the Android/:app and iOS hosts):
 *  1. Compose entry: application { Window(onCloseRequest = ::exitApplication) { App() } } —
 *     desktop-window lifecycle, not an Android Activity; window state via rememberWindowState.
 *  2. Koin startKoin wiring: initKoin(allReworkModules()) with the default empty appDeclaration —
 *     Desktop layers NO host-only Koin block, unlike Android (which passes androidContext etc.).
 *  3. Native-runtime bootstrap: KCEF.init runs runBlocking(Dispatchers.IO) BEFORE application { }
 *     on Windows/Linux only; KCEF.disposeBlocking() runs AFTER it returns (guarded) — a Desktop-only
 *     WebView host concern with no Android analogue (Android WebView is an OS component, not a
 *     bundled CEF download).
 *  4. macOS divergence: macOS HARD-SKIPS KCEF init entirely (no KCEF.init call). The embedded
 *     JCEF/CEF bring-up is non-viable in this project's run setups (JBR jcef / CEF-framework layout),
 *     and a native loader failure can prevent the Compose window from opening — so init is skipped,
 *     KcefState.markUnavailable() is published, and WebViewHost.desktop renders its placeholder
 *     (KcefState.initialized stays false). build.gradle.kts deliberately does NOT set
 *     -XstartOnFirstThread for the same reason (it would regress to "window never opens").
 *  5. No WorkManager/FCM/Service/Receiver surface: background jobs, push tokens, and screenshots
 *     are mediated through the :platform desktop actuals (BackgroundJobScheduler, PushTokenProvider
 *     no-ops), so this main() registers no scheduler and no notification channel.
 *  6. JDK pinning: KCEF 2025.03.23 forces JvmTarget.JVM_17 on this module and its --add-opens
 *     JVM args, a launch-config delta absent from the mobile hosts. (-XstartOnFirstThread is
 *     deliberately NOT set on macOS — see build.gradle.kts and delta-axis 4.)
 *
 * Nested-comment hazard check: this block contains no slash-star, no star-slash, and no
 * slash-star-star sequences; every delimiter above is spelled in words. Block is balanced and
 * opens with slash-star on its own line and closes with space-star-slash on its own line.
 *
 * UPDATE (2026-06, finding r7-cc-3 / owner decision A6): delta-axis 3 above is historical.
 * KCEF.init no longer blocks before application { } on Windows/Linux — it now runs on a
 * background CoroutineScope(SupervisorJob() + Dispatchers.IO) so the Compose window opens
 * immediately on ALL OSes; the first-run ~150-200 MB download happens while the app is usable,
 * with progress logged in 10% steps via the builder's onDownloading callback. WebViewHost
 * recovers through the pre-existing KcefState handshake (spinner while initializing, re-keyed
 * client acquisition on markInitialized, terminal placeholder on markUnavailable). The dispose
 * guard tightened from "init attempted" to "init completed" (KcefState.initialized) because
 * upstream KCEF.dispose() waits out an in-flight init. macOS behaviour is unchanged (hard-skip,
 * delta-axis 4).
 */
