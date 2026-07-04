import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :domain — pure-Kotlin domain layer for the architecture rework.
// Contract §4: entities + repository interfaces + use cases. ZERO framework deps.
// Allowed: kotlinx-coroutines (Flow returns), kotlinx-datetime (Instant/LocalDate fields), :core types.
// Forbidden: Compose, Android SDK, Room, Ktor, Koin, kotlinx-serialization, Coil.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "me.manga.kira.domain"
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
    // ComposeApp.framework as a klib dependency; nothing consumes a standalone domain.framework.
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // :core supplies AppResult, AppError, DispatcherProvider, Logger, FeatureFlagProvider.
            // `api` because AppResult appears in repository/use-case return types.
            api(project(":core"))

            // Flow types are part of repository contracts. `api` so downstream layers don't need
            // to declare coroutines explicitly to consume domain APIs.
            api(libs.kotlinx.coroutines.core)

            // Instant / LocalDate appear in entity timestamps. `api`-leaked from :core too.
            api(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
