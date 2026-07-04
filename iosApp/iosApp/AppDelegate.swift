import UIKit
import UserNotifications
import BackgroundTasks
import FirebaseCore
import FirebaseCrashlytics
import FirebaseMessaging
import ComposeApp

/// Single greppable prefix for the whole background-download system. Search Xcode console / device
/// logs (Console.app) for `KiraBgDownload` to see Swift + Kotlin lines together. NSLog shows in both.
@inline(__always)
private func bgLog(_ message: String) {
    NSLog("KiraBgDownload | %@", message)
}

/// App delegate for the SwiftUI host. Two jobs:
///
/// 1. Owns the `UNUserNotificationCenter` delegate so the app controls foreground download-notification
///    presentation.
/// 2. Hosts the background-download lifecycle (background-downloads M2–M6): the
///    `handleEventsForBackgroundURLSession` hook for durable transfers, and the two-path BG-task CPU
///    scheduling (`BGContinuedProcessingTask` on iOS 26+, `BGProcessingTask` before 26).
///
/// Dormant unless `DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED` is on — the Kotlin bridge no-ops
/// otherwise. All meaningful steps are logged under the `KiraBgDownload` prefix for the test build.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {

    private let processingTaskId = "me.manga.kira.download.processing"
    private let continuedTaskId = "me.manga.kira.download.continued"
    private let libraryRefreshTaskId = "me.manga.kira.library.refresh"

    /// True from the moment a BGContinuedProcessingTask is *submitted* until it completes/expires
    /// (main-thread only) — i.e. "one is in flight". Submitted, not just running, because the engine can
    /// fire many `workBecamePending` signals in a burst (e.g. queueing several chapters, or instant
    /// retries of 403'd chapters); coalescing on submit keeps exactly ONE Live Activity instead of a
    /// flurry where some flash "failed". Cleared on completion/expiry, and defensively on didBecomeActive
    /// so a submit that never started (raced suspension) can't deadlock future submits.
    private var continuedTaskActive = false

    /// True between `didBecomeActive` and `didEnterBackground` — i.e. the user is actually in the app.
    /// A `BGContinuedProcessingTask` only reliably *starts* from a FOREGROUND submit; submitting one
    /// from the background races suspension, the task never starts, and (worse) it left `continuedTaskActive`
    /// stuck true so every later submit was skipped `alreadyActive`. So continued tasks (Live Activities)
    /// are foreground-only; backgrounded, the queue advances on a `BGProcessingTask` (reliable opportunistic
    /// CPU, no UI — and a Live Activity can't be started in the background anyway). This is NOT
    /// `UIApplication.applicationState`, which reports non-`.active` while a continued task executes even
    /// with the user looking at the app — the lifecycle notifications below track real foreground/background.
    private var appIsForeground = true

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Initialize Firebase before any other startup work — it reads GoogleService-Info.plist from
        // the app bundle and must run before any Firebase API is touched. FirebaseAnalytics starts here.
        FirebaseApp.configure()

        // Crashlytics is RELEASE / TestFlight only. During development (DEBUG) we don't want local
        // crashes cluttering Crashlytics, and the dSYM-upload build phase is skipped for Debug too.
        #if DEBUG
        // Debugging: keep Crashlytics collection OFF (crashes here are expected noise). No Kotlin hook.
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(false)
        #else
        // Release / TestFlight: collect crashes, and install the Kotlin/Native unhandled-exception hook
        // (CrashKiOS) so uncaught Kotlin fatals reach Crashlytics WITH the symbolicated Kotlin stack
        // (see composeApp .../crash/CrashSetup.kt). Both MUST run after FirebaseApp.configure().
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(true)
        CrashSetupKt.setupCrashlytics()
        #endif

        bgLog("lifecycle.launch didFinishLaunching")

        // KiraBgDownload verbosity, enforced by distribution: full info trace for Debug and
        // TestFlight (device-QA builds), OFF for App Store — the pre-ship "flip VERBOSE" checklist
        // item can't be forgotten. (sandboxReceipt = TestFlight/dev-signed; "receipt" = App Store.)
        // warn/error lines are never gated, so production failures stay visible.
        #if DEBUG
        IosBackgroundBridgeKt.setBgDownloadVerboseLogging(enabled: true)
        #else
        let isTestFlight = Bundle.main.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt"
        IosBackgroundBridgeKt.setBgDownloadVerboseLogging(enabled: isTestFlight)
        #endif

        UNUserNotificationCenter.current().delegate = self

        // ---- Firebase Cloud Messaging (push) + In-App Messaging ----
        // FCM: receive the registration token via MessagingDelegate below, and bridge it to Kotlin
        // (PushTokenProvider) through IosPushBridge.
        Messaging.messaging().delegate = self
        // FIAM needs no code here: the FirebaseInAppMessaging SDK (linked via SPM) auto-initialises
        // after FirebaseApp.configure() and displays console-authored campaigns on every screen. The
        // app intentionally does not suppress in-app messages anywhere.
        // Register for remote notifications to obtain the APNs token (FirebaseMessaging maps it to an
        // FCM token). This does NOT show a permission dialog. The user-facing authorization prompt is
        // requested contextually by the onboarding flow (NotificationPermissionRequester), NOT here —
        // iOS shows that prompt only once ever, so asking at launch would preempt onboarding and make
        // its permission step a permanent no-op (#5). Real delivery also needs an APNs key uploaded to
        // the Firebase console + the App ID's Push capability (owner steps); on the simulator this is
        // a no-op path.
        application.registerForRemoteNotifications()

        // BG-task launch handlers MUST be registered before launch completes (for BGProcessingTask).
        BGTaskScheduler.shared.register(forTaskWithIdentifier: processingTaskId, using: nil) { [weak self] task in
            self?.handleProcessingTask(task)
        }
        bgLog("bgtask.registered id=\(processingTaskId) type=BGProcessingTask")
        if #available(iOS 26.0, *) {
            BGTaskScheduler.shared.register(forTaskWithIdentifier: continuedTaskId, using: nil) { [weak self] task in
                self?.handleContinuedTask(task)
            }
            bgLog("bgtask.registered id=\(continuedTaskId) type=BGContinuedProcessingTask iOS26+")
        } else {
            bgLog("bgtask.continued.unavailable iOS<26 fallback=BGProcessingTask")
        }

        // Backlog M2: background library refresh (new-chapter checks while the app is closed).
        // BGAppRefreshTask runs the SAME shared inline-refresh pipeline the manual pull-to-refresh
        // uses (IosLibraryRefreshBridge -> RefreshAllLibraryChaptersUseCase); the handler re-arms
        // the next request first (Apple's re-arming pattern), and expiration cancels the in-flight
        // Kotlin job. Native posture note: the native app ships its periodic worker commented out
        // and KMP-Android schedules none — this is a deliberate iOS forward step, not parity.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: libraryRefreshTaskId, using: nil) { [weak self] task in
            self?.handleLibraryRefreshTask(task)
        }
        bgLog("bgtask.registered id=\(libraryRefreshTaskId) type=BGAppRefreshTask")
        scheduleLibraryRefresh()
        // The engine calls this the moment download work becomes pending — while we are still FOREGROUND.
        // That is the reliable time to submit the BGContinuedProcessingTask (iOS 26+): the system starts
        // it promptly, attaches the Live Activity, and it then CONTINUES across backgrounding. Submitting
        // only at didEnterBackground often loses the race with suspension and the task never starts.
        IosBackgroundBridgeKt.setDownloadProcessingScheduler { [weak self] in
            self?.requestBackgroundDownloadWork(reason: "workPending")
        }

        // SwiftUI's App lifecycle does NOT reliably call the delegate's applicationDidEnterBackground /
        // applicationDidBecomeActive, so observe the notifications directly (these DO fire) to drive
        // BG-task submission. (The Kotlin engine observes the same didEnterBackground notification too.)
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.handleEnteredBackground() }
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            bgLog("lifecycle.didBecomeActive")
            self?.appIsForeground = true
            // Reset the coalesce latch on reopen: any continued task is gone (its Live Activity dismissed),
            // and a submit that raced suspension may have left the latch stuck. A fresh foreground submit
            // (workPending) can then arm a new Live Activity.
            self?.continuedTaskActive = false
            self?.refreshDeviceStress()
        }

        // Device-stress → engine: it defers FOREGROUND CBZ compression while the device is thermally
        // stressed or in Low Power Mode (background compression is unaffected). ProcessInfo's thermalState /
        // isLowPowerModeEnabled aren't in the Kotlin/Native Foundation binding, so the host reads + pushes them.
        NotificationCenter.default.addObserver(
            forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.refreshDeviceStress() }
        NotificationCenter.default.addObserver(
            forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: .main
        ) { [weak self] _ in self?.refreshDeviceStress() }
        refreshDeviceStress()

        IosBackgroundBridgeKt.ensureBackgroundDownloadsReady()
        return true
    }

    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        bgLog("urlsession.handleEvents id=\(identifier) (app relaunched for background transfers)")
        IosBackgroundBridgeKt.handleBackgroundUrlSessionEvents(
            identifier: identifier,
            completionHandler: completionHandler
        )
        // Pages just landed while we were suspended → those chapters are now DOWNLOADED and need a CPU
        // window to finalize (CBZ) and to prepare/start the next chapter (strict chapter-by-chapter). The
        // brief handleEvents window is NOT the place to run Skia, so request a *BGProcessingTask* — the
        // reliable opportunistic background-CPU path. NOT a continued task: this is a pure background wake
        // (no user watching), and a continued task submitted from the background races suspension and
        // usually never starts, which left finalize stranded until the next app reopen. Best-effort — if
        // the OS declines the window, finalize falls back to the next time the app is active.
        if IosBackgroundBridgeKt.hasPendingDownloadWork() {
            bgLog("urlsession.handleEvents.pendingWork=true requesting BGProcessingTask for finalize")
            scheduleProcessingTask(reason: "handleEvents")
        }
    }

    /// Invoked from the `UIApplication.didEnterBackgroundNotification` observer (the delegate's
    /// applicationDidEnterBackground is unreliable under the SwiftUI App lifecycle). Submits the
    /// continued task (iOS 26+) / schedules the opportunistic BGProcessingTask (pre-26) so download
    /// orchestration keeps advancing while backgrounded.
    /// Push current device stress (thermal serious/critical OR Low Power Mode) to the download engine, which
    /// defers FOREGROUND CBZ compression while stressed (background compression is unaffected). Cheap
    /// `ProcessInfo` reads — those APIs aren't in the Kotlin/Native Foundation binding, so the host owns
    /// them. Called on launch, on the thermal/power-state change notifications, and on foreground.
    private func refreshDeviceStress() {
        let info = ProcessInfo.processInfo
        let thermal = info.thermalState
        let stressed = thermal == .serious || thermal == .critical || info.isLowPowerModeEnabled
        bgLog("deviceStress thermal=\(thermal.rawValue) lowPower=\(info.isLowPowerModeEnabled) stressed=\(stressed)")
        IosBackgroundBridgeKt.setDownloadDeviceUnderStress(stressed: stressed)
    }

    private func handleEnteredBackground() {
        appIsForeground = false
        let pending = IosBackgroundBridgeKt.hasPendingDownloadWork()
        bgLog("lifecycle.didEnterBackground hasPendingWork=\(pending)")
        guard pending else { return }
        // Fallback: normally the continued task was already submitted + started at "workPending" while
        // foreground; this covers work that is pending without a prior foreground submit (e.g. relaunch).
        requestBackgroundDownloadWork(reason: "didEnterBackground")
    }

    /// Ask the OS for a background CPU window for download work. iOS 26+ submits a
    /// `BGContinuedProcessingTask` (prompt start + system Live Activity, continues across backgrounding);
    /// before 26 it submits the opportunistic `BGProcessingTask`. Hops to the main thread (the engine may
    /// call this from a background queue) so all `BGTaskScheduler` + state access is serialized there.
    private func requestBackgroundDownloadWork(reason: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if #available(iOS 26.0, *) {
                self.submitContinuedTask(reason: reason)
            } else {
                self.scheduleProcessingTask(reason: reason)
            }
        }
    }

    // MARK: - BGProcessingTask (iOS 13+, opportunistic fallback)

    private func scheduleProcessingTask(reason: String) {
        let request = BGProcessingTaskRequest(identifier: processingTaskId)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        do {
            try BGTaskScheduler.shared.submit(request)
            bgLog("bgtask.processing.submitted reason=\(reason)")
        } catch {
            bgLog("bgtask.processing.submitFailed reason=\(reason) error=\(error.localizedDescription)")
        }
    }

    /// Continuity hop (THE key rule: a Dynamic-Island expiry/finish must never stop the durable queue).
    /// When a continued task ends or expires with download work still pending, submit an opportunistic
    /// `BGProcessingTask` so a later background window can prepare/enqueue not-yet-started chapters and
    /// finalize downloaded ones — WITHOUT a foreground reopen. The file transfers themselves never stop
    /// (they run out-of-process on the background `URLSession`); this only re-arms the CPU orchestration.
    /// Best-effort: the OS decides when (and whether) to grant the window. Main-thread for BGTaskScheduler.
    private func scheduleNextProcessingIfPending(reason: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard IosBackgroundBridgeKt.hasPendingDownloadWork() else {
                bgLog("bgtask.continuity.skip reason=\(reason) pending=false")
                return
            }
            self.scheduleProcessingTask(reason: reason)
        }
    }

    /// Backlog M2: BGAppRefreshTask handler — re-arm, run the shared refresh via the Kotlin
    /// bridge, and settle the task exactly once (the bridge guarantees a single completion call).
    private func handleLibraryRefreshTask(_ task: BGTask) {
        scheduleLibraryRefresh() // re-arm the next window before doing any work
        bgLog("libraryRefresh.begin")
        let cancel = IosLibraryRefreshBridge.shared.run { success in
            // Kotlin (Boolean) -> Unit closures surface the arg as a boxed KotlinBoolean.
            let ok = success.boolValue
            bgLog("libraryRefresh.end success=\(ok)")
            task.setTaskCompleted(success: ok)
        }
        task.expirationHandler = {
            bgLog("libraryRefresh.expired -> cancelling")
            cancel()
        }
    }

    /// Submit the next BGAppRefreshTask request. IMPORTANT: `earliestBeginDate` is a SCHEDULING
    /// REQUEST, not a guaranteed interval — it only promises the task will not run BEFORE that
    /// time; iOS alone decides when (and whether) it actually runs, based on usage patterns,
    /// battery, and Background App Refresh settings. ~12h earliest (owner-tuned 2026-07-02, was
    /// 4h) keeps new-chapter checks roughly twice a day without competing with the download BG
    /// tasks for the system's budget. Duplicate submissions for the same id are coalesced by iOS.
    private func scheduleLibraryRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: libraryRefreshTaskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 12 * 60 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
            bgLog("libraryRefresh.scheduled earliest=+12h (request only — iOS picks the actual time)")
        } catch {
            // Expected in Simulator / when Background App Refresh is disabled by the user.
            bgLog("libraryRefresh.schedule.failed \(error)")
        }
    }

    private func handleProcessingTask(_ task: BGTask) {
        bgLog("bgtask.processing.started")
        // Chain the next opportunistic window ONLY while work remains. The previous unconditional
        // resubmit meant every granted window re-armed another one forever — perpetual background
        // wake-ups long after the queue drained (battery cost + the app looks like a background
        // abuser to the OS scheduler, degrading future grants). Belt: conditional chain here (covers
        // an expiry that kills us before completion runs); braces: conditional chain again at
        // completion (covers work still pending after MAX_BG_CYCLES). Once nothing is pending,
        // neither fires and the chain ends.
        scheduleNextProcessingIfPending(reason: "reschedule")
        task.expirationHandler = {
            bgLog("bgtask.processing.expired")
            IosBackgroundBridgeKt.cancelBackgroundDownloadWork()
        }
        IosBackgroundBridgeKt.runBackgroundDownloadWork(onProgress: { _ in }) { [weak self] in
            bgLog("bgtask.processing.completed")
            self?.scheduleNextProcessingIfPending(reason: "processingCompleted")
            task.setTaskCompleted(success: true)
        }
    }

    // MARK: - BGContinuedProcessingTask (iOS 26+, primary path with a system progress indicator)

    @available(iOS 26.0, *)
    private func submitContinuedTask(reason: String) {
        // Foreground-only: a continued task started from the background never reliably starts (it races
        // suspension) and would leave the coalesce latch stuck. Backgrounded, advance the queue on a
        // BGProcessingTask instead — reliable opportunistic CPU, and a Live Activity can't start in the
        // background anyway. (Submitting the same BGProcessingTask id twice is a no-op, so this is safe
        // even when another path already scheduled one.)
        guard appIsForeground else {
            bgLog("bgtask.continued.skip reason=\(reason) backgrounded → BGProcessingTask")
            scheduleProcessingTask(reason: "\(reason):bg")
            return
        }
        // One in flight at a time. Coalesce on submit (not just on start): a burst of workBecamePending
        // signals — queueing several chapters, or instant 403 retries — would otherwise each submit a
        // Live Activity, and the ones whose chapter fails/finishes immediately flash "failed". (Called
        // only on the main queue, so this check-and-set is atomic.)
        if continuedTaskActive {
            bgLog("bgtask.continued.skip reason=\(reason) alreadyActive")
            return
        }
        let request = BGContinuedProcessingTaskRequest(
            identifier: continuedTaskId,
            title: "Downloading chapters",
            subtitle: "Kira is finishing your downloads"
        )
        do {
            try BGTaskScheduler.shared.submit(request)
            continuedTaskActive = true
            bgLog("bgtask.continued.submitted reason=\(reason)")
        } catch {
            // Continued processing may be unavailable (already pending, or unsupported hardware) —
            // fall back to the opportunistic BGProcessingTask.
            bgLog("bgtask.continued.submitFailed reason=\(reason) error=\(error.localizedDescription) fallback=BGProcessingTask")
            scheduleProcessingTask(reason: "continuedFallback")
        }
    }

    @available(iOS 26.0, *)
    private func handleContinuedTask(_ task: BGTask) {
        guard let continued = task as? BGContinuedProcessingTask else {
            bgLog("bgtask.continued.wrongType")
            task.setTaskCompleted(success: false)
            return
        }
        bgLog("bgtask.continued.started")
        DispatchQueue.main.async { [weak self] in self?.continuedTaskActive = true } // defensive; set at submit
        continued.progress.totalUnitCount = 100

        // Complete the task exactly once, on the main thread, ALWAYS as success. The expiration handler
        // (OS reclaimed our time budget) and the normal completion can otherwise both call
        // setTaskCompleted — and an expiry that leaves progress < 100% makes the system Live Activity show
        // "failed". But an expiry is NOT a failure here: the file transfers are durable on the background
        // URLSession and any half-built CBZ resumes next window/foreground (atomic .part). So on either
        // path we drive progress to 100% and report success, so the Live Activity dismisses as done.
        var taskDone = false
        let finishSuccessfully: () -> Void = { [weak self] in
            DispatchQueue.main.async {
                guard !taskDone else { return }
                taskDone = true
                self?.continuedTaskActive = false
                continued.progress.completedUnitCount = continued.progress.totalUnitCount
                continued.setTaskCompleted(success: true)
            }
        }

        continued.expirationHandler = {
            bgLog("bgtask.continued.expired (durable hand-off; transfers keep running, queue resumes next window)")
            // Cancels ONLY the orchestration loop — never the background URLSession transfers (they
            // continue out-of-process) and never any chapter state (nothing is marked failed on expiry).
            IosBackgroundBridgeKt.cancelBackgroundDownloadWork()
            // Re-arm a background window so the queue keeps advancing without a foreground reopen.
            self.scheduleNextProcessingIfPending(reason: "continuedExpired")
            finishSuccessfully()
        }
        // One-chapter-at-a-time: this task represents only the lead chapter and completes at its safe
        // checkpoint (finalize.success), NOT the whole queue. Progress is that chapter's transfer %.
        IosBackgroundBridgeKt.runContinuedChapterBatch(
            onProgress: { fraction in
                // Kotlin function-type args are boxed across the ObjC bridge → unwrap KotlinFloat.
                let pct = fraction.floatValue
                bgLog("bgtask.continued.progress pct=\(Int(pct * 100))")
                DispatchQueue.main.async {
                    continued.progress.completedUnitCount = Int64(pct * 100)
                }
            },
            completion: {
                bgLog("bgtask.continued.completed")
                // The rest of the queue continues across later windows regardless of this Live Activity:
                // (a) a background BGProcessingTask if work is still pending, and
                self.scheduleNextProcessingIfPending(reason: "continuedFinishedPending")
                finishSuccessfully()
                // (b) a FRESH Live Activity for the next chapter, if real transfer work remains. We are in
                // the completing task's execution context (we still have CPU) so this can start the next
                // one even though the app is not `.active` during continued-task execution; the coalesce
                // latch (cleared just above) lets exactly this one through.
                self.submitNextContinuedIfTransferWork()
            }
        )
    }

    /// One-chapter-at-a-time re-arm: after a chapter's Live Activity finishes, start the next chapter's.
    /// Gated on `hasTransferWork` so a tail of already-transferred chapters awaiting only finalize never
    /// busy-loops submits. The foreground check lives in `submitContinuedTask`: if we're still foreground
    /// (user watching), the next chapter gets its own Live Activity; if backgrounded, that call falls back
    /// to a BGProcessingTask (and `scheduleNextProcessingIfPending` already chained one), so the remainder
    /// advances either way — no phantom background Live Activity that can't start.
    @available(iOS 26.0, *)
    private func submitNextContinuedIfTransferWork() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard IosBackgroundBridgeKt.hasTransferWork() else {
                bgLog("bgtask.continued.next.skip reason=noTransferWork")
                return
            }
            self.submitContinuedTask(reason: "nextChapter")
        }
    }

    // MARK: - Foreground notification presentation (download progress vs completion)

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // Remote FCM push arriving while the app is foregrounded → show it (banner + sound). Local
        // download notifications keep their existing per-category presentation below.
        if notification.request.trigger is UNPushNotificationTrigger {
            bgLog("notif.willPresent remotePush present=banner+list+sound")
            completionHandler([.banner, .list, .sound])
            return
        }
        let category = notification.request.content.categoryIdentifier
        switch category {
        case "DOWNLOAD_PROGRESS":
            bgLog("notif.willPresent category=DOWNLOAD_PROGRESS present=silent(list)")
            completionHandler([.list])              // silent: Notification Center only
        case "DOWNLOAD_DONE":
            bgLog("notif.willPresent category=DOWNLOAD_DONE present=banner+list+sound")
            completionHandler([.banner, .list, .sound])
        default:
            completionHandler([])                   // suppress other notifications in foreground
        }
    }

    // MARK: - Notification tap → deep link

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        // A tapped notification (remote or local). Forward its userInfo to Kotlin, which parses a
        // deep link from the FCM data payload and routes it (NotificationRouter → nav host). A payload
        // with no valid deep link is ignored, so a plain download-notification tap just opens the app.
        bgLog("notif.didReceive tap categoryId=\(response.notification.request.content.categoryIdentifier)")
        IosPushBridgeKt.onNotificationTap(userInfo: response.notification.request.content.userInfo)
        completionHandler()
    }

    // MARK: - APNs registration → FCM

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        // Hand the APNs device token to FirebaseMessaging, which exchanges it for an FCM token
        // (delivered via messaging(_:didReceiveRegistrationToken:) below).
        bgLog("push.apns.registered tokenBytes=\(deviceToken.count)")
        Messaging.messaging().apnsToken = deviceToken
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        // Expected on the simulator / before the APNs key + App ID capability are wired — non-fatal.
        bgLog("push.apns.registerFailed error=\(error.localizedDescription)")
    }

    // MARK: - MessagingDelegate

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        // Bridge the FCM registration token into Kotlin's PushTokenBroadcaster (feeds
        // PushTokenProvider). Fires on first registration and on every rotation.
        bgLog("push.fcm.token \(fcmToken != nil ? "received" : "nil")")
        IosPushBridgeKt.onPushToken(token: fcmToken)
    }
}
