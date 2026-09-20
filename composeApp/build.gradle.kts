import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.cryptography)
}

cryptography {
    configureSwiftLinkerOpts = true
}

val generatedSourceRemoteDir = layout.buildDirectory.dir("generated/sourceRemote/commonMain")
// Xcode and IDE-launched debug builds do not inherit variables from a developer's interactive
// shell. Read only the public source authority and trust pins from the ignored local release file
// as a final fallback; explicit environment variables and Gradle properties always win.
val localSourceConfigProperties =
    providers.fileContents(rootProject.layout.projectDirectory.file(".secrets/android-release.env"))
        .asText
        .map { contents ->
            Properties().apply {
                contents.reader().use(::load)
            }
        }

fun String.unquotedLocalEnvValue(): String {
    val value = trim()
    val hasMatchingQuotes =
        value.length >= 2 &&
            value.first() == value.last() &&
            (value.first() == '"' || value.first() == '\'')
    return if (hasMatchingQuotes) value.substring(1, value.lastIndex) else value
}

fun localSourceConfigValue(name: String) =
    localSourceConfigProperties
        .map { properties -> properties.getProperty(name)?.unquotedLocalEnvValue().orEmpty() }
        .orElse("")

val sourceConfigBaseUrl =
    providers.environmentVariable("KIRA_SOURCE_CONFIG_BASE_URL")
        .orElse(providers.gradleProperty("kira.sourceConfigBaseUrl"))
        .orElse(localSourceConfigValue("KIRA_SOURCE_CONFIG_BASE_URL"))
        .orElse("")
val sourceConfigPinnedKeys =
    providers.environmentVariable("KIRA_SOURCE_CONFIG_PINNED_KEYS")
        .orElse(providers.gradleProperty("kira.sourceConfigPinnedKeys"))
        .orElse(localSourceConfigValue("KIRA_SOURCE_CONFIG_PINNED_KEYS"))
        .orElse("")
val sourceConfigAppVersion =
    providers.environmentVariable("KIRA_APP_VERSION")
        .orElse(providers.environmentVariable("MOBILE_RELEASE_VERSION_NAME"))
        .orElse(providers.gradleProperty("kira.appVersion"))
        .orElse("1.0.5")

// Public operator/build inputs only. Never inherit complaint activation from the source catalog,
// the ignored release env file, a Debug flag, or allowUnconfiguredSourceRemote. No target is shipped.
fun complaintInput(environment: String, property: String) =
    providers.environmentVariable(environment).orElse(providers.gradleProperty(property)).orElse("")

val complaintLaunchRecord = complaintInput("KIRA_COMPLAINT_LAUNCH_RECORD", "kira.complaintLaunchRecord")
val complaintLaunchBindings = mapOf(
    "APPROVED_LAUNCH_SHA256" to complaintInput("KIRA_COMPLAINT_APPROVED_LAUNCH_SHA256", "kira.complaintApprovedLaunchSha256"),
    "DEPLOYMENT_SHA256" to complaintInput("KIRA_COMPLAINT_DEPLOYMENT_SHA256", "kira.complaintDeploymentSha256"),
    "BUILD_INPUTS_SHA256" to complaintInput("KIRA_COMPLAINT_BUILD_INPUTS_SHA256", "kira.complaintBuildInputsSha256"),
    "IOS_DEFAULT_ACCESS_GROUP" to complaintInput("KIRA_COMPLAINT_IOS_DEFAULT_ACCESS_GROUP", "kira.complaintIosDefaultAccessGroup"),
    "IOS_ACCESS_GROUP_EVIDENCE_SHA256" to complaintInput(
        "KIRA_COMPLAINT_IOS_ACCESS_GROUP_EVIDENCE_SHA256", "kira.complaintIosAccessGroupEvidenceSha256",
    ),
)

// Exact record identity/equality is not a signature or external approval. Deployment identity must
// reference approved image/config/bootstrap facts; build identity must reference the approved input
// manifest. Those facts and iOS signing/group evidence are independently supplied/reviewed later.
fun complaintLaunchSnapshot(): Map<String, String> {
    val record = complaintLaunchRecord.get()
    if (record.isEmpty() || record == "Disabled") {
        return mapOf("LAUNCH_RECORD" to "Disabled") + complaintLaunchBindings.keys.associateWith { "" }
    }
    val bindings = complaintLaunchBindings.mapValues { it.value.get() }
    validateComplaintLaunchRecord(record, bindings)
    return mapOf("LAUNCH_RECORD" to record) + bindings
}

fun validateComplaintLaunchRecord(record: String, bindings: Map<String, String>) {
    fun checkInput(accepted: Boolean, message: String) {
        if (!accepted) throw GradleException("Complaint launch refused: $message")
    }
    val keys = listOf(
        "platform", "sourceBackend", "contract", "mode", "dataScopeId", "deploymentSha256", "buildInputsSha256",
        "iosDefaultAccessGroup", "iosAccessGroupEvidenceSha256",
    )
    checkInput(record.length in 1..4096 && record.all { it == '\n' || it in ' '..'~' }, "invalid record framing")
    val lines = record.split('\n')
    checkInput(lines.size == keys.size + 1 && lines.first() == "kira-complaint-launch-v1", "unsupported record")
    val fields = keys.mapIndexed { index, key ->
        checkInput(lines[index + 1].startsWith("$key="), "invalid, duplicate, or out-of-order field")
        key to lines[index + 1].removePrefix("$key=")
    }.toMap()
    checkInput(fields.getValue("platform") in setOf("ANDROID", "IOS"), "unsupported platform")
    checkInput(fields.getValue("contract") == "1", "unsupported contract")
    val scope = fields.getValue("dataScopeId")
    val scopeMatches = when (fields.getValue("mode")) {
        "LIVE" -> scope == "00000000-0000-0000-0000-000000000000"
        "TEST" -> Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(scope)
        else -> false
    }
    checkInput(scopeMatches, "unsupported mode/scope binding")
    val base = fields.getValue("sourceBackend")
    checkInput(base == sourceConfigBaseUrl.get() && validComplaintLaunchBase(base), "source-backend origin/path mismatch")
    val sha256 = Regex("[0-9a-f]{64}")
    listOf("APPROVED_LAUNCH_SHA256", "DEPLOYMENT_SHA256", "BUILD_INPUTS_SHA256").forEach {
        checkInput(sha256.matches(bindings.getValue(it)), "missing exact approved deployment/build input identity")
    }
    checkInput(fields.getValue("deploymentSha256") == bindings.getValue("DEPLOYMENT_SHA256"), "deployment identity mismatch")
    checkInput(fields.getValue("buildInputsSha256") == bindings.getValue("BUILD_INPUTS_SHA256"), "build input identity mismatch")
    val digest = MessageDigest.getInstance("SHA-256").digest(record.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    checkInput(digest == bindings.getValue("APPROVED_LAUNCH_SHA256"), "exact launch record identity mismatch")
    val group = fields.getValue("iosDefaultAccessGroup")
    val evidence = fields.getValue("iosAccessGroupEvidenceSha256")
    checkInput(group == bindings.getValue("IOS_DEFAULT_ACCESS_GROUP"), "default access group mismatch")
    checkInput(evidence == bindings.getValue("IOS_ACCESS_GROUP_EVIDENCE_SHA256"), "access group evidence mismatch")
    if (fields.getValue("platform") == "IOS") {
        checkInput(
            Regex("[A-Za-z0-9][A-Za-z0-9.-]{1,254}").matches(group) && group.split('.').all { it.isNotEmpty() } &&
                sha256.matches(evidence),
            "missing independently verified iOS default-group input/evidence",
        )
    } else {
        checkInput(group.isEmpty() && evidence.isEmpty(), "iOS binding supplied to an Android launch")
    }
}

fun validComplaintLaunchBase(value: String): Boolean {
    if (value.length !in 1..2048 || !value.startsWith("https://") || value.any { it !in '!'..'~' || it in "\\@?#" }) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    if (uri.host.isNullOrEmpty() || uri.userInfo != null || uri.query != null || uri.fragment != null ||
        (uri.port != -1 && uri.port !in 1..65535)
    ) return false
    val path = uri.rawPath.orEmpty().removeSuffix("/")
    return path.isEmpty() || path.startsWith('/') && path.drop(1).split('/').all {
        it.isNotEmpty() && it != "." && it != ".." && Regex("[A-Za-z0-9._~-]+").matches(it)
    }
}

val generateSourceRemoteConfig = tasks.register("generateSourceRemoteConfig") {
    inputs.property("baseUrl", sourceConfigBaseUrl)
    inputs.property("pinnedKeys", sourceConfigPinnedKeys)
    inputs.property("appVersion", sourceConfigAppVersion)
    inputs.property("complaintLaunchRecord", complaintLaunchRecord)
    complaintLaunchBindings.forEach { (name, value) -> inputs.property("complaint$name", value) }
    outputs.dir(generatedSourceRemoteDir)
    doLast {
        fun String.asKotlinLiteral(): String =
            replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("$", "\\$")
        val complaintConstants = complaintLaunchSnapshot().entries.joinToString("\n                ") { (name, value) ->
            "const val COMPLAINT_$name: String = \"${value.asKotlinLiteral()}\""
        }
        val packageDir = generatedSourceRemoteDir.get().dir("me/manga/kira/sources/runtime").asFile
        packageDir.mkdirs()
        packageDir.resolve("GeneratedSourceRemoteConfig.kt").writeText(
            """
            package me.manga.kira.sources.runtime

            internal object GeneratedSourceRemoteConfig {
                const val BASE_URL: String = "${sourceConfigBaseUrl.get().asKotlinLiteral()}"
                const val APP_VERSION: String = "${sourceConfigAppVersion.get().asKotlinLiteral()}"
                const val PINNED_KEYS: String = "${sourceConfigPinnedKeys.get().asKotlinLiteral()}"
                $complaintConstants
            }
            """.trimIndent() + "\n",
        )
    }
}

// Both Android and Xcode release paths fail closed if remote source trust is absent or malformed.
// Local debug/test builds intentionally keep the bundled document as an offline floor.
gradle.taskGraph.whenReady {
    val xcodeRelease = System.getenv("CONFIGURATION").equals("Release", ignoreCase = true)
    val buildingRelease = allTasks.any { task ->
        task.project.path == ":composeApp" &&
            (task.name.contains("Release") || (xcodeRelease && task.name == "embedAndSignAppleFrameworkForXcode"))
    }
    val allowUnconfigured =
        providers.gradleProperty("allowUnconfiguredSourceRemote").orNull == "true"
    // Also validate when the generated task is up-to-date. The source-only flag never bypasses this.
    if (buildingRelease || allTasks.any { it.project.path == ":app" && it.name.contains("Release") }) {
        complaintLaunchSnapshot()
    }
    if (buildingRelease && !allowUnconfigured) {
        val baseUrl = sourceConfigBaseUrl.get()
        val pins = sourceConfigPinnedKeys.get()
        if (baseUrl.isBlank() || pins.isBlank()) {
            throw GradleException(
                "Release source delivery is not configured. Set KIRA_SOURCE_CONFIG_BASE_URL and " +
                    "KIRA_SOURCE_CONFIG_PINNED_KEYS (key-id=Base64-X.509[,key-id=...]). " +
                    "Use -PallowUnconfiguredSourceRemote=true only for non-shipping build-path validation.",
            )
        }
        val uri =
            runCatching { URI(baseUrl) }
                .getOrElse { throw GradleException("Invalid source-config base URL", it) }
        val hasSecureAuthority = uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
        val hasCleanLocation = uri.query == null && uri.fragment == null
        if (!hasSecureAuthority || !hasCleanLocation) {
            throw GradleException("Source-config base URL must be credential-free HTTPS without query or fragment")
        }
        val keyIds = mutableSetOf<String>()
        pins.split(',').forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) throw GradleException("Source-config pins must use key-id=Base64-X.509 format")
            val keyId = entry.substring(0, separator)
            if (!Regex("[A-Za-z0-9._-]{1,64}").matches(keyId) || !keyIds.add(keyId)) {
                throw GradleException("Source-config pin key ids must be valid and unique")
            }
            runCatching {
                KeyFactory.getInstance("Ed25519").generatePublic(
                    X509EncodedKeySpec(Base64.getDecoder().decode(entry.substring(separator + 1))),
                )
            }.getOrElse {
                throw GradleException(
                    "Source-config pin '$keyId' is not a Base64 X.509 Ed25519 public key",
                    it,
                )
            }
        }
    }
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
        commonMain { kotlin.srcDir(generateSourceRemoteConfig) }
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
            implementation(libs.androidx.room.runtime)

            // Generic-sources subsystem (Stage-0). :composeApp is the assembly root that wires the
            // engine + config + legacy adapters behind the :sources:contracts interfaces. :data only
            // ever sees :sources:contracts (and does not yet consume the registry — Stage-1).
            implementation(project(":sources:contracts"))
            implementation(project(":sources:engine"))
            implementation(project(":sources:config"))
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material)
            implementation(libs.compose.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.components.ui.tooling.preview)
            implementation(libs.compose.ui.tooling.preview)
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
            implementation(libs.ktor.client.mock)
        }

        androidMain.dependencies {
            // Android's platform JCA lacks Ed25519 on older supported API levels; BC keeps the
            // pinned source-document verifier available across the full minSdk 26 range.
            implementation(libs.cryptography.provider.jdk.bc)
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
            // (no linker plugin needed).
            implementation(libs.crashkios.crashlytics)
        }

        getByName("desktopMain") {
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

tasks.matching { task -> task.name.startsWith("compile") && task.name.contains("Kotlin") }.configureEach {
    dependsOn(generateSourceRemoteConfig)
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
