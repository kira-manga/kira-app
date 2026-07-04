import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :ui — Compose Multiplatform screen surface (design tokens, theme, composable screens).
// Contract §4 / §6: :ui imports :presentation (api) for MVI state/intent/effect and design-system
// types. NEVER imports :data / :platform / :shared — view models do all data orchestration.
// Theme tokens (colors, shapes, spacing, typography) live here and are exposed through MaterialTheme
// at the KiraTheme composable boundary so feature screens never touch raw hex/dp constants.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "me.manga.kira.ui"
        compileSdk = 37
        minSdk = 26
        // CMP-9547 workaround (verified reproduced on AGP 9.2.1 + CMP 1.11.1): the
        // com.android.kotlin.multiplatform.library plugin does NOT copy Compose-MP composeResources
        // (the generated .cvr assets) into the Android APK by default, so stringResource(...) throws
        // MissingResourceException at runtime. This experimental flag restores the asset copy. (This
        // is distinct from androidResources.enable, which governs Android res/R accessors — not the
        // .cvr packaging.) Remove if/when a stable API or upstream fix lands.
        experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true
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
    // ComposeApp.framework as a klib dependency; nothing consumes a standalone ui.framework.
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // :presentation gives screens the ViewModel types + sealed Intent/State/Effect contracts.
            // `api` because composable entry points take the concrete ViewModel as a parameter, so
            // downstream :composeApp wiring needs to see the type unchanged.
            api(project(":presentation"))

            // Compose Multiplatform — runtime/foundation/material3/ui. `api` because every screen
            // re-exposes these (composable surfaces accept Modifier, return @Composable, etc.) and
            // downstream :composeApp wiring sees them on screen entry points.
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.ui)

            // material-icons-extended (Phase 11.ui.UP-2, Option A): real Material vector icons
            // backing the design-system icon layer (KiraIcons / KiraIconButton), replacing the
            // interim text-glyph placeholders. `implementation` — screens consume Icons.* only
            // through the shared KiraIcons map; no public composable surface re-exposes the pack.
            implementation(libs.compose.icons.extended)

            // coil-compose provides AsyncImage/SubcomposeAsyncImage. We use Coil 3's singleton
            // ImageLoader (set in :composeApp/App.kt: setSingletonImageLoaderFactory) so screens
            // here pass plain `model = url` and pick up the AVIF decoder, OkHttp network fetcher,
            // maxBitmapSize override, HighQualitySkiaImageDecoder, and CoilSourceHeaderInterceptor
            // automatically — no per-screen ImageRequest builder needed for cover thumbnails.
            // `implementation` (not `api`) because no public composable surface exposes a Coil
            // type — screens take String URLs and produce composables, the Coil dep stays internal.
            implementation(libs.coil.compose)

            // coil-network-core: NetworkHeaders + ImageRequest.Builder.httpHeaders() extension.
            // Reader pages carry per-page headers (referer / user-agent / source token) on
            // `Page.headers`; the Reader screen attaches them per-request rather than relying on
            // the singleton ImageLoader's host-match interceptor (which silently fails when the
            // image CDN domain differs from the source base URL — see Bug 4 layer 4 documented
            // in `composeApp/.../images/SourceImageRequest.kt`). `implementation` (not `api`)
            // because no public composable surface exposes a Coil type — screens take a `Page`
            // and produce composables.
            implementation(libs.coil.network.core)

            // net.engawapg.lib:zoomable — KMP-capable pinch-to-zoom + pan Modifier extension.
            // Used by the rework Reader (`reader/ReaderScreen.kt`) to wrap each layout
            // composable's outermost Modifier with `.zoomable(rememberZoomState())` so users
            // can pinch-zoom the current page. Mirrors the legacy reader's modifier (see
            // `composeApp/.../reading_modes/HorizontalReadingMode.kt`). Already on the
            // legacy `:composeApp` classpath via its own `implementation(libs.zoomable)`;
            // adding to `:ui` makes the rework Reader self-contained for zoom without
            // routing through a `:composeApp` expect/actual shim. `implementation` (not
            // `api`) — no public composable surface exposes a `ZoomState` type.
            implementation(libs.zoomable)

            // compose-resources: generates the typed `Res` accessor (e.g. Res.font.gellix_regular)
            // so the design-system typography factory (`kiraTypography()`) can load the bundled
            // Gellix .ttf fonts from commonMain. Mirrors :composeApp's resource setup; the font
            // assets live under `ui/src/commonMain/composeResources/font/`. Required by
            // Phase 11.ui.UP-1 (typography parity) — without it MaterialTheme falls back to the
            // platform default font (Roboto/system) and the whole UI loses the Gellix type ramp.
            // `implementation` (not `api`): no public composable surface exposes a resource type;
            // the generated `Res` is internal (publicResClass = false below).
            implementation(libs.compose.components.resources)

            // ui-tooling-preview: the multiplatform `@Preview` annotation
            // (`org.jetbrains.compose.ui.tooling.preview.Preview`) so feature screens can ship
            // canned-state previews (Epic H4 — Home/Search). Mirrors :composeApp's preview setup.
            // `implementation` — the annotation is consumed only inside this module's preview funs;
            // no public composable surface re-exposes a preview type.
            implementation(libs.compose.components.ui.tooling.preview)
            implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.11.1")

            // #7 reader lifecycle bracket: LocalLifecycleOwner / Lifecycle.Event /
            // LifecycleEventObserver (JetBrains Compose-MP lifecycle, common across all targets) so
            // the reading-session timer brackets on the host lifecycle (foreground spans) instead of
            // bare composition. Same version :composeApp already resolves; `implementation` — no
            // public composable surface re-exposes a lifecycle type.
            implementation(libs.androidx.lifecycle.runtime.compose)
        }

        androidMain.dependencies {
            // activity-compose supplies `androidx.activity.compose.BackHandler`, which the Android
            // actual of `me.manga.kira.ui.util.BackHandler` delegates to for real
            // OnBackPressedDispatcher (predictive-back) integration. Android-only: the iOS/desktop
            // actuals are no-ops, so this dep stays out of commonMain. Catalog ref already used by
            // :composeApp and :app. `implementation` — no public composable surface re-exposes an
            // activity-compose type; screens import the common `ui.util.BackHandler` wrapper only.
            implementation(libs.androidx.activity.compose)

            // ui-tooling (compose.uiTooling = androidx.compose.ui:ui-tooling on Android) supplies
            // ComposeViewAdapter, the class Android Studio's layout renderer instantiates to draw
            // @Preview composables. Without it the IDE preview crashes with
            // ClassNotFoundException: androidx.compose.ui.tooling.ComposeViewAdapter. The annotation
            // (compose.preview) only declares @Preview; this is the renderer. In a normal Android
            // module this would be debugImplementation, but the single-variant
            // com.android.kotlin.multiplatform.library plugin has no debug configuration, so it is a
            // plain androidMain implementation — R8 strips the unreferenced tooling from release.
            implementation("org.jetbrains.compose.ui:ui-tooling:1.11.1")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // Compose UI tests on the JVM/desktop target (backlog T2/L4): runComposeUiTest +
        // semantics finders/assertions + captureToImage for pixel-level visibility checks.
        // `currentOs` supplies the Skiko runtime the headless test surface renders through.
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.uiTestJUnit4)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "me.manga.kira.ui.generated.resources"
    generateResClass = auto
}

// ---------------------------------------------------------------------------------------------
// Locale key-parity lint (prevents the i18n regression class: a string key added to the default
// `values/` but not to every locale, which silently falls back to English). Fails the build when
// any `values-<loc>/` is missing a key present in the default `values/`. Wired into `check`.
//   Run directly: ./gradlew :ui:checkLocaleKeyParity
// ---------------------------------------------------------------------------------------------
val checkLocaleKeyParity = tasks.register("checkLocaleKeyParity") {
    group = "verification"
    description = "Fail if any values-<loc>/ is missing a string key present in the default values/."
    val resDir = layout.projectDirectory.dir("src/commonMain/composeResources").asFile
    inputs.dir(resDir)
    doLast {
        // Capture the full <string ...> opening tag so we can skip translatable="false" entries.
        // `(?![-\w])` after "string" excludes <string-array> (a bare word boundary would match it).
        val stringRe = Regex("""<string(?![-\w])([^>]*)\bname="([^"]+)"([^>]*)>""")
        fun keysIn(dir: File): Set<String> {
            val files = dir.listFiles { f -> f.isFile && f.extension == "xml" } ?: return emptySet()
            val keys = mutableSetOf<String>()
            for (f in files) {
                // Strip XML comments first so a commented-out <string name=...> can't be counted.
                val text = f.readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
                for (m in stringRe.findAll(text)) {
                    val attrs = m.groupValues[1] + m.groupValues[3]
                    if (Regex("""translatable\s*=\s*"false"""").containsMatchIn(attrs)) continue
                    keys += m.groupValues[2]
                }
            }
            return keys
        }
        val defaultKeys = keysIn(File(resDir, "values"))
        val problems = StringBuilder()
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
            ?.sortedBy { it.name }
            ?.forEach { locDir ->
                val missing = (defaultKeys - keysIn(locDir)).sorted()
                if (missing.isNotEmpty()) {
                    val shown = missing.take(15).joinToString(", ")
                    val more = if (missing.size > 15) " … (+${missing.size - 15} more)" else ""
                    problems.appendLine("  ${locDir.name}: ${missing.size} missing key(s): $shown$more")
                }
            }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Locale key-parity check FAILED — keys present in values/ but missing in a locale " +
                    "(they would silently fall back to English):\n$problems" +
                    "\nAdd the missing translations (see scripts/check_locale_parity.py to port from native-app).",
            )
        }
        logger.lifecycle("Locale key-parity OK: ${defaultKeys.size} default keys present in every locale.")
    }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(checkLocaleKeyParity) }
