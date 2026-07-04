import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :sources:engine — EXECUTION of config-driven sources. Implements the contracts' MangaSourceClient
// generically: it interprets a SourceConfig (HTTP templates + named strategies) against an
// HttpExecutor and emits :domain models. It is framework-light by design — HTTP is abstracted behind
// the HttpExecutor PORT (no Ktor here), HTML parsing uses Ksoup, JSON uses kotlinx-serialization.
// Depends ONLY on :sources:contracts (and transitively :core/:domain). It never depends on
// :sources:config (the two meet at :contracts) and is invisible to :data. Contract §4.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "me.manga.kira.sources.engine"
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
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "sourcesEngine"
            isStatic = true
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":sources:contracts"))
            // JSON config parsing + JSON-API response extraction.
            implementation(libs.kotlinx.serialization.json)
            // HTML response extraction (CSS selectors) — KMP, same parser the legacy sources use.
            implementation(libs.ksoup)
            // Date parsing strategies (chapter dates → LocalDate).
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
