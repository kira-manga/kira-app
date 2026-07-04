import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
fun propOrFallback(name: String, fallback: String): String {
    val v = (project.findProperty(name) as String?)?.trim()
    return if (!v.isNullOrEmpty()) v else fallback
}

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
    }
}

android {
    namespace = "me.manga.kira"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.manga.kira"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val envKeystoreFile = env("KEYSTORE_FILE")
            val localKeystore = "yami-release.keystore"
            storeFile = file(envKeystoreFile ?: localKeystore)
            storePassword = env("KEYSTORE_PASSWORD") ?: (findProperty("KEYSTORE_PASSWORD") as String?)
            keyAlias = env("KEY_ALIAS") ?: (findProperty("KEY_ALIAS") as String?)
            keyPassword = env("KEY_PASSWORD") ?: (findProperty("KEY_PASSWORD") as String?)
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/5224354917\"")
            buildConfigField("String", "NATIVE_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/2247696110\"")
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")

            val rewarded = propOrFallback("ADMOB_REWARDED_ID", "ca-app-pub-3940256099942544/5224354917")
            val native = propOrFallback("ADMOB_NATIVE_ID", "ca-app-pub-3940256099942544/2247696110")
            val banner = propOrFallback("ADMOB_BANNER_ID", "ca-app-pub-3940256099942544/6300978111")

            buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"$rewarded\"")
            buildConfigField("String", "NATIVE_AD_UNIT_ID", "\"$native\"")
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$banner\"")

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

    // Google Play services / AdMob
    implementation(libs.play.services.ads)
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)
    implementation(libs.play.review)
    implementation(libs.play.review.ktx)
    implementation(libs.ump)

    // AdMob mediation
    implementation(libs.mediation.inmobi)
    implementation(libs.mediation.ironsource)
    implementation(libs.mediation.vungle)
    implementation(libs.mediation.facebook)

    // Android-only image extras (also depended on by composeApp; declared here so app's own composables resolve them too)
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
