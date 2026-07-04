import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // Pin the application JVM to JDK 17. Android Studio's bundled JBR (21) ships a libjcef.dylib
    // that hard-codes a search path for the Chromium Embedded Framework relative to its own
    // Contents/Frameworks/ directory — which doesn't exist on that JBR distribution. The result
    // is a SIGSEGV during KCEF init (FindClass → dlopen of a missing CEF framework). Using a
    // plain JDK that doesn't bundle JCEF lets KCEF stay fully self-contained inside ~/.kira/kcef-bundle.
    jvmToolchain(17)

    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            // KCEF (consumed via the `:composeApp` desktopMain dep) is built against JetBrains
            // Runtime 17 and ships JDK 17 bytecode. The desktopApp launcher must therefore also
            // compile and run on JDK 17+.
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val jvmMain = getByName("jvmMain") {
            dependencies {
                implementation(project(":composeApp"))
                // Phase 6 (:shared deleted): Main.kt uses :core (KcefState) + koin-core
                // (KoinApplication/Module return types of initKoin). Both were re-exported through
                // :shared's api(:platform)->api(:core) / api(koin.core); now declared directly.
                implementation(project(":core"))
                implementation(libs.koin.core)
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)

                // KCEF — required at the launcher level for `KCEF.init { ... }` in Main.kt. The
                // dep is also declared in `:composeApp` desktopMain (for the WebViewHost actual),
                // but `implementation` deps don't leak transitively to consumers, so we need the
                // explicit reference here too.
                implementation(libs.kcef)
            }
        }
        val jvmTest = getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// #20: single source of truth for the Desktop app version — reused for the packaged installer
// version AND passed to the running JVM so DesktopAppVersionProvider resolves a real version
// (About "Version" row + the What's-New show-once gate) instead of a frozen constant.
val desktopAppVersion = "1.0.35"

// C1 (2026-07-03): `./gradlew :desktopApp:run` is a DEV run — enable JVM assertions on the `run`
// task ONLY so the -ea probes (the Admin.isAdmin debug-only gate in Main.kt and the verbose-log
// floor's isHttpLoggingEnabled) recognize it without a manual -Dkira.debug=true. This is a
// JavaExec TASK property: it must never be added as a compose.desktop.application jvmArg below,
// because those propagate into the packaged distributions' launchers (packageDmg/Msi/Deb) —
// which must stay fail-closed (no admin console, Warn log floor). `runDistributable`/`runRelease*`
// are deliberately excluded by the exact-name filter: they emulate the packaged app.
tasks.withType<JavaExec>().configureEach {
    if (name == "run") enableAssertions = true
}

compose.desktop {
    application {
        mainClass = "me.manga.kira.desktop.MainKt"

        // #20: expose the version to the launched JVM (DesktopAppVersionProvider reads
        // System.getProperty("kira.app.version")). Packaged builds fall back to the JAR manifest /
        // the in-code constant.
        jvmArgs("-Dkira.app.version=$desktopAppVersion")

        // JVM args required by KCEF / JCEF on the JDK 17+ module system. The `--add-opens` flags
        // permit the AWT bridge code to reflectively access internal sun.awt / sun.lwawt packages
        // that JCEF still depends on. The macOS-specific flags are no-ops on other OSes but their
        // presence is harmless — `compose.desktop` propagates them only to the launched JVM, not
        // to the build JVM. Source: KCEF README + JCEF macOS bring-up notes.
        jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
        jvmArgs("--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED")
        if (System.getProperty("os.name").contains("Mac")) {
            jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
            jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")

            // NOTE: `-XstartOnFirstThread` is deliberately NOT set. It would force AWT/CEF to own
            // process thread 0, which prevents the Compose/Skiko window from opening on macOS when
            // KCEF is not actually running (and KCEF init is skipped on macOS — see Main.kt). It
            // would only be needed if embedded JCEF were initialized on macOS, which this project
            // does not do (the JBR jcef / CEF-framework layout makes it non-viable here). Adding it
            // back without a working macOS KCEF path regresses the app to "window never opens".
        }

        // ProGuard / R8 keep rules for the release (packaged) build only. KCEF + JCEF resolve a lot
        // of native bridge classes in the org.cef package tree reflectively, and KCEF dispatches
        // onto Swing through kotlinx-coroutines-swing's service-loaded SwingDispatcherFactory — both
        // are invisible to the shrinker's static reachability analysis and would be stripped,
        // breaking the WebView in packaged distributions while `./gradlew run` (no shrinking) kept
        // working. The keep rules live in `compose-desktop.pro` at this module's root.
        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Kira Manga"
            packageVersion = desktopAppVersion
            // Pin a valid reverse-DNS CFBundleIdentifier for the macOS app, matching the Android
            // applicationId / iOS PRODUCT_BUNDLE_IDENTIFIER. Without this jpackage derives the
            // identifier from the main class, leaving the macOS app identity unpinned and breaking
            // signing/notarization (which requires a stable bundle id).
            macOS {
                bundleID = "me.manga.kira"
            }
        }
    }
}
