# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


#
# Gson uses generic type information stored in a class file when working with
# fields. Proguard removes such information by default, keep it.
-keepattributes Signature

# This is also needed for R8 in compat mode since multiple
# optimizations will remove the generic signature such as class
# merging and argument removal. See:
# https://r8.googlesource.com/r8/+/refs/heads/main/compatibility-faq.md#troubleshooting-gson-gson
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Optional. For using GSON @Expose annotation
-keepattributes AnnotationDefault,RuntimeVisibleAnnotations
-keep class com.google.gson.reflect.TypeToken { <fields>; }
-keepclassmembers class **$TypeAdapterFactory { <fields>; }


-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
# prevent Crashlytics obfuscation
-keep class com.crashlytics.** { *; }
-dontwarn com.crashlytics.**

# AdMob mediation adapters — suppress missing optional annotations / cross-network refs.
# These SDKs (facebook-ads, ironsource, vungle, inmobi, unity, applovin, mintegral)
# reference each other's classes for optional interop; R8 cannot resolve them all.
-dontwarn com.facebook.**
-dontwarn com.ironsource.**
-dontwarn com.vungle.**
-dontwarn com.inmobi.**
-dontwarn com.unity3d.**
-dontwarn com.applovin.**
-dontwarn com.mbridge.**
-dontwarn com.fyber.**
-dontwarn com.chartboost.**
-dontwarn com.tapjoy.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Keep AdMob mediation entrypoints — discovered via reflection by Google Mobile Ads SDK
-keep class com.google.ads.mediation.** { *; }
-keep class com.google.android.gms.ads.mediation.** { *; }

# ---------------------------------------------------------------------------
# Phase 12.x — KMP migration additions
# ---------------------------------------------------------------------------

# Workers — Koin's `KoinWorkerFactory` + `workerOf(::ClassName)` resolves WorkManager workers
# reflectively at runtime. R8 cannot see the call site (`workerOf` is a Kotlin inline DSL that
# erases the class literal into a generic factory), so workers MUST be -keep'd along with their
# constructors. Without this, R8 strips them and `WorkManager.enqueue(OneTimeWorkRequest<X>())`
# throws ClassNotFoundException at runtime.
-keep class me.manga.kira.work.** { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# (Removed) CrashActivity keep rules — the custom crash Activity + UncaughtExceptionHandler were
# deliberately deleted (Firebase's default handler records the real fatal; see MyApp KDoc). The
# class no longer exists, so the old -keep rules were dead configuration.

# Compose Multiplatform compose-resources generated accessors — the `Res` class and its nested
# `string`/`drawable`/`font` accessors are reflected over by `org.jetbrains.compose.resources` at
# runtime when looking up `Res.string.X` / `Res.drawable.Y`. Without -keep, R8 strips the inner
# classes that the runtime lookup expects to find by name.
-keep class me.manga.kira.composeapp.generated.resources.** { *; }
-keep class me.manga.kira.composeapp.generated.resources.Res$* { *; }

# Kotlinx-datetime — `kotlinx.datetime.LocalDate` / `LocalDateTime` serialization is reflective via
# their `Companion.serializer()` accessor. The kotlinx-serialization consumer rules cover most of
# this, but keep the explicit Companion references since several Room `@TypeConverter`s call into
# them.
-keep class kotlinx.datetime.** { *; }
-dontwarn kotlinx.datetime.**

# Room — Room generally ships its own consumer rules, but our DAOs use suspend functions whose
# bytecode includes Continuation params that R8 has been known to mangle on certain AGP/R8 pair
# combos. Belt-and-braces:
-keep class me.manga.kira.data.local.dao.** { *; }
-keep @androidx.room.Entity class me.manga.kira.data.local.entity.** { *; }
-keepclassmembers class me.manga.kira.data.local.entity.** { *; }

# Firestore POJO mapping — ComplaintFirestoreDataSource passes its ComplaintDto to .add()/.set(),
# and the SDK's CustomClassMapper reads the getters reflectively at runtime. The getters have no
# code call sites, so R8 full mode would strip or rename them and the write path would fail (or
# write obfuscated field names) in release builds only. Keep the DTO's members by name:
-keepclassmembers class me.manga.kira.presentation.features.complaint.repository.ComplaintFirestoreDataSource$ComplaintDto {
    <init>(...);
    <fields>;
    <methods>;
}

# Koin reflection on ViewModel factories — `viewModel { Class(get(), get()) }` doesn't reflect over
# the VM class itself, but Koin's runtime DOES read the no-arg companion of `ViewModelProvider`
# implementations. ViewModels are referenced from Compose `koinViewModel<T>()` which keeps T, so
# this is mostly defensive — but consistent with upstream's Hilt -keep policy which kept all VMs:
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Manifest receivers — the upstream manifest declared a couple of receivers (Firebase Messaging,
# WorkManager's internal receivers). AGP usually emits implicit -keep rules for these, but Firebase
# Messaging in particular has had R8 strip its FirebaseInstanceIdReceiver in past releases. Re-add
# defensively:
-keep class com.google.firebase.iid.** { *; }
-keep class com.google.firebase.messaging.** { *; }