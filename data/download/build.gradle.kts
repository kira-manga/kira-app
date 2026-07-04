import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :data:download — the legacy chapter-download engine, extracted from :shared (strangler-fig Phase 4).
//
// Holds the DownloadRepository interface + DownloadManifest/DownloadState models (commonMain) and the
// three per-target implementations behind it: Android WorkManager (DownloadRepositoryImpl +
// DownloadWorkerV2 + ChapterDownloadService), iOS background URLSession
// (BackgroundUrlSessionDownloadRepository) and the shared non-Android coroutine queue
// (CoroutineDownloadRepositoryImpl, in nonAndroidMain). The per-target Koin wiring is downloadModule()
// (expect/actual), appended to allReworkModules() in :composeApp so all three hosts load it.
//
// Layering: a feature-execution module ABOVE the data/source foundations. Depends DOWN onto
// :data:local (DAOs/entities), :sources:legacy (scrapers + SourcesRepository's Set), :platform
// (FileService/CBZ/notifier/background/filesystem; brings :core via its api edge). The dep on :shared
// is the documented strangler bridge for the two legacy repos the engine still calls
// (LibraryRepository + SourcesRepository); it is acyclic because :shared holds ZERO download
// references after the binding move, and :shared does not depend on :data. It drops when those two
// repos relocate (Phase 5). :data depends UP onto this module for the DownloadRepository interface it
// strangles — the same shape as the pre-existing :data -> :shared download bridge, not a regression.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // kotlinx.serialization COMPILER plugin: DownloadManifest/ManifestPage are @Serializable and use
    // generated DownloadManifest.serializer() codegen for the persisted manifest.json. Safe for
    // :composeApp's resolution of this module — :sources:legacy (also @Serializable, also consumed by
    // :composeApp as implementation) is the working precedent; the P0-SRCSEED variant hazard was
    // specific to :data/:domain.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "me.manga.kira.data.download"
        compileSdk = 37
        minSdk = 26
        // The Android download path builds notifications with an Android R class (R.string.* for the
        // channel/notification text) — the new plugin disables Android resource processing by default,
        // so enable it to generate me.manga.kira.data.download.R.
        androidResources.enable = true
        // Opt in the Android host (unit) test source set (androidHostTest under the new plugin).
        withHostTestBuilder {}
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            // Match :shared/:composeApp Desktop (JVM_17); this module is consumed by :composeApp.
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // No binaries.framework block: like :data, this library is linked into :composeApp's umbrella
    // ComposeApp.framework as a klib dependency; nothing consumes a standalone framework.
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Intermediate nonAndroidMain source set shared by iOS + Desktop (mirrors :shared): hosts the
        // coroutine-queue impl + resolver/finalizer that have no WorkManager/Bitmap dependency.
        val nonAndroidMain = create("nonAndroidMain") {
            dependsOn(commonMain.get())
        }
        iosMain.get().dependsOn(nonAndroidMain)
        getByName("desktopMain").dependsOn(nonAndroidMain)

        commonMain.dependencies {
            // :platform brings FileService/CBZ/notifier/background/filesystem facades and, via its
            // api(:core) edge, the :core State/dispatchers the engine uses.
            implementation(project(":platform"))
            // DAO/entity types the engine reads/writes.
            implementation(project(":data:local"))
            // Scrapers (BaseMangaRepository + the concrete repos the engine news up) + MangaSource +
            // the legacy LibraryRepository/SourcesRepository the engine calls (relocated here in
            // Phase 5). core.cbz.OptimizedCbzManager now resolves from :platform. The transitional
            // :data:download -> :shared edge is therefore GONE — the engine no longer touches :shared.
            implementation(project(":sources:legacy"))

            implementation(libs.kotlinx.coroutines.core)
            // Runtime JSON for the download manifest (no @Serializable — see plugins note).
            implementation(libs.kotlinx.serialization.json)
            // org.koin.core.context lookup from the WorkManager worker.
            implementation(libs.koin.core)
            // Raw Ktor client for image fetch (the HttpClient instance is Koin-injected).
            implementation(libs.ktor.client.core)
            implementation(libs.kermit)
            implementation(libs.okio)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }

        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            // androidContext() in downloadModule().android + worker koin access.
            implementation(libs.koin.android)
            // NotificationCompat (androidx.core.app) + WorkManager (CoroutineWorker/ForegroundInfo).
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.work.runtime.ktx)
        }

        val androidHostTest = getByName("androidHostTest") {
            dependencies {
                implementation(libs.junit)
            }
        }
    }
}
