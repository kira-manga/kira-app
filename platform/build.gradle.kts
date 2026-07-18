import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :platform — platform-specific facades for the architecture rework.
// Contract §4: declares SPI interfaces consumed by upper layers (e.g. AppVersionProvider,
// IntentLauncher, ToastShower, AppFileSystem, CrashReporter, AnalyticsClient, ...) and provides
// per-target implementations.
//
// Forbidden in commonMain: Compose UI, ViewModel, domain repositories.
// Allowed in androidMain / iosMain / desktopMain: respective platform SDKs.
//
// Dep direction (contract §4 layer graph):
//   :core        ← :platform (uses Logger, AppResult, DispatcherProvider).
//   :domain      not consumed here. Platform facades are independent of business contracts.
//   :data        not consumed here.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// skiko ships its JVM API (`org.jetbrains.skia.*`) without the native binary; the host-classified
// `skiko-awt-runtime-*` artifact carries the .dylib/.so/.dll. The desktop test set needs it on its
// runtime classpath so SkiaWebpEncoderTest can actually encode (the main desktop classpath gets the
// native from :desktopApp's compose plugin, not here). Version must track the skiko `:platform`
// resolves transitively via coil-core (currently 0.9.22.2 — bump if a coil/compose upgrade moves it).
val skikoVersion = "0.9.22.2"
val skikoHostTarget: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val arm = arch == "aarch64" || arch == "arm64"
    when {
        os.contains("mac") || os.contains("darwin") -> if (arm) "macos-arm64" else "macos-x64"
        os.contains("win") -> "windows-x64"
        else -> if (arm) "linux-arm64" else "linux-x64"
    }
}

kotlin {
    android {
        namespace = "me.manga.kira.platform"
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
    // ComposeApp.framework as a klib dependency; nothing consumes a standalone platform.framework.
    //
    // libwebp cinterop (Option K): the native iOS WebP encoder (IosLibWebpEncoder) calls libwebp's
    // WebPEncodeRGBA via these bindings. The vendored static archive (libwebp + libsharpyuv, combined)
    // under platform/libs/libwebp/<slice>/ is bundled INTO the klib via -staticLibrary/-libraryPath, so
    // it rides along into :composeApp's ComposeApp.framework link with no wiring needed there. Headers
    // are slice-independent; only the static-lib path differs per slice.
    iosArm64 {
        compilations.getByName("main").cinterops.create("libwebp") {
            defFile(project.file("src/nativeInterop/cinterop/libwebp.def"))
            includeDirs(project.file("libs/libwebp/include"))
            extraOpts("-staticLibrary", "libwebp.a", "-libraryPath", project.file("libs/libwebp/ios-arm64").absolutePath)
        }
    }
    iosSimulatorArm64 {
        compilations.getByName("main").cinterops.create("libwebp") {
            defFile(project.file("src/nativeInterop/cinterop/libwebp.def"))
            includeDirs(project.file("libs/libwebp/include"))
            extraOpts("-staticLibrary", "libwebp.a", "-libraryPath", project.file("libs/libwebp/ios-arm64-simulator").absolutePath)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Phase 5.w.1 — intermediate `nonAndroidMain` source set shared by iOS + Desktop. Hosts
        // the Skia-backed `HighQualitySkiaImageDecoder` (one of the load-bearing image-quality
        // fixes preserved verbatim from legacy `:shared`). commonMain → nonAndroidMain →
        // { iosMain, desktopMain }; androidMain stays on the default hierarchy template path
        // (commonMain → androidMain) because its actual depends on `org.aomedia.avif.android`
        // and `android.graphics.Bitmap` — neither of which exist on nonAndroidMain.
        val nonAndroidMain = create("nonAndroidMain") {
            dependsOn(commonMain.get())
        }
        iosMain.get().dependsOn(nonAndroidMain)
        getByName("desktopMain").dependsOn(nonAndroidMain)

        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
            // Okio (multiplatform file I/O) — required by the AppFileSystem SPI (Phase 5.4).
            // Exposed as `api` so the interface's `Path` / `FileSystem` return types remain
            // resolvable to downstream callers without each consumer re-declaring okio.
            api(libs.okio)
            // multiplatform-settings — required by the SettingsFactory SPI (Phase 5.v.3).
            // Exposed as `api` because the interface returns `Settings` / `ObservableSettings`,
            // and downstream consumers need to call methods on those returned instances without
            // each declaring the dep again. The same artifact provides the per-target backends
            // (`SharedPreferencesSettings` on Android, `NSUserDefaultsSettings` on iOS,
            // `PreferencesSettings` on JVM) via Kotlin Multiplatform metadata resolution.
            api(libs.multiplatform.settings)
            // multiplatform-settings-coroutines — required by the DataStoreHelper SPI (PC-8).
            // Provides the `ObservableSettings.getBooleanFlow/getIntFlow/getStringFlow(key, default)`
            // extensions that back DataStoreHelper's reactive `Flow<T>` properties. Same artifact
            // family as `multiplatform-settings` above (version pinned by the shared ref); the
            // legacy `:shared` DataStoreHelper used exactly these extensions.
            implementation(libs.multiplatform.settings.coroutines)
            // kotlinx-serialization-json — required by the DataStoreHelper SPI (PC-8) for the
            // headers-per-API map (de)serialization (`parseHeadersMap`/`encodeHeadersMap`). The
            // legacy `:shared` facade stored the nested header map as a kotlinx JsonObject string;
            // the byte-format must be preserved verbatim so persisted stores round-trip.
            implementation(libs.kotlinx.serialization.json)
            // Coil 3 core — required by the ImageDecoderRegistry SPI (Phase 5.w.1). Exposed as
            // `api` because the interface's `registerAll()` returns
            // `List<coil3.decode.Decoder.Factory>`; downstream consumers (the ImageLoader
            // builder in :composeApp / :ui) must be able to call into Coil's types without each
            // re-declaring this dep. We pull only `coil-core` here — the singleton ImageLoader,
            // Compose binding, and network engines stay in :composeApp.
            api(libs.coil.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // SkiaWebpEncoderTest exercises real skiko encoding on the Desktop/JVM target; pull the
        // host's native skiko runtime so `org.jetbrains.skia` calls don't UnsatisfiedLinkError.
        getByName("desktopTest").dependencies {
            runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-$skikoHostTarget:$skikoVersion")
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            // AppCompat — required by AndroidLocaleSwitcher (Phase 5.6) for
            // AppCompatDelegate.setApplicationLocales(...). Adding explicitly here rather than
            // relying on the transitive pull that legacy :shared/androidMain happened to get from
            // other artifacts (Material / activity-compose, depending on the resolved graph).
            implementation(libs.androidx.appcompat)
            // androidx.security:security-crypto — required by AndroidSecureStorage (Phase 5.v
            // SecureStorage) for EncryptedSharedPreferences + the AES-256-GCM master key spec.
            // Declared explicitly on :platform/androidMain (same rationale as appcompat above:
            // the clean-layer module owns its dependency graph and does not inherit transitive
            // classpath from :shared).
            implementation(libs.androidx.security.crypto)
            // Firebase Messaging + the kotlinx Task<T>.await() bridge — required by
            // AndroidPushTokenProvider (Phase 5.y.2 PushTokenProvider). The BoM aligns versions
            // for any future Firebase artifacts on this module.
            // (iOS / Desktop are pure no-ops for push and need no SDK.)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.messaging)
            // Firebase Analytics — required by AndroidAnalyticsClient (Phase 5.z.5
            // AnalyticsClient). Provides `FirebaseAnalytics.getInstance(context)` +
            // `logEvent(name, bundle)` / `setUserProperty` / `setUserId`. Version comes from the
            // Firebase BoM above (declared once for Push at Phase 5.y.2, reused here for
            // Analytics — same BoM, no duplicate platform import). iOS / Desktop are no-ops
            // (Firebase iOS deferred to Phase 12; no Firebase JVM SDK).
            implementation(libs.firebase.analytics)
            // Firebase Crashlytics — required by AndroidCrashReporter (Phase 5.z.6
            // CrashReporter). Provides `FirebaseCrashlytics.getInstance()` (zero-arg, no
            // Context needed) + `recordException` / `log` / `setUserId` / `setCustomKey`.
            // Version comes from the Firebase BoM above. iOS / Desktop are no-ops (iOS
            // Crashlytics deferred to Phase 12; no Firebase JVM SDK).
            implementation(libs.firebase.crashlytics)
            // Firebase Firestore — required by AndroidRemoteDocStore (Phase 5.z.7
            // RemoteDocStore). Provides `FirebaseFirestore.getInstance()` (zero-arg, no
            // Context needed) + `DocumentReference.get/set/delete().await()` /
            // `addSnapshotListener` / `Query.whereEqualTo` etc. Version comes from the
            // Firebase BoM (same BoM that pins Messaging / Analytics / Crashlytics — fourth
            // reuse). iOS / Desktop are no-ops (iOS Firestore deferred to Phase 12; no
            // first-party JVM SDK, REST integration deferred to Phase 13).
            implementation(libs.firebase.firestore)
            // Firebase In-App Messaging (display) — the modal/banner/image renderer that shows
            // console-authored FIAM campaigns. Auto-initialises from google-services.json (its
            // Firebase-components registrar merges into the app manifest) and displays on every screen
            // with no app code — the app intentionally does not suppress in-app messages. Version
            // comes from the Firebase BoM above. iOS uses the SPM FirebaseInAppMessaging-Beta product;
            // Desktop has no SDK.
            implementation(libs.firebase.inappmessaging.display)
            implementation(libs.kotlinx.coroutines.play.services)
            // WorkManager — required by AndroidBackgroundJobScheduler (Phase 5.y.4
            // BackgroundJobScheduler) for OneTimeWorkRequest / PeriodicWorkRequest /
            // WorkManager.getInstance / getWorkInfoByIdFlow. Only `work-runtime-ktx` is needed;
            // `work-gcm` is for pre-API-23 fallback and `:platform`'s minSdk is 26.
            implementation(libs.androidx.work.runtime.ktx)
            // AVIF decoder native library — required by AvifDecoderCoil (Phase 5.w.1
            // ImageDecoderRegistry). Many Cloudflare-protected manga CDNs serve chapter pages
            // as AVIF; without this lib registered on the singleton ImageLoader, decode falls
            // back to the platform default at degraded quality (Android 31+) or fails entirely
            // (older releases). This is one of the load-bearing image-quality fixes ported
            // verbatim from legacy `:shared/androidMain`.
            implementation(libs.avif)
            // androidx.palette:palette-ktx — required by AndroidDominantColorExtractor
            // (Phase 5.w.3 DominantColorExtractor). Used for the `Palette.from(bitmap)
            // .generate().getDominantColor(BLACK)` sampling that backs cover-tinted UI accents.
            // iOS / Desktop use platform-native pixel-sampling (CoreGraphics 1×1 context /
            // BufferedImage SCALE_AREA_AVERAGING) and need no extra dep.
            implementation(libs.androidx.palette.ktx)
            // Play Core review — required by AndroidInAppReviewClient (Phase 5.z.1
            // InAppReviewClient). Provides `ReviewManagerFactory.create(context)` and the
            // `requestReviewFlow()` / `launchReviewFlow()` Tasks that the actual awaits via
            // `kotlinx-coroutines-play-services` (already pulled in for Push above).
            // iOS uses SKStoreReviewController; Desktop has no review surface — both are no-ops.
            implementation(libs.play.review)
            // Play Core app-update — required by AndroidAppUpdateClient (Phase 5.z.2
            // AppUpdateClient). Provides `AppUpdateManagerFactory.create(context)` +
            // `startUpdateFlowForResult(info, activity, options, requestCode)` for the
            // flexible/immediate in-app update flows. iOS uses the App Store outside the app;
            // Desktop has no Play-Store-style update mechanism — both are no-ops.
            implementation(libs.play.app.update)
        }

        iosMain.dependencies {
            // ktor — required by IosConnectivityObserver (Phase 5.x) for the HEAD-probe
            // fallback used in lieu of an SCNetworkReachability cinterop binding. ktor-client-core
            // gives us `HttpClient` / `HttpTimeout` / `head`; ktor-client-darwin provides the
            // `Darwin` engine that wires through to NSURLSession.
            // (Declared on iosMain only — Android uses ConnectivityManager and Desktop uses
            // HttpURLConnection, so they don't need ktor in :platform.)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.darwin)
        }
    }
}
