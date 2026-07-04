import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :sources:contracts — the STABLE API of the generic-sources subsystem.
// Pure interfaces + the (de)serializable config model. The ONLY sources module the rest of the
// app (:data) is allowed to depend on. Holds NO execution and NO remote-update logic — those live
// in :sources:engine and :sources:config respectively, which both depend on this module and never
// on each other. Contract §4: points inward only (:core, :domain).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "me.manga.kira.sources.contracts"
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
            baseName = "sourcesContracts"
            isStatic = true
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // AppResult/AppError (return types on MangaSourceClient) + DispatcherProvider.
            api(project(":core"))
            // Manga/Chapter/MangaDetails/Page appear in MangaSourceClient signatures.
            api(project(":domain"))
            // Flow on the source port + coroutines used by ports.
            api(libs.kotlinx.coroutines.core)
            // SourceConfig model is @Serializable (parsed from the signed config document).
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
