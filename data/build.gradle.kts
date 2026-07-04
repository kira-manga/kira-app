import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :data — concrete repository / data-source implementations for the architecture rework.
// Contract §4: implements the interfaces declared in :domain. May depend on Room/Ktor/serialization.
// Forbidden: Compose, presentation/UI types, Android-only SDK APIs (those belong in :platform).
//
// Room moved to :data:local (strangler-fig Phase 1); :data depends on that leaf for the DAO/entity
// types its repository impls use. The remaining transitional dep on :shared is only for the legacy
// scrapers (BaseMangaRepository) + ApiClient the Home/Search repos still call — it goes away when
// those relocate in later phases.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // P0-SRCSEED RE-EVALUATION (strangler Phase 5): the serialization compiler plugin is now
    // applied so the relocated complaint/whatsnew @Serializable DTOs codegen here. The historical
    // "plugin breaks :composeApp resolution of :data" hazard no longer reproduces — :data:download
    // and :sources:legacy both carry the plugin and are consumed by :composeApp cleanly. Verified
    // by the full compile + Koin-graph gate.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "me.manga.kira.data"
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
            // Match :shared (JVM_17) — :data depends on :shared transitionally; the consumer's
            // classfile target must be no less than the producer's on Desktop.
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // No binaries.framework block: this library is linked into :composeApp's umbrella
    // ComposeApp.framework as a klib dependency; nothing consumes a standalone data.framework.
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":domain"))

            // :platform actuals consumed directly by :data impls (e.g. LocaleSwitcher in
            // LanguageRepositoryImpl). Declared explicitly: every :platform type a :data actual
            // references must be on :data's own compile classpath, not relied on transitively.
            implementation(project(":platform"))

            // Persistence + scraper foundations (strangler Phases 1-3): DAO/entity types
            // (:data:local) and BaseMangaRepository/ManhastroDadosStore (:sources:legacy) resolve
            // from these leaves, not :shared. (:data:remote was dropped 2026-07-04 audit — the
            // edge went dead when SourceRegistry retirement P6 deleted the endpoint path; :data
            // has zero ApiClient/data.remote references and declares its own ktor-client-core.)
            implementation(project(":data:local"))
            // :data:download (strangler Phase 4) — the DownloadsRepositoryImpl /
            // DownloadsActionRepositoryImpl strangle the legacy DownloadRepository interface, and
            // MangaDetails/Downloads mappers use HandelDataClasses; both kept their original
            // packages (me.manga.kira.presentation.features.download.* / core.util.data_classes) on
            // the move, so this edge replaces the part of the :shared dep that served them.
            implementation(project(":data:download"))
            implementation(project(":sources:legacy"))

            // NOTE: the transitional :data -> :shared edge is GONE (strangler-fig Phase 5). Every
            // legacy type :data used to reach through :shared has relocated: SourcesRepository/
            // LibraryRepository -> :sources:legacy, the DownloadRepository interface + HandelDataClasses
            // -> :data:download, complaint + settings/statistics/whatsnew -> :data itself, Admin ->
            // :core, SharedPrefsHelper/FileService/UserIdProvider -> :platform,
            // runCatchingCancellable -> :core. :data no longer depends on :shared.

            // Generic-sources seam: :data may depend ONLY on :sources:contracts (interfaces + model).
            // Used to route piloted sources (Stage-1: Azora) through the registry while everything else
            // stays on the legacy SourcesRepository path.
            implementation(project(":sources:contracts"))

            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)

            // kotlinx-serialization runtime for the @Serializable DTOs codegen'd in this module
            // (complaint/whatsnew). (Its original P0-SRCSEED consumer — the remote source-list
            // parser — was deleted in SourceRegistry retirement Phase 6.)
            implementation(libs.kotlinx.serialization.json)

            // multiplatform-settings — backing store for `ReadingModeRepositoryImpl`. Bound as a
            // `single<ObservableSettings>` by the legacy `PlatformModule.*` (strangler-fig: rework
            // re-uses the legacy DI cell so disk state stays in sync across both readers).
            // `api` (not `implementation`) because `ReadingModeRepositoryImpl`'s constructor
            // takes `ObservableSettings`, and the `:composeApp` Koin module instantiates that
            // impl — Kotlin resolves the parameter type at the call site, so the consumer needs
            // the type on its classpath.
            api(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)

            // Ktor client — the relocated ComplaintFirestoreRestDataSource (iOS/Desktop complaint
            // impl, strangler Phase 5) talks to the Firestore REST API via the Koin-injected
            // HttpClient. Runtime engine is provided per-target by the host, not here.
            implementation(libs.ktor.client.core)
        }

        androidMain.dependencies {
            // Firebase Firestore + Task<T>.await() bridge — the relocated ComplaintFirestoreDataSource
            // (Android complaint impl, strangler Phase 5) uses the Firebase Android SDK.
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.firestore)
            implementation(libs.kotlinx.coroutines.play.services)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            // MapSettings (in-memory ObservableSettings) — backs the real legacy `SourcesRepository`
            // the Home/Search `:data` impls strangle, so its tests don't need a real DataStore.
            implementation(libs.multiplatform.settings.test)
        }
    }
}
