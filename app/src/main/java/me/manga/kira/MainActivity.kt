package me.manga.kira

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import co.touchlab.kermit.Logger
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.kira.App
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.core.storage.StorageKeys
import me.manga.kira.navigation.push.NotificationRouter
import me.manga.kira.navigation.push.PushPayloadParser
import me.manga.kira.navigation.sourceaccess.SourceActivationRequestRouter
import me.manga.kira.platform.consent.ConsentFlowClient
import me.manga.kira.platform.review.InAppReviewClient
import me.manga.kira.platform.storage.SecureStorage
import me.manga.kira.platform.update.AppUpdateClient
import org.koin.core.context.GlobalContext

/**
 * Android launcher. Thin wrapper that hands rendering off to the shared `App()` composable, plus
 * the per-launch Google Play / UMP wiring that native's `MainActivity.onCreate` performs.
 *
 * - `installSplashScreen()` MUST be called BEFORE `super.onCreate()` to bridge the launcher
 *   theme `Theme.App.Starting` into the AndroidX SplashScreen API.
 * - `WindowCompat.setDecorFitsSystemWindows(window, false)` turns the activity into an edge-to-edge
 *   surface so the shared Compose UI can own the system-bar coloring via `Modifier.systemBarsPadding()`
 *   etc. This is the EXACT call native's `MainActivity.onCreate` makes (MainActivity.kt:203) — we
 *   deliberately do NOT use `enableEdgeToEdge()`, which would install AndroidX's default translucent
 *   system-bar scrims and force its own transparent bar colors. Native shows no scrim: it goes
 *   edge-to-edge and lets `Theme.KiraManga` (navigationBarColor=transparent +
 *   windowTranslucentNavigation=true) govern the bars, so matching the call matches native chrome.
 *
 * After `setContent`, three best-effort flows mirror native MainActivity.onCreate (each wrapped so
 * a failure never crashes launch):
 *  1. **In-app update** — `AppUpdateClient.checkForUpdate()` then `startFlexibleUpdate()` when an
 *     update is available (native: `AppUpdateHelper.checkForUpdate(immediate=false)` → flexible).
 *  2. **In-app review** — `InAppReviewClient.requestReview()`, gated on the same "20 days since
 *     first open" threshold native's `ReviewManagerHelper.shouldShowReview()` enforces.
 *  3. **UMP consent** — `requestConsentInfoUpdate()` → `loadAndShowConsentFormIfRequired()`, then
 *     `MobileAds.initialize` gated on `canRequestAds()` (native: `requestConsent` → `initializeAds`).
 *
 * The SPIs are resolved from Koin's `GlobalContext` (this Activity is not a `KoinComponent`), the
 * same out-of-graph resolution pattern `DownloadWorkerV2` uses. The foreground Activity these
 * Activity-hosted dialogs need is supplied by `ActivityHolder`, populated from MyApp's
 * `onActivityResumed` callback — so the consent/review flows (which resolve the Activity via
 * `ActivityHolder` on `Dispatchers.IO`) start from the first `onResume`, not `onCreate`, or they
 * could race the create→resume transaction and find no Activity. The Main-dispatched update flow
 * queues behind the lifecycle transaction and may safely start from `onCreate`.
 */
class MainActivity : ComponentActivity() {

    private val log = Logger.withTag("MainActivity")

    // Activity-scoped scope for the per-launch Play / UMP flows, cancelled in onDestroy — the same
    // shape native uses (`reviewScope = CoroutineScope(Dispatchers.IO + Job())`, cancelled in
    // onDestroy). SupervisorJob so one flow failing doesn't cancel the siblings.
    private val launchFlowScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Once-guard: the consent/review flows run once per Activity instance, deferred to the first
    // onResume so ActivityHolder is guaranteed to be populated (see class KDoc).
    private var activityScopedFlowsStarted = false

    // POST_NOTIFICATIONS runtime-permission launcher (Android 13+). Registered here — not in a
    // composable — because MainActivity is the Android permission-UX owner. Requested once ever via
    // [maybeRequestNotificationPermission]; the result is intentionally ignored (a denial just means
    // notifications aren't shown — the FCM token + deep-link paths are unaffected). Field-initialized
    // so registration happens before the Activity is STARTED, as ActivityResult requires.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Native parity: MainActivity.kt:203 calls WindowCompat.setDecorFitsSystemWindows(window,
        // false) right after super.onCreate() (post-installSplashScreen) — plain edge-to-edge with
        // no AndroidX scrim. See class KDoc for why this replaces enableEdgeToEdge().
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            App()
        }

        // A notification cold-launch delivers the tapped payload as this Activity's launch intent.
        // Only on a FRESH create (savedInstanceState == null); on a recreation (rotation, theme
        // change, process-death restore) the same launch intent is re-delivered and would otherwise
        // re-navigate to the pushed screen (#2). handlePushIntent also clears the handled extras.
        if (savedInstanceState == null) {
            handleSourceActivationIntent(intent)
            handlePushIntent(intent)
        }
        maybeRequestNotificationPermission()

        startInAppUpdateFlow()
        registerUpdateInstallListener()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode=singleTop → a tap while the app is already running re-delivers here rather than
        // creating a fresh Activity. Update the stored intent and route the deep link.
        setIntent(intent)
        handleSourceActivationIntent(intent)
        handlePushIntent(intent)
    }

    override fun onResume() {
        // Base Activity.onResume() dispatches MyApp's onActivityResumed (which sets ActivityHolder)
        // before returning, so by this point ActivityHolder.current == this.
        super.onResume()
        if (!activityScopedFlowsStarted) {
            activityScopedFlowsStarted = true
            startConsentFlow()
            startInAppReviewFlow()
        }
        // Native parity: MainActivity.onResume calls AppUpdateHelper.resumeUpdate → completeUpdate
        // so a flexible update that finished downloading while the app was backgrounded is installed.
        resumeInAppUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            GlobalContext.get().get<AppUpdateClient>().unregisterUpdateListener()
        } catch (t: Throwable) {
            log.e(t) { "unregister in-app update listener failed" }
        }
        launchFlowScope.cancel()
    }

    /** Mirrors native `AppUpdateHelper.checkForUpdate(immediate=false)` → flexible-update auto-start. */
    private fun startInAppUpdateFlow() {
        launchFlowScope.launch {
            try {
                val client = GlobalContext.get().get<AppUpdateClient>()
                if (client.checkForUpdate() != null) {
                    client.startFlexibleUpdate()
                }
            } catch (t: Throwable) {
                log.e(t) { "in-app update flow failed" }
            }
        }
    }

    /**
     * Mirrors native `AppUpdateHelper.registerListener(onDownloaded = { completeUpdate() })`: a
     * flexible update that finishes downloading while the user is in the app is installed
     * immediately. Without this the downloaded APK is stranded and never installed. Unregistered in
     * [onDestroy].
     */
    private fun registerUpdateInstallListener() {
        try {
            val client = GlobalContext.get().get<AppUpdateClient>()
            client.registerUpdateListener {
                launchFlowScope.launch {
                    try {
                        client.completeUpdate()
                    } catch (t: Throwable) {
                        log.e(t) { "in-app update completeUpdate failed" }
                    }
                }
            }
        } catch (t: Throwable) {
            log.e(t) { "register in-app update listener failed" }
        }
    }

    /** Mirrors native `AppUpdateHelper.resumeUpdate` → `completeUpdate` from `onResume`. */
    private fun resumeInAppUpdate() {
        launchFlowScope.launch {
            try {
                GlobalContext.get().get<AppUpdateClient>().resumeIfDownloaded()
            } catch (t: Throwable) {
                log.e(t) { "resume in-app update failed" }
            }
        }
    }

    /** Mirrors native `requestConsent` → `loadAndShowForm` → `initializeAds` (gated on canRequestAds). */
    private fun startConsentFlow() {
        launchFlowScope.launch(Dispatchers.IO) {
            try {
                val consent = GlobalContext.get().get<ConsentFlowClient>()
                consent.requestConsentInfoUpdate()
                consent.loadAndShowConsentFormIfRequired()
                if (consent.canRequestAds()) {
                    // Native calls MobileAds.initialize only once consent permits ad requests. We do
                    // NOT build any ad views here — ad rendering is a separate, out-of-scope item.
                    withContext(Dispatchers.Main) {
                        MobileAds.initialize(this@MainActivity) { /* no-op */ }
                    }
                }
            } catch (t: Throwable) {
                log.e(t) { "UMP consent flow failed" }
            }
        }
    }

    /**
     * Mirrors native `launchInAppReviewSafely()`. The KMP `InAppReviewClient.requestReview()` does
     * NOT self-gate (unlike native's `ReviewManagerHelper.launchInAppReview`, which checks
     * `shouldShowReview()` internally), so the "first open + 20 days" gate is replicated here:
     *  - On the very first launch, record `now` as the first-open timestamp and DO NOT prompt.
     *  - On later launches, prompt only once `now - firstOpen >= REVIEW_MIN_AGE_MILLIS` (20 days).
     *
     * The timestamp is persisted via the already-bound `SecureStorage` SPI (native used a
     * DataStore long under `"first_open_time"`); the threshold matches native's
     * `ReviewManagerHelper.SEVEN_DAYS_MILLIS = TimeUnit.DAYS.toMillis(20)` (the constant is
     * mis-named in native but evaluates to 20 days).
     */
    private fun startInAppReviewFlow() {
        launchFlowScope.launch(Dispatchers.IO) {
            try {
                val storage = GlobalContext.get().get<SecureStorage>()
                val firstOpen = storage.get(KEY_FIRST_OPEN_TIME)?.toLongOrNull() ?: 0L
                val now = System.currentTimeMillis()
                val shouldShow = if (firstOpen == 0L) {
                    storage.put(KEY_FIRST_OPEN_TIME, now.toString())
                    false
                } else {
                    now - firstOpen >= REVIEW_MIN_AGE_MILLIS
                }
                if (shouldShow) {
                    GlobalContext.get().get<InAppReviewClient>().requestReview()
                }
            } catch (t: Throwable) {
                log.e(t) { "in-app review flow failed" }
            }
        }
    }

    /**
     * Parse a deep link from a notification-tap intent's extras and hand it to the [NotificationRouter]
     * (which the nav host in `App()` drains). Covers both the cold-launch intent (from [onCreate]) and
     * the warm re-delivery (from [onNewIntent]). Only string extras are read; a payload with no valid
     * deep link is ignored, so the app just opens normally.
     */
    private fun handlePushIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        val data = buildMap {
            for (key in extras.keySet()) {
                val value = extras.getString(key) ?: continue
                put(key, value)
            }
        }
        if (data.isEmpty()) return
        val destination = PushPayloadParser.parse(data) ?: return
        try {
            GlobalContext.get().get<NotificationRouter>().submit(destination)
            // Clear the handled push extras so this intent — retained by getIntent() and pinned by
            // onNewIntent's setIntent — can't be re-parsed and re-navigated on a later recreation (#2).
            intent.replaceExtras(null as Bundle?)
        } catch (t: Throwable) {
            log.e(t) { "failed to submit push deep-link" }
        }
    }

    /** Forward a validated activation URL without retaining or logging its raw value. */
    private fun handleSourceActivationIntent(intent: Intent?) {
        val link = intent?.dataString ?: return
        try {
            val accepted = GlobalContext.get().get<SourceActivationRequestRouter>().submit(link)
            if (accepted) intent.data = null
        } catch (t: Throwable) {
            log.e(t) { "failed to submit source activation link" }
        }
    }

    /**
     * Request POST_NOTIFICATIONS once ever on Android 13+ (guarded by a prefs flag so we never nag).
     * Onboarding's Theme step is the sole owner of the NEW-user ask (contextual, with a denial toast +
     * settings deep-link), so this fires ONLY once onboarding is complete (`first_launch == false`) —
     * a backfill for existing installs that onboarded before notifications existed. Gating on
     * `first_launch` prevents both this and the Theme step firing on a fresh install and burning
     * Android's two allowed prompts (#6). A denial degrades gracefully — notifications simply aren't
     * shown; the FCM token + deep-link paths are unaffected.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) return
        try {
            val prefs = GlobalContext.get().get<SharedPrefsHelper>()
            // Onboarding (Theme step) owns the new-user ask; only backfill once onboarding is done, so
            // the two paths never both fire on a fresh install (#6).
            if (prefs.getBoolean(StorageKeys.FIRST_LAUNCH, true)) return
            if (prefs.getBoolean(StorageKeys.NOTIF_PERMISSION_ASKED, false)) return
            // Launch BEFORE persisting the one-shot flag (2026-07 audit): a throw out of launch()
            // is caught below, and persisting first would burn the single backfill ask on a launch
            // that never showed the dialog. If the process dies between launch and the write, the
            // worst case is one repeat ask — never a permanently-lost one.
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            prefs.putBoolean(StorageKeys.NOTIF_PERMISSION_ASKED, true)
        } catch (t: Throwable) {
            log.e(t) { "notification permission request failed" }
        }
    }

    private companion object {
        const val KEY_FIRST_OPEN_TIME = "first_open_time"

        // Matches native ReviewManagerHelper.SEVEN_DAYS_MILLIS = TimeUnit.DAYS.toMillis(20).
        const val REVIEW_MIN_AGE_MILLIS = 20L * 24L * 60L * 60L * 1000L
    }
}

/*
 * §253 audit-trail postscript — cluster283 §253 sweep (2026-05-29)
 * Classification: LIVE-HOST (android-host) — the launcher Activity, single Compose entry point.
 *
 * LIVE evidence:
 *  - AndroidManifest.xml:40 declares activity android:name=".MainActivity" with the
 *    android.intent.action.MAIN + android.intent.category.LAUNCHER intent-filter (manifest:44-47),
 *    so this is the system launch target — not a stale duplicate.
 *  - exported="true" (manifest:41) and android:theme="@style/Theme.App.Starting" (manifest:43)
 *    match the installSplashScreen() bridge in onCreate (line 20).
 *  - setContent mounts App() from :composeApp/commonMain (verified App.kt:294, fun App()), so this
 *    Activity is a THIN host — zero feature logic lives here; it delegates wholly into the
 *    architecture-rework :composeApp graph.
 *  - Status: LIVE-HOST. No legacy carry-over; the file is already in its fulfilled thin-shell form.
 *
 * Delta-axes (host responsibilities this file owns, each a distinct seam to the rework graph):
 *  1. Splash bridge — installSplashScreen() (line 20) MUST precede super.onCreate() to hand the
 *     launcher theme Theme.App.Starting over to the AndroidX SplashScreen API.
 *  2. Edge-to-edge surface — WindowCompat.setDecorFitsSystemWindows(window, false) makes the window
 *     edge-to-edge so shared Compose owns system-bar insets via Modifier.systemBarsPadding() in :ui.
 *     This mirrors native's exact call (no AndroidX scrim); see the onCreate KDoc for the rationale
 *     vs enableEdgeToEdge().
 *  3. Compose entry — setContent { App() } (lines 23-25) is the ONLY mount point; navigation,
 *     theming, and Koin-scoped ViewModels all resolve below App(), never in this Activity.
 *  4. Lifecycle minimalism — only onCreate is overridden; no onResume/onPause logic, confirming the
 *     host carries no retained state and survives config-change via Compose, not Activity callbacks.
 *  5. No DI here — Koin is started in MyApp.onCreate (the Application), so this Activity never calls
 *     startKoin; koinViewModel() lookups inside App() use the already-running GlobalContext.
 *
 * Nested-comment hazard check: no interior slash-star, star-slash, nor slash-star-star sequences;
 * block delimiters are balanced (opens once, closes once). Diff is purely additive.
 */
