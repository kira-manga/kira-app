import com.android.aapt.Resources
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    // kotlin.android intentionally NOT applied: AGP 9 provides built-in Kotlin (the kotlin{} extension
    // + Kotlin compilation) for com.android.application, so the explicit plugin is redundant and
    // conflicts with built-in Kotlin. Removing it let us drop android.builtInKotlin=false.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

fun env(vararg names: String): String? =
    names.firstNotNullOfOrNull { name ->
        System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
    }

val releaseVersionProperties = Properties().apply {
    rootProject.file("release/version.properties").inputStream().use(::load)
}
val releaseVersionName =
    env("KIRA_VERSION_NAME", "MOBILE_RELEASE_VERSION_NAME")
        ?: releaseVersionProperties.getProperty("VERSION_NAME")
val releaseVersionCode =
    (env("KIRA_BUILD_NUMBER", "MOBILE_RELEASE_BUILD_NUMBER")
        ?: env("GITHUB_RUN_NUMBER")
        ?: releaseVersionProperties.getProperty("VERSION_CODE"))
        .toIntOrNull()
        ?.takeIf { it > 0 }
        ?: error("Kira Android version code must be a positive integer")
val crashDiagnosticsEnabled =
    providers.gradleProperty("kira.enableCrashDiagnostics")
        .map { value -> value.equals("true", ignoreCase = true) }
        .orElse(false)
val sourceConfigBaseUrl =
    providers.environmentVariable("KIRA_SOURCE_CONFIG_BASE_URL")
        .orElse(providers.gradleProperty("kira.sourceConfigBaseUrl"))
val sourceConfigPinnedKeys =
    providers.environmentVariable("KIRA_SOURCE_CONFIG_PINNED_KEYS")
        .orElse(providers.gradleProperty("kira.sourceConfigPinnedKeys"))

// Production signing is intentionally environment-only. A developer without these four values
// still gets an unsigned release artifact for R8/package validation; there is no local-keystore or
// Gradle-property fallback that could accidentally sign a production bundle with the wrong key.
val releaseSigningEnvironment =
    mapOf(
        "KEYSTORE_FILE" to
            env(
                "KEYSTORE_FILE",
                "KIRA_ANDROID_KEYSTORE_PATH",
                "MOBILE_RELEASE_ANDROID_KEYSTORE_PATH",
            ),
        "KEYSTORE_PASSWORD" to
            env("KEYSTORE_PASSWORD", "MOBILE_RELEASE_ANDROID_KEYSTORE_PASSWORD"),
        "KEY_ALIAS" to env("KEY_ALIAS", "MOBILE_RELEASE_ANDROID_KEY_ALIAS"),
        "KEY_PASSWORD" to env("KEY_PASSWORD", "MOBILE_RELEASE_ANDROID_KEY_PASSWORD"),
    )
val hasAnyReleaseSigningValue = releaseSigningEnvironment.values.any { it != null }
val hasAllReleaseSigningValues = releaseSigningEnvironment.values.all { it != null }
val releaseKeystore = releaseSigningEnvironment.getValue("KEYSTORE_FILE")?.let(::file)
val releaseSigningReady = hasAllReleaseSigningValues && releaseKeystore?.isFile == true
val releaseSigningRequired =
    env("MOBILE_RELEASE_REQUIRE_SIGNING")?.equals("true", ignoreCase = true) == true

// Release guard (audit: firebase-placeholder-ships-inert-in-release). Fail any release-variant
// build that would package the committed PLACEHOLDER google-services.json — which leaves
// Crashlytics / Analytics / FCM / Firestore silently inert in the shipped app with no signal.
// For build-PATH validation only (CI release-verify, local R8 smoke), bypass with
// -PallowPlaceholderGoogleServices=true (NEVER ship such an artifact).
gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { t ->
        t.project.path == ":app" && t.name.contains("Release") &&
            (
                t.name.startsWith("assemble") || t.name.startsWith("bundle") ||
                    t.name.startsWith("package") || t.name == "processReleaseGoogleServices"
            )
    }
    if (buildingRelease) {
        val allowPlaceholder =
            (project.findProperty("allowPlaceholderGoogleServices") as String?) == "true"
        val gs = file("google-services.json")
        // Catch both the committed placeholder marker and a partially-doctored file that kept the
        // dummy project_number (000000000000).
        val gsText = if (gs.exists()) gs.readText() else ""
        val isPlaceholder = gsText.contains("yami-local-placeholder") || gsText.contains("000000000000")
        if (isPlaceholder && !allowPlaceholder) {
            throw GradleException(
                "Release build is using the PLACEHOLDER app/google-services.json " +
                    "(project_id 'yami-local-placeholder'): Crashlytics, Analytics, FCM and " +
                    "Firestore would be INERT in the shipped app. Provide the real " +
                    "google-services.json before releasing, or pass " +
                    "-PallowPlaceholderGoogleServices=true to build the release artifact for " +
                    "path-validation only.",
            )
        }
        if (hasAnyReleaseSigningValue && !hasAllReleaseSigningValues) {
            val missing = releaseSigningEnvironment.filterValues { it == null }.keys.joinToString()
            throw GradleException("Incomplete release signing environment; missing: $missing")
        }
        if (hasAllReleaseSigningValues && releaseKeystore?.isFile != true) {
            throw GradleException("KEYSTORE_FILE does not point to a readable keystore file")
        }
        if (releaseSigningRequired && !releaseSigningReady) {
            throw GradleException(
                "MOBILE_RELEASE_REQUIRE_SIGNING=true, but Android release signing is not ready",
            )
        }
        val allowUnconfiguredSourceRemote =
            providers.gradleProperty("allowUnconfiguredSourceRemote").orNull == "true"
        if (!allowUnconfiguredSourceRemote &&
            (sourceConfigBaseUrl.orNull.isNullOrBlank() || sourceConfigPinnedKeys.orNull.isNullOrBlank())
        ) {
            throw GradleException(
                "Release source delivery is not configured. Set KIRA_SOURCE_CONFIG_BASE_URL and " +
                    "KIRA_SOURCE_CONFIG_PINNED_KEYS (key-id=Base64-X.509[,key-id=...]). " +
                    "Use -PallowUnconfiguredSourceRemote=true only for non-shipping build-path validation.",
            )
        }
    }
}

android {
    namespace = "me.manga.kira"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.manga.kira"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField(
            "boolean",
            "CRASH_DIAGNOSTICS_ENABLED",
            crashDiagnosticsEnabled.get().toString(),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseSigningEnvironment.getValue("KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":composeApp"))
    // Strangler foundations, declared explicitly (implementation deps don't leak transitively):
    //  - :core — MyApp.onCreate() calls setAndroidAppContext() (moved to me.manga.kira.core.android in Phase 2).
    //  - :platform — MainActivity/MyApp resolve platform facades (AppUpdateClient/ConsentFlowClient/
    //    SecureStorage/InAppReviewClient/notification/push) + the relocated core.cbz + core.storage.
    //    Phase 6: :app used to reach these transitively through :shared's api(:platform); with :shared
    //    deleted, the edge is now direct.
    //  - :data:local — the Android workers reference Room DAO/entity types directly.
    //  - :sources:legacy (Phase 3) — LibraryRefreshWorker references BaseMangaRepository.
    implementation(project(":core"))
    implementation(project(":platform"))
    implementation(project(":data:local"))
    // :data:download (strangler Phase 4) — DownloadCancelReceiver references the DownloadRepository
    // interface (moved here from :shared, package preserved).
    implementation(project(":data:download"))
    implementation(project(":sources:legacy"))
    // MangaSource decoupling (2026-07): LibraryRefreshWorker routes config-backed sources through
    // the SourceRegistry's generic client (details verb) — contracts carries the registry seam and
    // :domain the entities the client speaks.
    implementation(project(":sources:contracts"))
    implementation(project(":domain"))

    // Koin Android
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.workmanager)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)

    // Android core / Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.i18n)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.palette.ktx)

    // DataStore (Android-only bridge to multiplatform-settings)
    implementation(libs.androidx.datastore.preferences)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.work.gcm)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.firestore)

    // Google Play services
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)
    implementation(libs.play.review)
    implementation(libs.play.review.ktx)

    // Android-only image extras (also depended on by composeApp; declared here so app's own
    // composables resolve them too).
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Public transport API for bounded, worker-owned covers, independent of the UI image loader.
    implementation(libs.okhttp)
    implementation(libs.avif)
    implementation(libs.google.material)

    // Kermit + Crashlytics writer
    implementation(libs.kermit)
    implementation(libs.kermit.crashlytics)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.koin.test)
    testImplementation(libs.robolectric.runner)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.concurrent.futures)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.multiplatform.settings.test)
    // Names the externally-provided Ktor engine type for the Koin verify() graph check.
    testImplementation(libs.ktor.client.core)
}

// App-specific offline Robolectric/SQLite inputs; not part of production runtime.
// Keep this inline: lint's FIR environment does not register dynamically applied Kotlin scripts.
// Like data:download's host inputs, these archives are data only, never Java classpath entries.
val notificationHostCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val notificationHostNativeInput by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}
val notificationHostSdkInput by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    add(notificationHostNativeInput.name, notificationHostCatalog.findLibrary("sqlite-host-jni-input").get())
    add(notificationHostSdkInput.name, notificationHostCatalog.findLibrary("robolectric-sdk35").get())
}

val notificationHostRuntime = layout.buildDirectory.dir("notification-android-host-runtime")
val prepareNotificationAndroidHostRuntime by tasks.registering(Sync::class) {
    from({ zipTree(notificationHostNativeInput.singleFile) }) {
        include("natives/linux_x64/libsqliteJni.so")
        eachFile { path = "native/libsqliteJni.so" }
        includeEmptyDirs = false
    }
    from(notificationHostSdkInput) { into("sdk") }
    into(notificationHostRuntime)
}

tasks
    .withType<Test>()
    .matching { it.name == "testDebugUnitTest" }
    .configureEach {
        dependsOn(prepareNotificationAndroidHostRuntime)
        maxParallelForks = 1
        forkEvery = 0
        maxHeapSize = "1g"
        jvmArgs("-XX:ActiveProcessorCount=2")
        // Set before the fork: Gradle offline mode does not control Robolectric's own resolver.
        systemProperty("robolectric.offline", "true")
        systemProperty(
            "robolectric.dependency.dir",
            notificationHostRuntime.get().dir("sdk").asFile.absolutePath,
        )
        systemProperty(
            "kira.notification.sqlite.native",
            notificationHostRuntime.get().file("native/libsqliteJni.so").asFile.absolutePath,
        )
        doFirst {
            val forbidden =
                classpath.files.filter {
                    it.name.startsWith("sqlite-bundled-jvm-") || it.name.startsWith("room-runtime-jvm-")
                }
            check(forbidden.isEmpty()) {
                "Desktop SQLite/Room classes must not enter the Android host classpath: $forbidden"
            }
            val sdk =
                notificationHostRuntime
                    .get()
                    .file("sdk/android-all-instrumented-15-robolectric-13954326-i7.jar")
                    .asFile
            check(sdk.isFile) { "Pinned offline Robolectric SDK35 input was not staged" }
            check(sdk.notificationHostSha256() == "06c4c8602d6db7486266a982bc55834ed6dfebd62a0b88daca204139ecf142cb") {
                "Offline Robolectric SDK35 input does not match the pinned host runtime"
            }
        }
    }

fun File.notificationHostSha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// AGP-backed declarations belong to this plugin-owning script; applied Kotlin scripts
// do not inherit its Android plugin compilation classpath. Keep the inspector typed.
// Uses aapt2-proto already supplied by AGP: no downloaded inspector or release toolchain change.
val storePackage = "me.manga.kira"
val androidNamespace = "http://schemas.android.com/apk/res/android"
val debuggableResourceId = 0x0101000f
val manifestEntry = "base/manifest/AndroidManifest.xml"

fun requireStoreIdentity(condition: Boolean, reason: String) {
    if (!condition) throw GradleException("Store AAB identity refused: $reason")
}

fun verifyStoreBundle(bundle: File) {
    val node = ZipFile(bundle).use { zip ->
        val entries = zip.entries().asSequence().filter { it.name == manifestEntry }.take(2).toList()
        requireStoreIdentity(entries.size == 1, "expected one base binary manifest")
        val bytes = zip.getInputStream(entries.single()).use { it.readNBytes(1_048_577) }
        requireStoreIdentity(bytes.size <= 1_048_576, "manifest exceeds inspection bound")
        Resources.XmlNode.parseFrom(bytes)
    }
    requireStoreIdentity(node.hasElement() && node.element.name == "manifest" && node.element.namespaceUri.isEmpty(), "invalid manifest root")
    val packages = node.element.attributeList.filter { it.name == "package" && it.namespaceUri.isEmpty() }
    requireStoreIdentity(packages.size == 1 && packages.single().value == storePackage, "non-canonical package")
    val packageAttribute = packages.single()
    requireStoreIdentity(packageAttribute.resourceId == 0, "invalid package resource")
    if (packageAttribute.hasCompiledItem()) {
        val item = packageAttribute.compiledItem
        requireStoreIdentity(item.hasStr() && item.str.value == storePackage, "conflicting compiled package")
    }
    val applications = node.element.childList.filter { it.hasElement() && it.element.name == "application" && it.element.namespaceUri.isEmpty() }
    requireStoreIdentity(applications.size == 1, "expected one application")
    val debugAttributes = applications.single().element.attributeList.filter {
        it.resourceId == debuggableResourceId || (it.name == "debuggable" && it.namespaceUri == androidNamespace)
    }
    requireStoreIdentity(debugAttributes.size <= 1, "ambiguous debuggable attribute")
    debugAttributes.singleOrNull()?.let { attribute ->
        requireStoreIdentity(attribute.name == "debuggable" && attribute.namespaceUri == androidNamespace, "invalid debuggable identity")
        requireStoreIdentity(attribute.resourceId == 0 || attribute.resourceId == debuggableResourceId, "invalid debuggable resource")
        requireStoreIdentity(attribute.value.isEmpty() || attribute.value == "false", "debuggable app")
        if (attribute.hasCompiledItem()) {
            val item = attribute.compiledItem
            requireStoreIdentity(item.hasPrim() && item.prim.hasBooleanValue() && !item.prim.booleanValue, "debuggable or unresolved app")
        } else {
            requireStoreIdentity(attribute.value == "false", "unresolved debuggable app")
        }
    }
    // Absence is Android's non-debuggable default. A compiled true cannot be hidden by raw "false".
}

extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
    val expected = if (variant.buildType == "debug") "$storePackage.debug" else storePackage
    requireStoreIdentity(variant.applicationId.get() == expected, "unexpected effective variant application ID")
    if (variant.buildType == "release") {
        val suppliedBundle = providers.gradleProperty("kira.releaseBundleToVerify")
        val bundle = if (suppliedBundle.isPresent) {
            layout.file(suppliedBundle.map { rootProject.file(it) })
        } else {
            variant.artifacts.get(SingleArtifact.BUNDLE)
        }
        tasks.register("verifyReleaseBundleIdentity") {
            group = "verification"
            description = "Reject development identities/debuggable apps in the exact AAB selected for upload"
            inputs.file(bundle)
            doLast {
                verifyStoreBundle(bundle.get().asFile)
                logger.lifecycle("Store AAB canonical package and non-debuggable binary manifest verified")
            }
        }
    }
}

tasks.register("testReleaseBundleIdentity") {
    group = "verification"
    description = "Exercise the actual AAB inspector with binary protobuf identity fixtures (not signed artifacts)"
    doLast {
        fun manifest(packageName: String, debug: Resources.XmlAttribute? = null): Resources.XmlNode {
            val application = Resources.XmlElement.newBuilder().setName("application")
            if (debug != null) application.addAttribute(debug)
            return Resources.XmlNode.newBuilder().setElement(
                Resources.XmlElement.newBuilder().setName("manifest")
                    .addAttribute(Resources.XmlAttribute.newBuilder().setName("package").setValue(packageName))
                    .addChild(Resources.XmlNode.newBuilder().setElement(application)),
            ).build()
        }
        fun debugAttribute(value: Boolean): Resources.XmlAttribute = Resources.XmlAttribute.newBuilder()
            .setName("debuggable").setNamespaceUri(androidNamespace).setResourceId(debuggableResourceId)
            .setCompiledItem(Resources.Item.newBuilder().setPrim(Resources.Primitive.newBuilder().setBooleanValue(value)))
            .build()
        val fixture = File(temporaryDir, "identity-fixture.aab")
        fun inspect(node: Resources.XmlNode, allowed: Boolean) {
            ZipOutputStream(fixture.outputStream()).use {
                it.putNextEntry(ZipEntry(manifestEntry))
                it.write(node.toByteArray())
                it.closeEntry()
            }
            val refusal = runCatching { verifyStoreBundle(fixture) }.exceptionOrNull()
            check(if (allowed) refusal == null else refusal is GradleException) { "Binary AAB identity fixture had unexpected outcome" }
        }
        try {
            inspect(manifest(storePackage), true)
            inspect(manifest(storePackage, debugAttribute(false)), true)
            inspect(manifest("$storePackage.debug", debugAttribute(true)), false)
            inspect(manifest("$storePackage.debug", debugAttribute(false)), false)
            inspect(manifest(storePackage, debugAttribute(true)), false)
            inspect(manifest(storePackage, debugAttribute(true).toBuilder().setValue("false").build()), false)
            inspect(manifest(storePackage, debugAttribute(true).toBuilder().setName("misnamed").build()), false)
            inspect(manifest(storePackage, debugAttribute(false).toBuilder().clearCompiledItem().setValue("@bool/debuggable").build()), false)
            val hiddenPackage = manifest(storePackage).toBuilder()
            hiddenPackage.elementBuilder.getAttributeBuilder(0).setCompiledItem(
                Resources.Item.newBuilder().setStr(Resources.String.newBuilder().setValue("$storePackage.debug")),
            )
            inspect(hiddenPackage.build(), false)
            logger.lifecycle("9 binary AAB identity fixtures verified; no signing or Store delivery claimed")
        } finally {
            fixture.delete()
        }
    }
}
