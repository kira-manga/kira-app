import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "me.manga.kira.composeapp"
        compileSdk = 37
        minSdk = 26
        // CMP-9547 (same fix verified on :ui): the new plugin doesn't package Compose-MP
        // composeResources (.cvr) into the APK by default → runtime MissingResourceException on
        // stringResource(...). This flag restores the asset copy. (Distinct from androidResources.enable,
        // which is Android res/R — :composeApp has no res/.)
        experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            // Desktop target must be JDK 17+ because KCEF (`dev.datlag:kcef`, the JCEF wrapper
            // backing the embedded WebView) is built against the JetBrains Runtime 17.x and ships
            // bytecode that requires Java 17 to load. The Android target stays on JVM 11 because
            // its compileSdk path is separately constrained by AGP / Compose-Android requirements.
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iosX64 intentionally omitted: Compose Multiplatform 1.11.0 dropped Apple x86_64 support
    // (Kotlin deprecation KT-81596). Apple Silicon Macs use iosSimulatorArm64; physical iPhones
    // use iosArm64. Restore iosX64 only if/when CMP republishes those artifacts.
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            // baseName MUST match the Swift `import ComposeApp` statement in
            // `iosApp/iosApp/iOSApp.swift` and `ContentView.swift`. K/N derives the Swift module
            // name from the framework's CFBundleName, which equals baseName. PascalCase matches
            // Swift conventions and the rest of the iOS toolchain's expectations.
            baseName = "ComposeApp"
            isStatic = true

            // :shared was deleted (strangler-fig Phase 6): its Koin bootstrap (doInitKoin /
            // initKoin / platformModule / sharedModule) moved into this module's own
            // me.manga.kira.di package, so the Swift host's IosKoinKt.bootstrapIosKoin() entry is
            // already part of ComposeApp's exported surface — nothing external to export.
        }
    }

    applyDefaultHierarchyTemplate()

    // expect/actual classes (LocalAppLocale here; MangaDatabase in :shared) are a Beta Kotlin
    // feature (KT-61573) used intentionally. Opt in to silence the per-declaration
    // "expect/actual classes are in Beta" warning across all targets.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            // Rework modules (Phase 8). Pulled in as `implementation` because :composeApp is the
            // top of the graph — nothing downstream needs to re-export these types. Importing :ui
            // transitively brings :presentation → :domain → :core; importing :data brings the
            // concrete LibraryRepositoryImpl. Until Phase 8.y swaps the user-facing route, the
            // rework graph compiles alongside the legacy graph but is invoked only via the new
            // Koin module wiring in this same module.
            implementation(project(":ui"))
            implementation(project(":data"))
            // Persistence + transport foundations (strangler Phases 1-2). :composeApp's Koin modules
            // instantiate :data repo impls whose constructors take these types — so the types must be on
            // its compile classpath (implementation deps don't leak transitively):
            //  - :data:local — SourcesGenericModule / Stage0Ports reference DAO types.
            //  - :data:remote — SharedModule constructs the shared HttpClient/ApiClient here,
            //    whose `api` param is ApiClient.
            //  - :sources:legacy (Phase 3) — SourcesGenericModule / DefaultSourceRegistry /
            //    LegacyKotlinSourceClient reference BaseMangaRepository; RepoIconResolver uses MangaSource.
            implementation(project(":data:local"))
            implementation(project(":data:remote"))
            //  - :data:download (strangler Phase 4) — allReworkModules() appends downloadModule();
            //    IosBackgroundBridge + DownloadsReworkModule + route adapters reference the engine's
            //    DownloadRepository/state types (which kept their me.manga.kira.presentation.features.
            //    download.* package on the move).
            implementation(project(":data:download"))
            implementation(project(":sources:legacy"))
            implementation(project(":platform"))

            // Generic-sources subsystem (Stage-0). :composeApp is the assembly root that wires the
            // engine + config + legacy adapters behind the :sources:contracts interfaces. :data only
            // ever sees :sources:contracts (and does not yet consume the registry — Stage-1).
            implementation(project(":sources:contracts"))
            implementation(project(":sources:engine"))
            implementation(project(":sources:config"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material)
            implementation(libs.compose.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.components.ui.tooling.preview)
            implementation(compose.preview)
            implementation(libs.compose.animation)

            // Koin for Compose + ViewModels
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.viewmodel.navigation)

            // Lifecycle + Navigation (KMP)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)

            // Coil 3 (KMP)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.coil.svg)

            // Zoomable image (KMP-capable per author docs)
            implementation(libs.zoomable)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // Azora pilot parity tests parse fixture JSON into the legacy DTOs to compare against the engine.
            implementation(libs.kotlinx.serialization.json)
            // MapSettings — to build an (unused-for-Azora) SourcesRepository in the :data flip integration test.
            implementation(libs.multiplatform.settings.test)
            // #27 (B13): koinApplication/module for the Desktop+iOS DI-graph registration smoke tests
            // (mirrors the Android :app KoinGraphRegistrationTest). koin-core is otherwise only a
            // transitive impl dep of koin-compose; declare it for the test classpath explicitly.
            implementation(libs.koin.core)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.androidx.palette.ktx)
            // Koin Android (androidContext()) + WorkManager — needed by the platformModule.android
            // actual relocated here from :shared (strangler-fig Phase 6).
            implementation(libs.koin.android)
            implementation(libs.androidx.work.runtime.ktx)

            // Android-only image extras
            implementation(libs.coil.network.okhttp)
            implementation(libs.avif)
            implementation(libs.google.material)
        }

        iosMain.dependencies {
            // CrashKiOS — reports uncaught Kotlin/Native exceptions to Crashlytics as FATAL crashes
            // WITH the symbolicated Kotlin stack, so each crash groups by its real Kotlin type/stack.
            // (A raw NSException raised from the host carries no Kotlin frames, so Crashlytics lumps
            // every Kotlin crash under one `ExceptionObjHolderImpl` issue.) iosMain-only: CrashKiOS has
            // no jvm/desktop target, so it must NOT go in commonMain. Wired by setupCrashlytics()
            // (CrashSetup.kt), called from the Swift host after FirebaseApp.configure(). The framework
            // is static, so its FIR* symbols resolve at the app link from the SPM FirebaseCrashlytics
            // (no linker plugin needed). Requires kotlin.native.cacheKind.iosSimulatorArm64=none
            // (see gradle.properties).
            implementation(libs.crashkios.crashlytics)
        }

        val desktopMain = getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)

                // KCEF — Compose-MP-friendly JCEF (Chromium Embedded Framework) wrapper that powers
                // `WebViewHost.desktop.kt`. First-launch downloads ~150-200 MB of platform-specific
                // CEF + JetBrains Runtime binaries to the install dir (defaults to
                // `~/.kira/kcef-bundle` — see `Main.kt`). Required JDK is 17+ (jvmTarget is set
                // above). The library itself is small; only the runtime CEF bundle is large and
                // never bundled with the jar.
                implementation(libs.kcef)
            }
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "me.manga.kira.composeapp.generated.resources"
    generateResClass = auto
}

// ---------------------------------------------------------------------------------------------
// Locale key-parity lint (same gate as :ui:checkLocaleKeyParity, for :composeApp's own Res
// catalog). Fails the build when any values-<loc>/ is missing a string key present in the default
// values/. Wired into `check`.  Run directly: ./gradlew :composeApp:checkLocaleKeyParity
// ---------------------------------------------------------------------------------------------
val checkLocaleKeyParity = tasks.register("checkLocaleKeyParity") {
    group = "verification"
    description = "Fail if any values-<loc>/ is missing a string key present in the default values/."
    val resDir = layout.projectDirectory.dir("src/commonMain/composeResources").asFile
    inputs.dir(resDir)
    doLast {
        // `(?![-\w])` after "string" excludes <string-array> (a bare word boundary would match it).
        val stringRe = Regex("""<string(?![-\w])([^>]*)\bname="([^"]+)"([^>]*)>""")
        fun keysIn(dir: File): Set<String> {
            val files = dir.listFiles { f -> f.isFile && f.extension == "xml" } ?: return emptySet()
            val keys = mutableSetOf<String>()
            for (f in files) {
                // Strip XML comments first so a commented-out <string name=...> can't be counted.
                val text = f.readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
                for (m in stringRe.findAll(text)) {
                    val attrs = m.groupValues[1] + m.groupValues[3]
                    if (Regex("""translatable\s*=\s*"false"""").containsMatchIn(attrs)) continue
                    keys += m.groupValues[2]
                }
            }
            return keys
        }
        val defaultKeys = keysIn(File(resDir, "values"))
        val problems = StringBuilder()
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
            ?.sortedBy { it.name }
            ?.forEach { locDir ->
                val missing = (defaultKeys - keysIn(locDir)).sorted()
                if (missing.isNotEmpty()) {
                    val shown = missing.take(15).joinToString(", ")
                    val more = if (missing.size > 15) " … (+${missing.size - 15} more)" else ""
                    problems.appendLine("  ${locDir.name}: ${missing.size} missing key(s): $shown$more")
                }
            }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Locale key-parity check FAILED — keys present in values/ but missing in a locale " +
                    "(they would silently fall back to English):\n$problems" +
                    "\nAdd the missing translations (see scripts/check_locale_parity.py).",
            )
        }
        logger.lifecycle("Locale key-parity OK: ${defaultKeys.size} default keys present in every locale.")
    }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(checkLocaleKeyParity) }
