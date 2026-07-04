import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :presentation — MVI ViewModels + Intent/State/Effect contracts.
// Contract §4 / §6: presentation owns the user-interaction layer's state machines but ZERO
// Compose UI types. Composable screens live in :ui (next phase). View models depend on :domain
// use cases via constructor injection; they never reach across to :data or :platform directly.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "me.manga.kira.presentation"
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

    // No binaries.framework block: this library is linked into :composeApp's umbrella
    // ComposeApp.framework as a klib dependency; nothing consumes a standalone presentation.framework.
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":domain"))

            // KMP ViewModel base class. `api` because every ViewModel here subclasses it and
            // downstream UI consumers see the type on screen surfaces.
            api(libs.androidx.lifecycle.viewmodel)

            // StateFlow / Channel for state + effect streams.
            api(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
