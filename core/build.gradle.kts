import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core — pure-Kotlin foundation for the architecture rework.
// Contract §4: utilities, base classes, error types, dispatchers, base contracts.
// Depends on NOTHING above it in the layer graph (no :data, :presentation, :ui, :platform).
// External deps stay minimal: coroutines for DispatcherProvider, atomicfu for thread-safe
// primitives, kotlinx-datetime for time abstractions, Kermit for the Logger SPI.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "me.manga.kira.core"
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
            // Align with :shared/:composeApp Desktop target (JDK 17 — see :shared comment).
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iosX64 omitted to match :shared (CMP 1.11.0 dropped Apple x86_64 — KT-81596).
    // No binaries.framework block: this library is linked into :composeApp's umbrella
    // ComposeApp.framework as a klib dependency; nothing consumes a standalone core.framework.
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // Coroutines — used by DispatcherProvider abstraction (returns CoroutineDispatcher).
            // `api` because DispatcherProvider's return type leaks across module boundaries; any
            // downstream module receiving a DispatcherProvider must be able to see CoroutineDispatcher.
            api(libs.kotlinx.coroutines.core)

            // Kermit — Logger SPI re-exports Severity / LogWriter types from Kermit. `api` so
            // downstream modules can implement the Logger interface without an extra dep.
            api(libs.kermit)

            // kotlinx-datetime — used by error/result types that carry timestamps (e.g. when an
            // AppError was raised). `api` to avoid forcing every consumer to add datetime explicitly.
            api(libs.kotlinx.datetime)

            // atomicfu — thread-safe primitives for any foundation utilities (e.g. lazy holders,
            // simple counters in error reporting). Implementation, not api — internal use only.
            implementation(libs.kotlinx.atomicfu)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
