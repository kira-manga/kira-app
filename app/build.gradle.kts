import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // kotlin.android intentionally NOT applied: AGP 9 provides built-in Kotlin (the kotlin{} extension
    // + Kotlin compilation) for com.android.application, so the explicit plugin is redundant and
    // conflicts with built-in Kotlin. Removing it let us drop android.builtInKotlin=false.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

val releaseVersionProperties = Properties().apply {
    rootProject.file("release/version.properties").inputStream().use(::load)
}
val releaseVersionName =
    env("KIRA_VERSION_NAME") ?: releaseVersionProperties.getProperty("VERSION_NAME")
val releaseVersionCode =
    (env("KIRA_BUILD_NUMBER")
        ?: env("GITHUB_RUN_NUMBER")
        ?: releaseVersionProperties.getProperty("VERSION_CODE"))
        .toIntOrNull()
        ?.takeIf { it > 0 }
        ?: error("Kira Android version code must be a positive integer")
val crashDiagnosticsEnabled =
    providers.gradleProperty("kira.enableCrashDiagnostics")
        .map { value -> value.equals("true", ignoreCase = true) }
        .orElse(false)
val sourceConfigBaseUrl =
    providers.environmentVariable("KIRA_SOURCE_CONFIG_BASE_URL")
        .orElse(providers.gradleProperty("kira.sourceConfigBaseUrl"))
val sourceConfigPinnedKeys =
    providers.environmentVariable("KIRA_SOURCE_CONFIG_PINNED_KEYS")
        .orElse(providers.gradleProperty("kira.sourceConfigPinnedKeys"))

// Production signing is intentionally environment-only. A developer without these four values
// still gets an unsigned release artifact for R8/package validation; there is no local-keystore or
// Gradle-property fallback that could accidentally sign a production bundle with the wrong key.
val releaseSigningEnvironment =
    mapOf(
        "KEYSTORE_FILE" to env("KEYSTORE_FILE"),
        "KEYSTORE_PASSWORD" to env("KEYSTORE_PASSWORD"),
        "KEY_ALIAS" to env("KEY_ALIAS"),
        "KEY_PASSWORD" to env("KEY_PASSWORD"),
    )
val hasAnyReleaseSigningValue = releaseSigningEnvironment.values.any { it != null }
val hasAllReleaseSigningValues = releaseSigningEnvironment.values.all { it != null }
val releaseKeystore = releaseSigningEnvironment.getValue("KEYSTORE_FILE")?.let(::file)
val releaseSigningReady = hasAllReleaseSigningValues && releaseKeystore?.isFile == true

// Release guard (audit: firebase-placeholder-ships-inert-in-release). Fail any release-variant
// build that would package the committed PLACEHOLDER google-services.json — which leaves
// Crashlytics / Analytics / FCM / Firestore silently inert in the shipped app with no signal.
// For build-PATH validation only (CI release-verify, local R8 smoke), bypass with
// -PallowPlaceholderGoogleServices=true (NEVER ship such an artifact).
gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { t ->
        t.project.path == ":app" && t.name.contains("Release") &&
            (
                t.name.startsWith("assemble") || t.name.startsWith("bundle") ||
                    t.name.startsWith("package") || t.name == "processReleaseGoogleServices"
            )
    }
    if (buildingRelease) {
        val allowPlaceholder =
            (project.findProperty("allowPlaceholderGoogleServices") as String?) == "true"
        val gs = file("google-services.json")
        // Catch both the committed placeholder marker and a partially-doctored file that kept the
        // dummy project_number (000000000000).
        val gsText = if (gs.exists()) gs.readText() else ""
        val isPlaceholder = gsText.contains("yami-local-placeholder") || gsText.contains("000000000000")
        if (isPlaceholder && !allowPlaceholder) {
            throw GradleException(
                "Release build is using the PLACEHOLDER app/google-services.json " +
                    "(project_id 'yami-local-placeholder'): Crashlytics, Analytics, FCM and " +
                    "Firestore would be INERT in the shipped app. Provide the real " +
                    "google-services.json before releasing, or pass " +
                    "-PallowPlaceholderGoogleServices=true to build the release artifact for " +
                    "path-validation only.",
            )
        }
        if (hasAnyReleaseSigningValue && !hasAllReleaseSigningValues) {
            val missing = releaseSigningEnvironment.filterValues { it == null }.keys.joinToString()
            throw GradleException("Incomplete release signing environment; missing: $missing")
        }
        if (hasAllReleaseSigningValues && releaseKeystore?.isFile != true) {
            throw GradleException("KEYSTORE_FILE does not point to a readable keystore file")
        }
        val allowUnconfiguredSourceRemote =
            providers.gradleProperty("allowUnconfiguredSourceRemote").orNull == "true"
        if (!allowUnconfiguredSourceRemote &&
            (sourceConfigBaseUrl.orNull.isNullOrBlank() || sourceConfigPinnedKeys.orNull.isNullOrBlank())
        ) {
            throw GradleException(
                "Release source delivery is not configured. Set KIRA_SOURCE_CONFIG_BASE_URL and " +
                    "KIRA_SOURCE_CONFIG_PINNED_KEYS (key-id=Base64-X.509[,key-id=...]). " +
                    "Use -PallowUnconfiguredSourceRemote=true only for non-shipping build-path validation.",
            )
        }
    }
}

android {
    namespace = "me.manga.kira"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.manga.kira"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField(
            "boolean",
            "CRASH_DIAGNOSTICS_ENABLED",
            crashDiagnosticsEnabled.get().toString(),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseSigningEnvironment.getValue("KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug { }
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":composeApp"))
    // Strangler foundations, declared explicitly (implementation deps don't leak transitively):
    //  - :core — MyApp.onCreate() calls setAndroidAppContext() (moved to me.manga.kira.core.android in Phase 2).
    //  - :platform — MainActivity/MyApp resolve platform facades (AppUpdateClient/ConsentFlowClient/
    //    SecureStorage/InAppReviewClient/notification/push) + the relocated core.cbz + core.storage.
    //    Phase 6: :app used to reach these transitively through :shared's api(:platform); with :shared
    //    deleted, the edge is now direct.
    //  - :data:local — the Android workers reference Room DAO/entity types directly.
    //  - :sources:legacy (Phase 3) — LibraryRefreshWorker references BaseMangaRepository.
    implementation(project(":core"))
    implementation(project(":platform"))
    implementation(project(":data:local"))
    // :data:download (strangler Phase 4) — DownloadCancelReceiver references the DownloadRepository
    // interface (moved here from :shared, package preserved).
    implementation(project(":data:download"))
    implementation(project(":sources:legacy"))
    // MangaSource decoupling (2026-07): LibraryRefreshWorker routes config-backed sources through
    // the SourceRegistry's generic client (details verb) — contracts carries the registry seam and
    // :domain the entities the client speaks.
    implementation(project(":sources:contracts"))
    implementation(project(":domain"))

    // Koin Android
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.workmanager)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)

    // Android core / Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.i18n)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.palette.ktx)

    // DataStore (Android-only bridge to multiplatform-settings)
    implementation(libs.androidx.datastore.preferences)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.work.gcm)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.firestore)

    // Google Play services
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)
    implementation(libs.play.review)
    implementation(libs.play.review.ktx)

    // Android-only image extras (also depended on by composeApp; declared here so app's own
    // composables resolve them too).
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.avif)
    implementation(libs.google.material)

    // Kermit + Crashlytics writer
    implementation(libs.kermit)
    implementation(libs.kermit.crashlytics)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.koin.test)
    // Names the externally-provided Ktor engine type for the Koin verify() graph check.
    testImplementation(libs.ktor.client.core)
}
