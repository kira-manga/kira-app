package me.manga.kira

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.Configuration
import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.crashlytics.CrashlyticsLogWriter
import co.touchlab.kermit.platformLogWriter
import com.google.firebase.FirebaseApp
import me.manga.kira.admin.Admin
import me.manga.kira.core.android.setAndroidAppContext
import me.manga.kira.di.allReworkModules
import me.manga.kira.di.appKoinModule
import me.manga.kira.di.initKoin
import me.manga.kira.firebase_cores.messaging.MessagingNotificationChannels
import me.manga.kira.platform.activity.ActivityHolder
import me.manga.kira.platform.notification.NotificationPresenter
import me.manga.kira.platform.update.AppUpdateClient
import me.manga.kira.work.LibraryRefreshScheduling
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory

/**
 * Application entry point for the KMP Android app.
 *
 * Bootstrap order (preserved verbatim where it matters for Koin and Room init):
 *   1. Register Android Context with the shared layer so Room (DatabaseBuilder.android.kt) can
 *      resolve `applicationContext` BEFORE Koin starts wiring up singletons. (DeviceTier detection
 *      moved to the :platform AndroidDeviceTierProbe, Context-injected via Koin — PC-1, Task #422.)
 *   2. Start Koin with `allSharedModules() + platformModule() + appKoinModule` and install the
 *      `KoinWorkerFactory` via `workManagerFactory()` so `androidx.work.WorkManager` resolves
 *      worker dependencies (ChapterDao, CbzManager, LibraryRepository, etc.) through the standard
 *      Koin graph instead of `GlobalContext.get()`.
 *   3. (No custom crash handler — by design.) Fatal crashes are left to Firebase Crashlytics'
 *      default uncaught-exception handler so they are reported as real FATAL crashes. A prior custom
 *      handler that recorded a non-fatal then killed the process is removed — it converted fatals
 *      into (often-lost) non-fatals and kept crashes out of Firebase.
 *   4. Eagerly resolve the Phase-8 expect/actual replacements for upstream's static helpers:
 *      - `AppUpdateClient` (was `AppUpdateHelper.init(this)`)
 *      - `NotificationPresenter` (was `NotificationHelper.init(this)`)
 *      Both are Koin singletons — constructing them now matches upstream's "init at app start"
 *      behavior (channel pre-creation, Play Core manager handshake) without leaving them lazy.
 *   5. Best-effort initialize Firebase and route Kermit logs into Crashlytics as breadcrumbs (via
 *      `CrashlyticsLogWriter`) so the log trail leading up to a crash enriches the report. Both are
 *      wrapped in try/catch so a misconfigured device (missing Play Services / Crashlytics
 *      unavailable) doesn't kill the app at launch. The Google Mobile Ads SDK is deliberately NOT
 *      initialized here (unlike native): it is initialized once-per-process only after UMP consent
 *      permits ad requests, in `MainActivity.startConsentFlow()` gated on `canRequestAds()`.
 *
 * Implements [Configuration.Provider] so WorkManager picks up the Koin-backed factory installed
 * in step 2. Without this, WorkManager would default-initialize itself before step 2 ran, miss
 * the factory, and fall back to reflection-based instantiation that can't see Koin-injected deps.
 *
 * NotificationWorker + MangaDownloadWorker deferrals are documented in [appKoinModule]'s KDoc —
 * the former is an upstream debug stub never enqueued, the latter is upstream-commented-out code.
 */
class MyApp :
    Application(),
    Configuration.Provider {
    private val log = Logger.withTag("MyApp")

    @OptIn(ExperimentalKermitApi::class)
    override fun onCreate() {
        super.onCreate()

        // 0) SECURITY — establish the release log floor FIRST (2026-07 audit: it used to be raised
        //    at step 5b-i, AFTER Koin start / scheduling / eager singletons, so startup-window
        //    Info logs reached release Logcat before the floor landed). The legacy scrapers log
        //    request URLs / header maps (Cookie, cf_clearance) / HTML bodies at Info — none of
        //    that may reach Logcat or the Crashlytics breadcrumb trail in a shipped build. The
        //    5b-i site keeps its (now-redundant, idempotent) call as a second anchor.
        if (!BuildConfig.DEBUG) {
            Logger.setMinSeverity(Severity.Warn)
        }

        // 1) Register Android Context with the shared-layer Room gate BEFORE Koin starts.
        //    (DeviceTier detection moved to the :platform AndroidDeviceTierProbe, which takes the
        //    Context via Koin `androidContext()` — no pre-Koin global setter needed; PC-1, Task #422.)
        setAndroidAppContext(applicationContext)

        // 1a) C1 (2026-07-03): debug-only admin. Admin.isAdmin defaults to false (fail-closed);
        //     only debuggable builds get the admin complaint console (the flag's former second
        //     consumer, the /dev/source registry feed, was deleted in SourceRegistry retirement
        //     Phase 6). Release keeps false — no production admin mechanism by design (see
        //     Admin.kt, which also records that C2 Firestore rules stay intentionally deferred).
        Admin.isAdmin = BuildConfig.DEBUG

        // 1b) Track the current foreground Activity in ActivityHolder so the Play Core / UMP / AdMob
        //     facades bound in PlatformModule.android.kt with `{ ActivityHolder.current }` can launch
        //     their Activity-hosted surfaces (in-app update / review dialogs, UMP consent form).
        registerActivityLifecycleCallbacks(activityLifecycleCallbacks)

        // 2) Start Koin via the shared bootstrap helper. `androidContext()` is required so the
        // PlatformModule.android.kt `androidContext()` lookups (SettingsFactory, ConnectivityObserver,
        // SecureStorage, AdProvider, etc.) resolve; `androidLogger()` pipes Koin diagnostics into
        // Logcat; `workManagerFactory()` installs the `KoinWorkerFactory` so worker bindings in
        // [appKoinModule] (`workerOf(...)`) are honored by WorkManager. `extraModules =
        // allReworkModules()` layers the architecture-rework feature graph on top of the legacy
        // bindings — both graphs coexist until the Phase 8.y route swap takes the rework Library
        // screen user-facing. Koin 4 allows definition override by default, so a collision with the
        // legacy SharedModule bindings does NOT fail here at startup (last-loaded wins); the
        // duplicate-binding guard is KoinGraphRegistrationTest, which loads this exact module set
        // with allowOverride(false).
        initKoin(allReworkModules()) {
            androidLogger()
            androidContext(this@MyApp)
            workManagerFactory()
            modules(appKoinModule)
        }

        // 2a) M2 (2026-07-03): schedule the 12h periodic background library refresh — the Android
        //     twin of the iOS BGAppRefreshTask. Runs the existing LibraryRefreshWorker on a
        //     CONNECTED+battery-not-low unique periodic chain (distinct from the manual
        //     pull-to-refresh one-time chain); UPDATE policy makes the per-launch call free.
        //     After initKoin so WorkManager's on-demand init picks up the KoinWorkerFactory.
        try {
            LibraryRefreshScheduling.schedule(this)
        } catch (t: Throwable) {
            log.e(t) { "Periodic library refresh scheduling failed" }
        }

        // 3) No custom uncaught-exception handler — by design. Firebase Crashlytics installs the
        // default Thread.UncaughtExceptionHandler (via its Firebase init provider, reinforced by
        // FirebaseApp.initializeApp below) which records fatal crashes and chains to the system
        // handler for normal termination. A prior custom handler here recorded the throwable as a
        // NON-FATAL (recordException) and then Process.killProcess'd before Crashlytics could persist
        // it — converting fatals into (often-lost) non-fatals and keeping them out of Firebase. We
        // deliberately install NONE: let crashes surface as real fatals the Crashlytics SDK captures.

        // 4) Eagerly construct Koin singletons that mirror upstream's static `*Helper.init(this)` calls.
        // `get()` on the application-Koin scope triggers immediate construction; the side-effects in
        // each constructor (channel handshake for NotificationPresenter; Play Core manager init for
        // AppUpdateClient) happen now rather than on first use.
        try {
            get<AppUpdateClient>()
        } catch (t: Throwable) {
            log.e(t) { "AppUpdateClient eager init failed" }
        }
        try {
            get<NotificationPresenter>()
        } catch (t: Throwable) {
            log.e(t) { "NotificationPresenter eager init failed" }
        }

        // 4b) Pre-create the FCM "App Messages" channel at app start (mirroring native
        //     NotificationHelper.init) so background-delivered notification-messages — posted by the
        //     FCM SDK without invoking MyFirebaseMessagingService — resolve the branded HIGH channel
        //     instead of FCM's unbranded "Miscellaneous" fallback (#12). Idempotent.
        try {
            MessagingNotificationChannels.ensure(this)
        } catch (t: Throwable) {
            log.e(t) { "FCM message channel pre-create failed" }
        }

        // 5) Best-effort 3rd-party SDK bootstraps.
        try {
            FirebaseApp.initializeApp(this)
        } catch (t: Throwable) {
            log.e(t) { "FirebaseApp.initializeApp failed" }
        }

        // 5b) Route Kermit log output into Crashlytics as breadcrumbs so the diagnostic context
        //     leading up to a crash (Info+ logs) and any logged throwables (Warn+) enrich the
        //     Crashlytics report. The CrashlyticsLogWriter is added alongside the default platform
        //     writer (Logcat) AFTER FirebaseApp.initializeApp because the writer's init wires into
        //     the Firebase Crashlytics backend. Wrapped in try/catch so a misconfigured device
        //     (missing google-services.json / Crashlytics unavailable) doesn't kill launch.
        // 5b-i) SECURITY: in release builds, drop Info/Debug/Verbose logs globally BEFORE wiring
        //       any writer. The legacy source scrapers emit Info-level diagnostics that can include
        //       request URLs, header maps (Cookie/cf_clearance/User-Agent values), and full HTML
        //       bodies; raising the floor to Warn means none of that reaches Logcat OR the
        //       Crashlytics breadcrumb trail in a shipped build (the lambda isn't even evaluated).
        //       Debug builds keep verbose logs for development.
        if (!BuildConfig.DEBUG) {
            Logger.setMinSeverity(Severity.Warn)
        }
        try {
            Logger.setLogWriters(platformLogWriter(), CrashlyticsLogWriter())
        } catch (t: Throwable) {
            log.e(t) { "CrashlyticsLogWriter install failed" }
        }

        // The unused AdMob/UMP stack was removed for the first release. No advertising SDK is
        // initialized by the application process.
    }

    /**
     * Keeps [ActivityHolder] pointed at the current foreground Activity. `onActivityResumed` sets
     * it; `onActivityPaused` / `onActivityDestroyed` clear it only if the paused/destroyed Activity
     * is still the held one (identity guard in [ActivityHolder.clear]) so Activity-to-Activity
     * transitions don't null out a newer Activity that already resumed.
     */
    private val activityLifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) = ActivityHolder.set(activity)

            override fun onActivityPaused(activity: Activity) = ActivityHolder.clear(activity)

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) = ActivityHolder.clear(activity)
        }

    /**
     * Required by [Configuration.Provider]. The `KoinWorkerFactory` is installed in [onCreate]
     * via `workManagerFactory()` and wired into Koin's `GlobalContext`; WorkManager pulls it from
     * there when it first initializes.
     */
    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()
}

/*
 * §253 audit-trail postscript — cluster283 §253 sweep (2026-05-29)
 * Classification: LIVE-HOST (android-host) — the Application object; process-wide bootstrap owner.
 *
 * LIVE evidence:
 *  - AndroidManifest.xml:30 declares application android:name=".MyApp", so the OS instantiates THIS
 *    class as the process Application — it is the real entry, not a stale skeleton.
 *  - initKoin(allReworkModules()) { ... } at line 74 calls the shared bootstrap whose signature is
 *    fun initKoin(extraModules, appDeclaration) (verified KoinInitializer.kt:36); the trailing
 *    lambda adds androidLogger() + androidContext(this) + workManagerFactory() + modules(appKoinModule).
 *  - This is the SOLE production startKoin path on Android; the iOS host uses KoinHelper.doInitKoin
 *    and Desktop uses Main.kt initKoin(allReworkModules()) — three hosts, one shared helper.
 *  - Status: LIVE-HOST. Carries genuine bootstrap orchestration (not a thin shell) but ALL of it is
 *    glue wiring into shared/:platform singletons — no feature business logic resides here.
 *
 * Delta-axes (bootstrap seams, ordered as onCreate executes):
 *  1. Context gating — setAndroidAppContext must run BEFORE Koin so Room DatabaseBuilder.android.kt
 *     resolves applicationContext. (DeviceTier detection moved to the :platform AndroidDeviceTierProbe,
 *     which takes Context via Koin `androidContext()` — no pre-Koin global setter — PC-1, Task #422.)
 *  2. Koin startKoin wiring — initKoin layers allReworkModules() (rework feature graph) plus the
 *     app-local appKoinModule on top of allSharedModules()+platformModule(); duplicate-binding
 *     diagnostics fire here at startup.
 *  3. WorkManager integration — Configuration.Provider (line 54) + workManagerFactory() (line 77)
 *     install the KoinWorkerFactory so CbzMigrationWorker/LibraryRefreshWorker resolve via Koin,
 *     and tools:node="remove" on androidx.startup InitializationProvider (manifest:76-79) defers init.
 *  4. Crash bridge — Thread.setDefaultUncaughtExceptionHandler (line 85) routes through CrashReporter
 *     then posts CrashActivity.start on the main looper (line 101), delegating to the prior handler.
 *  5. Eager singleton priming — get of AppUpdateClient + NotificationPresenter (lines 115-120) mirror
 *     upstream static Helper.init(this) side-effects (channel + Play Core handshake) at app start.
 *  6. Third-party SDK boot — FirebaseApp.initializeApp + MobileAds.initialize (lines 127-133), each
 *     try-catch wrapped so a misconfigured device cannot crash launch.
 *
 * Nested-comment hazard check: no interior slash-star, star-slash, nor slash-star-star sequences;
 * GlobalContext.get references in prose are spelled "get of"; block opens once and closes once.
 * Diff is purely additive.
 */
