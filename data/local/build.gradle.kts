import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :data:local — the Room persistence foundation, extracted from :shared (strangler-fig Phase 1).
//
// A LEAF module: depends only on :core. Both :shared (legacy scrapers/download/DI) and :data (rework
// repository impls) depend DOWN onto it — which is what breaks the would-be :shared <-> :data cycle a
// direct Room-in-:data move would create (:shared's own scrapers/download/PlatformModule use Room, and
// :data still depends on :shared for scrapers/ApiClient, so Room could not live in either without a
// cycle). Package stays `me.manga.kira.data.local.*` so no consumer import changes.
//
// Owns MangaDatabase v11 + 9 DAOs / 7 entities / 5 converters / 10 migrations, the 3 per-target
// DatabaseBuilder actuals, and the `databaseModule()` Koin bindings. (The 4 co-extracted model types
// under presentation.features.* keep their package names for now — renamed in a later strangler phase.)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // The co-extracted `SourceState` is @Serializable, so the serialization compiler plugin is needed
    // here (unlike :data, which deliberately omits it).
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    android {
        namespace = "me.manga.kira.data.local"
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
            // Match :shared / :data (JVM_17). Consumers on Desktop are 17, so this producer's
            // classfile target must be no greater than theirs.
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // No binaries.framework block: linked into :composeApp's umbrella ComposeApp.framework as a klib.
    // Swift never calls Room, so nothing here needs to appear in the generated Obj-C header (no export).
    // iosX64 intentionally omitted to match :shared/:composeApp (CMP 1.11 dropped Apple x86_64, KT-81596).
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    // expect/actual classes (MangaDatabase + the generated Room MangaDatabaseConstructor) are a Beta
    // Kotlin feature (KT-61573) used intentionally. Opt in to silence the per-declaration warning.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))

            implementation(libs.kotlinx.coroutines.core)
            // `api`: entity public surfaces expose kotlinx-datetime types (e.g. SavedChapterEntity.date);
            // consumers (:data mappers, :shared) reference entity.date directly.
            api(libs.kotlinx.datetime)
            // Runtime for the @Serializable SourceState + the StringListConverter JSON codec.
            implementation(libs.kotlinx.serialization.json)

            // Room (KMP) + bundled SQLite driver.
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)

            // `databaseModule()` lives here (MangaDatabase + 9 DAO singletons). `api` so the hosts'
            // module lists can reference it.
            api(libs.koin.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.androidx.room.ktx)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Room compiler — one KSP processor per supported target. A single common ksp(...) will NOT
    // generate the per-target `actual` for MangaDatabaseConstructor (kspIosX64 intentionally absent).
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}
