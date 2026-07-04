import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

    plugins {
        alias(libs.plugins.android.application)
        alias(libs.plugins.kotlin.android)
        alias(libs.plugins.kotlin.compose)
        id("kotlin-parcelize")
        id("kotlin-kapt")
        id("dagger.hilt.android.plugin")
        id("com.google.dagger.hilt.android")
        id("com.google.devtools.ksp")
        kotlin("plugin.serialization") version "1.9.0"
        id("androidx.navigation.safeargs.kotlin") version "2.8.9"
        id("com.google.gms.google-services")
        id("com.google.firebase.crashlytics")
//        kotlin("jvm") version "1.9.0"

    }
    fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
    fun propOrFallback(name: String, fallback: String): String {
        val v = (project.findProperty(name) as String?)?.trim()
        return if (!v.isNullOrEmpty()) v else fallback
    }
    android {
        namespace = "me.manga.yamiapk"
        compileSdk = 35

        defaultConfig {
            applicationId = "me.manga.yamiapk"
            minSdk = 26
            targetSdk = 35
            versionCode = 35
            versionName = "1.0.35"

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        signingConfigs {
            create("release") {

                val envKeystoreFile = env("KEYSTORE_FILE")
                val localKeystore = "yami-release.keystore" // أو المسار اللي عندك

                storeFile = file(envKeystoreFile ?: localKeystore)

                // لو على CI: هتكون موجودة
                // لو محلي: ممكن تخليها من gradle.properties (أفضل)، أو تكتبها مباشرة (غير مفضل)
                storePassword = env("KEYSTORE_PASSWORD") ?: (findProperty("KEYSTORE_PASSWORD") as String?)
                keyAlias      = env("KEY_ALIAS") ?: (findProperty("KEY_ALIAS") as String?)
                keyPassword   = env("KEY_PASSWORD") ?: (findProperty("KEY_PASSWORD") as String?)
            }
        }
        buildTypes {
            debug {
//                isMinifyEnabled = true
//                isShrinkResources = true
//                proguardFiles(
//                    getDefaultProguardFile("proguard-android-optimize.txt"),
//                    "proguard-rules.pro"
//                )
                buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/5224354917\"")
                buildConfigField("String", "NATIVE_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/2247696110\"")
                buildConfigField("String", "BANNER_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
//                buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"${findProperty("ADMOB_REWARDED_ID") ?: ""}\"")
//                buildConfigField("String", "NATIVE_AD_UNIT_ID", "\"${findProperty("ADMOB_NATIVE_ID") ?: ""}\"")
//                buildConfigField("String", "BANNER_AD_UNIT_ID", "\"${findProperty("ADMOB_BANNER_ID") ?: ""}\"")


            }
            release {
                signingConfig = signingConfigs.getByName("release")

                val rewarded = propOrFallback("ADMOB_REWARDED_ID", "ca-app-pub-3940256099942544/5224354917")
                val native   = propOrFallback("ADMOB_NATIVE_ID",   "ca-app-pub-3940256099942544/2247696110")
                val banner   = propOrFallback("ADMOB_BANNER_ID",   "ca-app-pub-3940256099942544/6300978111")

                buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"$rewarded\"")
                buildConfigField("String", "NATIVE_AD_UNIT_ID", "\"$native\"")
                buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$banner\"")

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
        kotlinOptions {

            jvmTarget = "11"
        }
        buildFeatures {
            buildConfig = true   // ✅ REQUIRED

            viewBinding = true
            compose = true
        }
    }
    //gradle.projectsEvaluated {
    //    tasks
    //        .matching { it.name.startsWith("uploadCrashlyticsMappingFile") }
    //        .configureEach {
    //            enabled = false
    //        }
    //}

    dependencies {

        implementation(libs.androidx.core.ktx)
        implementation(libs.androidx.lifecycle.runtime.ktx)
        implementation(libs.androidx.activity.compose)
        implementation(platform(libs.androidx.compose.bom))
        implementation(libs.androidx.ui)
        implementation(libs.androidx.ui.tooling.preview)
        implementation(libs.androidx.material3)
        implementation(libs.androidx.palette.ktx)
    //    implementation(libs.androidx.recyclerview)
        implementation(libs.material)
        implementation("androidx.compose.foundation:foundation:1.8.2")

        implementation(libs.androidx.core.i18n)
        testImplementation(libs.junit)
        androidTestImplementation(libs.androidx.junit)
        androidTestImplementation(libs.androidx.espresso.core)
        androidTestImplementation(platform(libs.androidx.compose.bom))
        androidTestImplementation(libs.androidx.ui.test.junit4)
        debugImplementation(libs.androidx.ui.tooling)
        debugImplementation(libs.androidx.ui.test.manifest)
        implementation("androidx.activity:activity-ktx:1.10.1")
    //    implementation("androidx.fragment:fragment-ktx:1.8.6")
        implementation("com.airbnb.android:lottie-compose:6.6.6")

    //    implementation("org.imaginativeworld.whynotimagecarousel:whynotimagecarousel:2.1.0")

    //serialization
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3") // Add the serialization library
        // retrofit
        implementation("com.squareup.retrofit2:retrofit:2.11.0")
        implementation("com.squareup.retrofit2:converter-gson:2.11.0")
        implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
        implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0") // Add this
        implementation("org.jsoup:jsoup:1.18.3")
    //    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    //    implementation("com.davemorrissey.labs:subsampling-scale-image-view-androidx:3.10.0")
        implementation("com.google.dagger:hilt-android:2.57.1")
        kapt("com.google.dagger:hilt-android-compiler:2.57.1")
        val room_version = "2.8.4"
        implementation("androidx.room:room-runtime:$room_version")
        // If this project uses any Kotlin source, use Kotlin Symbol Processing (KSP)
        // See Add the KSP plugin to your project
        ksp("androidx.room:room-compiler:$room_version")
        // optional - Kotlin Extensions and Coroutines support for Room
        implementation("androidx.room:room-ktx:$room_version")
        implementation("androidx.room:room-paging:2.6.1")
        implementation("androidx.paging:paging-runtime:3.3.6")
        implementation("androidx.paging:paging-compose:3.3.6")
        val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
        implementation(composeBom)
        androidTestImplementation(composeBom)
        // Choose one of the following:
        // Material Design 3
        implementation("androidx.constraintlayout:constraintlayout-compose:1.0.1")
        // or Material Design 2
        implementation("androidx.compose.material:material")
        // or skip Material Design and build directly on top of foundational components
        //-------------
        implementation("androidx.compose.foundation:foundation")
        implementation("androidx.compose.ui:ui")
        implementation("androidx.compose.material3:material3")
        implementation("androidx.compose.ui:ui-tooling-preview")
        implementation(libs.androidx.ui.graphics)

        //------------
        implementation("androidx.compose.animation:animation")
        // 2️⃣ Override only the UI (and Foundation, Material3, etc.) to beta01
    //    implementation("androidx.compose.ui:ui:1.9.0-beta01")
    //    implementation("androidx.compose.foundation:foundation:1.9.0-beta01")
    ////    implementation("androidx.compose.material3:material3:1.9.0-beta01")
    //    // …and any other Compose modules you use:
    //    implementation("androidx.compose.ui:ui-graphics:1.9.0-beta01")
    //    implementation("androidx.compose.ui:ui-tooling-preview:1.9.0-beta01")

        // Android Studio Preview support
        debugImplementation("androidx.compose.ui:ui-tooling")
        implementation("androidx.compose.material:material-icons-core")
        implementation("androidx.compose.material:material-icons-extended")
        implementation("androidx.compose.material3.adaptive:adaptive")
        implementation("androidx.activity:activity-compose:1.10.0")
        // Optional - Integration with ViewModels
        implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
        // Optional - Integration with LiveData
        implementation("androidx.compose.runtime:runtime-livedata")
        implementation("io.coil-kt.coil3:coil-compose:3.1.0")
        implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")


//        implementation("io.coil-kt.coil3:coil-gif:3.1.0")
        implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")


        implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
        implementation("me.saket.telephoto:zoomable-image-coil3:0.16.0")
        implementation("net.engawapg.lib:zoomable:2.8.0")
        implementation("androidx.compose.material:material:1.8.0")
        implementation("androidx.core:core-splashscreen:1.0.1")

        // Navigation Compose
        implementation("androidx.navigation:navigation-compose:2.8.9")
        implementation("androidx.navigation:navigation-runtime-ktx:2.8.9")
        implementation("androidx.navigation:navigation-ui-ktx:2.8.9")
    //    val navVersion = "2.8.9"
        ksp("androidx.navigation:navigation-safe-args-generator:2.5.3")
        implementation("androidx.datastore:datastore-preferences:1.1.4")


    //    implementation("com.github.piasy:BigImageViewer:1.8.1")
    //    implementation("com.github.piasy:GlideImageLoader:1.8.1")
        implementation("androidx.hilt:hilt-work:1.0.0")
        kapt("androidx.hilt:hilt-compiler:1.0.0")

        implementation("com.composables:core:1.32.0")
        val work_version = "2.10.1"
        // Kotlin + coroutines
        implementation("androidx.work:work-runtime-ktx:$work_version")
        implementation("androidx.work:work-gcm:$work_version")
        implementation("com.google.android.play:app-update:2.1.0")

        // For Kotlin users, also import the Kotlin extensions library for Play In-App Update:
        implementation("com.google.android.play:app-update-ktx:2.1.0")
        implementation("com.google.android.play:review:2.0.2")

        // For Kotlin users, also import the Kotlin extensions library for Play In-App Review:
        implementation("com.google.android.play:review-ktx:2.0.2")
        implementation("com.google.android.ump:user-messaging-platform:3.2.0")

        implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
        implementation("com.google.firebase:firebase-analytics")
        implementation("com.google.firebase:firebase-crashlytics")
    //    implementation("com.google.firebase:firebase-auth")
        implementation("com.google.firebase:firebase-messaging")

        implementation("com.google.firebase:firebase-firestore")
        implementation("com.google.android.gms:play-services-ads:24.8.0")
        implementation("com.facebook.shimmer:shimmer:0.5.0")
        implementation("androidx.compose.ui:ui-viewbinding:1.6.0-rc01")
        implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")

        implementation("com.facebook.infer.annotation:infer-annotation:0.18.0")

        // ad mob sources
        implementation("com.google.ads.mediation:inmobi:11.1.0.0")
        implementation("com.google.ads.mediation:ironsource:9.2.0.0")
        implementation("com.google.ads.mediation:vungle:7.6.1.0")
        implementation("com.google.ads.mediation:facebook:6.21.0.0")


        implementation("org.apache.commons:commons-compress:1.24.0")

//        implementation("com.github.awxkee:avif-coder-coil:2.1.2")
        implementation("org.aomedia.avif.android:avif:1.3.0.841110fd")

    }


val inputKtFile = File(
    "E:/profaction/yami manga last 8-8/yami---manga/app/src/main/java/me/manga/yami/sources_repositry/data/MangaSource.kt"
)

// Output name for jar/dex
//val pluginName = "MangaSource"


//    tasks.register("buildDexFromFile") {
//        group = "plugin"
//        description = "Compile a specific Kotlin file into JAR and DEX"
//
//        doLast {
//
//            // ------------------------------
//            // READ ANDROID SDK ONLY NOW (not at configuration time)
//            // ------------------------------
//            val androidSdk =
//                System.getenv("ANDROID_HOME")
//                    ?: System.getenv("ANDROID_SDK_ROOT")
//                    ?: throw GradleException(
//                        "ANDROID_HOME or ANDROID_SDK_ROOT is not set.\n" +
//                                "Fix: Set environment variable pointing to Android SDK."
//                    )
//
//            val buildToolsVersion = "34.0.0"
//            val d8Path = "$androidSdk/build-tools/$buildToolsVersion/d8"
//
//            // ------------------------------
//            // BEGIN BUILD
//            // ------------------------------
//
//            if (!inputKtFile.exists()) {
//                throw GradleException("Source file not found: ${inputKtFile.absolutePath}")
//            }
//
//            val buildDirFile = File(project.buildDir, "single/$pluginName")
//            val classesDir = File(buildDirFile, "classes")
//            val jarFile = File(buildDirFile, "$pluginName.jar")
//            val dexOut = File(buildDirFile, "dex")
//
//            classesDir.mkdirs()
//            dexOut.mkdirs()
//
//            println("➡️ Compiling Kotlin file: ${inputKtFile.absolutePath}")
//
//            val args = arrayOf(
//                "-jvm-target", "1.8",
//                "-classpath", files(
//                    "$androidSdk/platforms/android-35/android.jar",
//                    "$buildDir/tmp/kotlin-classes/debug",
//                    "$buildDir/intermediates/javac/debug/classes",
//                    configurations.debugCompileClasspath.get().asPath
//                ).asPath,
//                "-d", classesDir.absolutePath,
//                inputKtFile.absolutePath
//            )
//
//            val result = K2JVMCompiler().exec(System.err, *args).code
//            if (result != 0) throw GradleException("Kotlin compilation failed with code: $result")
//
//            println("✔ Kotlin compiled")
//
//            println("➡️ Creating JAR: ${jarFile.absolutePath}")
//            ant.invokeMethod("jar", mapOf(
//                "destfile" to jarFile.absolutePath,
//                "basedir" to classesDir.absolutePath
//            ))
//
//            println("✔ JAR created")
//
//            println("➡️ Converting JAR to DEX using D8...")
//            exec {
//                commandLine(
//                    d8Path,
//                    "--release",
//                    "--min-api", "21",
//                    "--output", dexOut.absolutePath,
//                    jarFile.absolutePath
//                )
//            }
//
//            println("🎉 DONE!")
//            println("📦 JAR: ${jarFile.absolutePath}")
//            println("📱 DEX: ${File(dexOut, "classes.dex").absolutePath}")
//        }
//    }


//    val inputKtPluginFile = file(
//        "E:/profaction/yami manga last 8-8/yami---manga/app/src/main/java/me/manga/yami/sources_repositry/data/MangaSource.kt"
//    )
//
//    val pluginName = "MangaSource"
//-----------------------------------------------

//    androidComponents.onVariants { variant ->
//
//        val variantName = variant.name
//        val capitalized = variantName.replaceFirstChar { it.uppercase() }
//
//        val taskName = "buildDexFromFile${capitalized}"
//
//        tasks.register(taskName) {
//
//            group = "plugin"
//            description = "Compile a single Kotlin file into JAR and DEX for variant: $variantName"
//
//            doLast {
//
//                // 1) Validate file
//                if (!inputKtPluginFile.exists()) {
//                    throw GradleException("❌ Source file not found: ${inputKtPluginFile.absolutePath}")
//                }
//
//                // 2) Get Android SDK
//                val androidSdk = System.getenv("ANDROID_HOME")
//                    ?: System.getenv("ANDROID_SDK_ROOT")
//                    ?: throw GradleException("❌ ANDROID_HOME or ANDROID_SDK_ROOT is not set.")
//
//                val d8 = "$androidSdk/build-tools/34.0.0/d8"
//                if (!File(d8).exists()) throw GradleException("❌ D8 not found at: $d8")
//
//                // 3) Output dirs
//                val outputRoot = file("$buildDir/pluginDex/$variantName")
//                val classesOut = File(outputRoot, "classes")
//                val jarFile = File(outputRoot, "$pluginName.jar")
//
//                outputRoot.mkdirs()
//                classesOut.mkdirs()
//
//                // 4) Classpath (works correctly in Kotlin DSL)
//                val classpath = variant.compileClasspath.files.joinToString(File.pathSeparator) { it.absolutePath }
//
//                println("➡️ Classpath:")
//                println(classpath)
//
//                // 5) Compile .kt → .class
//                println("➡️ Compiling Kotlin…")
//
//                ant.invokeMethod("javac", mapOf(
//                    "srcdir" to inputKtPluginFile.parent,
//                    "includes" to inputKtPluginFile.name,
//                    "destdir" to classesOut.absolutePath,
//                    "classpath" to classpath,
//                    "includeantruntime" to false
//                ))
//
//                println("✔ Kotlin compiled!")
//
//                // 6) Create JAR
//                ant.invokeMethod("jar", mapOf(
//                    "destfile" to jarFile.absolutePath,
//                    "basedir" to classesOut.absolutePath
//                ))
//
//                println("✔ JAR created: $jarFile")
//
//                // 7) Convert JAR → DEX
//                println("➡️ Running D8…")
//
//                exec {
//                    commandLine(
//                        d8,
//                        "--release",
//                        "--min-api", "21",
//                        "--output", outputRoot.absolutePath,
//                        jarFile.absolutePath
//                    )
//                }
//
//                println("🎉 DONE!")
//                println("📦 JAR: $jarFile")
//                println("📱 DEX: ${File(outputRoot, "classes.dex").absolutePath}")
//            }
//        }
//    }

// Required dependencies for compiling PluginData.kt
    val pluginClasspath = configurations.detachedConfiguration(
        dependencies.create("org.jetbrains.kotlin:kotlin-stdlib:1.9.0"),
        dependencies.create("org.json:json:20231013"),
        dependencies.create("org.jsoup:jsoup:1.18.3")   // <-- REQUIRED

    ).resolve().joinToString(File.pathSeparator)

    val inputKtPluginFile = file("src/main/java/me/manga/yami/dex/AasqPlugin.kt")
    val pluginName = "PluginData"

    tasks.register("buildDexPlugin") {

        group = "plugin"
        description = "Build Kotlin file → JAR → DEX"

        doLast {

            if (!inputKtPluginFile.exists()) {
                throw GradleException("Source file not found: ${inputKtPluginFile.absolutePath}")
            }

            // --- FIND SDK + D8 ---
            val androidSdk = System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: throw GradleException("ANDROID_HOME / ANDROID_SDK_ROOT not set")

            val buildTools = File("$androidSdk/build-tools")
            val d8 = buildTools.listFiles()
                ?.sortedByDescending { it.name }
                ?.flatMap { dir -> listOf("d8.bat", "d8").map { File(dir, it) } }
                ?.firstOrNull { it.exists() }
                ?: throw GradleException("❌ No D8 found")

            println("✔ Using D8: ${d8.absolutePath}")

            // --- OUTPUT ---
            val outRoot = file("$buildDir/pluginDex")
            val classesOut = File(outRoot, "classes")
            val jarOut = File(outRoot, "$pluginName.jar")
            val dexOut = File(outRoot, "$pluginName.dex")

            outRoot.mkdirs()
            classesOut.mkdirs()

            println("➡️ Using plugin classpath:\n$pluginClasspath")

            // --- Compile KOTLIN ---
            val kotlincArgs = arrayOf(
                "-classpath", pluginClasspath,
                "-jvm-target", "1.8",
                "-d", classesOut.absolutePath,
                inputKtPluginFile.absolutePath
            )

            val exit = K2JVMCompiler().exec(System.err, *kotlincArgs).code
            if (exit != 0) throw GradleException("❌ Kotlin compilation failed: exit $exit")

            println("✔ Kotlin compiled to .class")

            // --- JAR ---
            ant.invokeMethod("jar", mapOf(
                "destfile" to jarOut.absolutePath,
                "basedir" to classesOut.absolutePath
            ))

            println("✔ JAR created: $jarOut")

            // --- DEX ---
            exec {
                commandLine(
                    d8.absolutePath,
                    "--release",
                    "--min-api", "21",
                    "--output", outRoot.absolutePath,
                    jarOut.absolutePath
                )
            }

            val classesDex = File(outRoot, "classes.dex")
            if (!classesDex.exists())
                throw GradleException("❌ D8 did NOT generate classes.dex")

            classesDex.copyTo(dexOut, overwrite = true)
            classesDex.delete()

            println("✔ DEX created: ${dexOut.absolutePath}")

            println("🎉 DONE!")
        }
    }
