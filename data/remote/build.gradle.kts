import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :data:remote — the Ktor transport layer, extracted from :shared (strangler-fig Phase 2).
//
// A LEAF module: depends only on :core (incl. its Android app-context facade). Its consumers are
// :sources:legacy (scrapers construct ApiClient) and :composeApp (SharedModule builds the shared
// HttpClient/ApiClient) — the former :data edge was dropped 2026-07-04 audit (dead after
// SourceRegistry retirement P6 deleted the endpoint path). Package stays me.manga.kira.data.remote.*
// so no consumer import changes.
//
// Owns createHttpClient() (expect + 3 per-target actuals: OkHttp/Darwin/CIO), the iOS
// BoundedCacheStorage, ApiClient, and the remoteModule() Koin bindings. No @Serializable models live
// here (per-source DTOs stay under sources_repositry) — so, unlike :data:local, NO serialization
// compiler plugin is applied; only the kotlinx-serialization runtime (pulled via ktor) is used.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "me.manga.kira.data.remote"
        compileSdk = 37
        minSdk = 26
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            // Match :shared / :data (JVM_17): consumers on Desktop are 17, so this producer's
            // classfile target must be no greater than theirs.
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // No binaries.framework: linked into :composeApp's umbrella ComposeApp.framework as a klib.
    // Swift never calls ApiClient/HttpClient, so nothing here needs to appear in the Obj-C header.
    // iosX64 intentionally omitted (CMP 1.11 dropped Apple x86_64, KT-81596).
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    // createHttpClient() / isHttpLoggingEnabled are expect declarations with per-target actuals.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))

            // `api`: ApiClient's public methods return io.ktor.client.statement.HttpResponse, so
            // consumers of ApiClient (the :shared scrapers) must see the Ktor client types.
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
            // `api`: `DefaultJson` (a top-level public Json instance) is referenced by consumers.
            api(libs.kotlinx.serialization.json)

            // remoteModule() (HttpClient + ApiClient singletons) lives here.
            api(libs.koin.core)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        getByName("desktopMain").dependencies {
            implementation(libs.ktor.client.cio)
        }
    }
}
