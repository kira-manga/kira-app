import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :sources:legacy — the ~50 hand-written per-source scrapers extracted from :shared (strangler Phase 3).
//
// Depends DOWN only on :core / :domain / :data:local / :data:remote / :platform — an acyclic leaf both
// :shared (its download engine constructs concrete scrapers) and :data (Home/Search repos) depend down
// onto. Package stays me.manga.kira.sources_repositry.* → zero consumer import churn. It does NOT depend
// on :sources:contracts/:engine — the legacy scrapers predate the generic-sources API (the rework
// adapter LegacyKotlinSourceClient lives in :composeApp).
//
// Owns: the 5 `common/` base classes, BaseMangaRepository/EmptyMangaRepository, data/MangaSource (the
// 45-source registry), 43 concrete scrapers + 187 @Serializable per-source DTO models, the per-target
// ar/dilar/CryptoUtils actuals, and persistence-only Koin bindings. Scraper implementations remain
// compiled for saved-data compatibility but are not bound into the runtime graph.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // 187 @Serializable per-source response models need the serialization compiler plugin (like
    // :data:local for SourceState). NO compose plugin — MangaSource only uses the Color value class
    // (artifact, not @Composable); NO ksp/room/parcelize.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "me.manga.kira.sources.legacy"
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
            // Match :shared / :data (JVM_17): consumers on Desktop are 17.
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // No binaries.framework: linked into :composeApp's umbrella ComposeApp.framework as a klib.
    // iosX64 omitted (CMP 1.11 dropped Apple x86_64, KT-81596).
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // Scraper public surfaces expose State/SearchType/domain models/ApiClient/DAO types → api.
            api(project(":core"))
            api(project(":domain"))
            api(project(":data:local"))
            api(project(":data:remote"))
            // DataStoreHelper is a scraper ctor param (constructed here in legacySourcesModule) → impl.
            implementation(project(":platform"))

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime) // LocalDate/LocalDateTime appear on scraper/model surfaces
            implementation(libs.kotlinx.atomicfu)

            // Ktor client — scrapers use ApiClient's HttpResponse + raw io.ktor helpers directly.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)

            // HTML parsing.
            implementation(libs.ksoup)
            // Logging.
            implementation(libs.kermit)
            // Coil (API-only: PlatformContext / NetworkHeaders / ImageRequest.Builder in BaseManga's
            // per-source image-request builder). Engine/singleton stay in :composeApp/:platform.
            implementation(libs.coil.core)
            implementation(libs.coil.network.core)
            // androidx.compose.ui.graphics.Color — used once by MangaSource for source brand colors.
            // The value-class artifact only; NO compose compiler plugin needed.
            implementation(libs.compose.ui)

            // sourcePersistenceModule() binds the saved-data facades and an intentionally empty
            // Set<BaseMangaRepository>.
            api(libs.koin.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // Real-database tests (LibraryRepositoryCoverGuardTest) build an in-memory MangaDatabase —
            // Room + the bundled SQLite driver are `implementation` in :data:local, so the test source
            // set needs its own visibility. okio: the AppFileSystem fake exposes Path/FileSystem.
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.okio)
        }
    }
}
