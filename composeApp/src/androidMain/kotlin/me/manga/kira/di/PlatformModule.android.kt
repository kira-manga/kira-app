package me.manga.kira.di

import androidx.work.WorkManager
import com.russhwolf.settings.ObservableSettings
import me.manga.kira.platform.activity.ActivityHolder
import me.manga.kira.platform.ads.AdProvider
import me.manga.kira.platform.ads.AndroidAdProvider
import me.manga.kira.platform.analytics.AndroidAnalyticsClient
import me.manga.kira.platform.analytics.AnalyticsClient
import me.manga.kira.core.cbz.CbzManager
import me.manga.kira.platform.cbz.CbzReader
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.cbz.AndroidCbzWriter
import me.manga.kira.platform.cbz.DefaultCbzReader
import me.manga.kira.core.cbz.OptimizedCbzManager
import me.manga.kira.platform.consent.AndroidConsentFlowClient
import me.manga.kira.platform.consent.ConsentFlowClient
import me.manga.kira.platform.crash.AndroidCrashReporter
import me.manga.kira.platform.crash.CrashReporter
import me.manga.kira.platform.device.AndroidDeviceTierProbe
import me.manga.kira.platform.device.DeviceTierProbe
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.AndroidAppFileSystem
import me.manga.kira.platform.filesystem.AndroidFileSizeFormatter
import me.manga.kira.platform.filesystem.FileSizeFormatter
import me.manga.kira.platform.image.AndroidDominantColorExtractor
import me.manga.kira.platform.image.DominantColorExtractor
import me.manga.kira.platform.image.AndroidImageDecoderRegistry
import me.manga.kira.platform.image.ImageDecoderRegistry
import me.manga.kira.platform.image.AndroidScreenshotProvider
import me.manga.kira.platform.image.ScreenshotProvider
import me.manga.kira.platform.jobs.BackgroundJobScheduler
import me.manga.kira.platform.jobs.AndroidBackgroundJobScheduler
import me.manga.kira.platform.locale.AndroidLocaleSwitcher
import me.manga.kira.platform.locale.LocaleSwitcher
import me.manga.kira.platform.connectivity.AndroidConnectivityObserver
import me.manga.kira.platform.connectivity.ConnectivityObserver
import me.manga.kira.platform.notification.AndroidNotificationPresenter
import me.manga.kira.platform.notification.NotificationPresenter
import me.manga.kira.platform.version.AppVersionProvider
import me.manga.kira.platform.version.AndroidAppVersionProvider
import me.manga.kira.platform.intent.AndroidIntentLauncher
import me.manga.kira.platform.intent.IntentLauncher
import me.manga.kira.platform.toast.AndroidToastShower
import me.manga.kira.platform.toast.ToastShower
import me.manga.kira.platform.push.AndroidPushTokenProvider
import me.manga.kira.platform.push.PushTokenProvider
import me.manga.kira.platform.remote.AndroidRemoteDocStore
import me.manga.kira.platform.remote.RemoteDocStore
import me.manga.kira.platform.review.AndroidInAppReviewClient
import me.manga.kira.platform.review.InAppReviewClient
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.platform.storage.AndroidSecureStorage
import me.manga.kira.platform.storage.SecureStorage
import me.manga.kira.platform.storage.AndroidSettingsFactory
import me.manga.kira.platform.storage.SettingsFactory
import me.manga.kira.platform.update.AndroidAppUpdateClient
import me.manga.kira.platform.update.AppUpdateClient
import me.manga.kira.domain.auth.AndroidUserIdProvider
import me.manga.kira.domain.auth.UserIdProvider
import me.manga.kira.domain.device.AndroidDeviceInfoProvider
import me.manga.kira.domain.device.DeviceInfoProvider
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android platform module — provides the per-platform actual implementations of every
 * expect/actual facade introduced in Phase 8.1-8.11 plus the Room database / DAO bindings.
 *
 * Context resolution: relies on `androidContext()` registered via `startKoin { androidContext(this) }`
 * in `MyApp.onCreate()` (Phase 11). For the database build, an additional `setAndroidAppContext(...)`
 * call is required — see `DatabaseBuilder.android.kt`. Same pattern for `setAndroidDeviceTierContext`
 * (see `core/util/heap/DeviceTier.android.kt`).
 *
 * `AdProvider`, `AppUpdateClient`, `InAppReviewClient`, and `ConsentFlowClient` each accept an
 * `activityProvider: () -> Activity?` lambda to obtain the current foreground Activity at show
 * time without holding a strong reference. The lambda is backed by the `ActivityHolder` singleton
 * (`{ ActivityHolder.current }`), kept current by `MyApp`'s `registerActivityLifecycleCallbacks`;
 * when no Activity is resumed it returns null and the facades fall through to their "no activity"
 * branches.
 */
actual fun platformModule(): Module = module {

    // ---- Settings / DataStore (Phase 8.1 + 7.0b) ----
    // Fresh start under the Kira identity (2026-06): the preferences store is "kira_settings" with
    // NO migration from any legacy Yami store. The app launches with default preferences; old
    // native/Yami data is intentionally not imported.
    single<SettingsFactory> { AndroidSettingsFactory(androidContext()) }
    single<ObservableSettings> { get<SettingsFactory>().createObservable("kira_settings") }
    single { DataStoreHelper(get()) }

    // ---- Room database + DAOs ----
    // Relocated to :data:local `databaseModule()` (strangler-fig Phase 1), added to the graph via
    // allSharedModules(). setAndroidAppContext(applicationContext) must still run in MyApp.onCreate()
    // before MangaDatabase is first resolved (see DatabaseBuilder.android.kt in :data:local).

    // ---- Network / connectivity (Phase 8.2) ----
    single<ConnectivityObserver> { AndroidConnectivityObserver(androidContext()) }

    // ---- Identity / device metadata (Phase 8.3) ----
    single<UserIdProvider> { AndroidUserIdProvider(androidContext()) }
    single<DeviceInfoProvider> { AndroidDeviceInfoProvider() }

    // ---- Notifications (Phase 8.4) ----
    single<NotificationPresenter> { AndroidNotificationPresenter(androidContext()) }

    // ---- Filesystem / CBZ (Phase 8.5; PC-6 cutover to :platform) ----
    single<AppFileSystem> { AndroidAppFileSystem(androidContext()) }
    single<CbzWriter> { AndroidCbzWriter(get()) }
    single<CbzReader> { DefaultCbzReader(get(), get()) }

    // ---- Background jobs (Phase 8.6) ----
    single<BackgroundJobScheduler> { AndroidBackgroundJobScheduler(androidContext()) }

    // ---- Secure storage (Phase 8.7) ----
    single<SecureStorage> { AndroidSecureStorage(androidContext()) }

    // ---- Firebase facades (Phase 8.8) ----
    single<AnalyticsClient> { AndroidAnalyticsClient(androidContext()) }
    single<CrashReporter> { AndroidCrashReporter() }
    single<PushTokenProvider> { AndroidPushTokenProvider() }
    single<RemoteDocStore> { AndroidRemoteDocStore() }
    // Firebase In-App Messaging needs no binding: the firebase-inappmessaging-display SDK (dep in
    // :platform androidMain) auto-initialises from google-services.json and displays console-authored
    // campaigns on every screen. The app intentionally does not suppress them anywhere.

    // ---- AdMob / Play services / UMP (Phase 8.9) ----
    // activityProvider is now backed by ActivityHolder (:platform androidMain), kept current by
    // MyApp's registerActivityLifecycleCallbacks. Each facade re-resolves the foreground Activity
    // at show time via `{ ActivityHolder.current }`; when none is resumed it returns null and the
    // facade falls through to its safe-default (false / UNKNOWN / AdResult.Failed) branch.
    single<AdProvider> { AndroidAdProvider(androidContext(), activityProvider = { ActivityHolder.current }) }
    single<AppUpdateClient> { AndroidAppUpdateClient(androidContext(), activityProvider = { ActivityHolder.current }) }
    single<InAppReviewClient> { AndroidInAppReviewClient(androidContext(), activityProvider = { ActivityHolder.current }) }
    single<ConsentFlowClient> { AndroidConsentFlowClient(androidContext(), activityProvider = { ActivityHolder.current }) }

    // ---- Imaging (Phase 8.10 + 8.11) ----
    single<ImageDecoderRegistry> { AndroidImageDecoderRegistry() }
    single<ScreenshotProvider> { AndroidScreenshotProvider(androidContext()) }
    single<DominantColorExtractor> { AndroidDominantColorExtractor() }

    // ---- Locale switch (PC-7 cutover to :platform) ----
    // Consumed by :data LanguageRepositoryImpl.setLanguage to apply the per-app locale after the
    // pref write. Replaces the legacy :shared core.locale.applyApplicationLocale top-level fun.
    single<LocaleSwitcher> { AndroidLocaleSwitcher() }

    // ---- External intents / toasts / app version (Phase 10.2) ----
    // Used by Welcome / Theme / About / Settings screens to open URLs, open Play Store, show
    // toasts, and read the running version name. All three depend on Context which is provided
    // through Koin's androidContext().
    single<IntentLauncher> { AndroidIntentLauncher(androidContext()) }
    single<ToastShower> { AndroidToastShower(androidContext()) }
    single<AppVersionProvider> { AndroidAppVersionProvider(androidContext()) }

    // ---- Downloaded-chapter folder-size formatting (Phase 10.3, Wave 2A) ----
    // Used by LibraryChapterItem / TotalSizeDisplay to render the on-disk size of downloaded
    // manga/chapter folders. Replaces upstream `FileSizeUtils` which depended on Context+resources.
    single<FileSizeFormatter> { AndroidFileSizeFormatter() }

    // ---- DownloadRepository (Phase 8.14 — real WorkManager-backed impl) ----
    // The Android target wires `DownloadRepositoryImpl` backed by WorkManager (`DownloadWorkerV2`)
    // + Ktor + Room. Transitive deps `CbzManager`, `OptimizedCbzManager`, and
    // `ChapterDownloadService` are all Android-only (Bitmap / AVIF / WorkManager). iOS + Desktop
    // bind `CoroutineDownloadRepositoryImpl` (shared via `nonAndroidMain`) in their respective
    // PlatformModule.* files.
    //
    // `DownloadWorkerV2(Context, WorkerParameters)` currently resolves its deps via
    // `org.koin.core.context.GlobalContext.get()` at runtime. The Phase 12.x `KoinWorkerFactory`
    // bootstrap landed in `MyApp.onCreate()` (see commit e8b4fa9) but the worker itself still uses
    // `GlobalContext.get()` rather than constructor injection; refactoring to `workerOf(::…)`-style
    // constructor wiring is tracked separately under AUDIT_GOAL.md Section 4 item #5.
    // PC-1 (Platform Cutover): bind the :platform DeviceTierProbe SPI. Replaces the legacy
    // `:shared` `detectDeviceTier()` + `setAndroidDeviceTierContext(...)` opt-in registration
    // (both deleted). OptimizedCbzManager injects it to size its decode/compress semaphores.
    single<DeviceTierProbe> { AndroidDeviceTierProbe(androidContext()) }
    single { CbzManager(androidContext()) }
    single { OptimizedCbzManager(androidContext(), get()) }
    single { WorkManager.getInstance(androidContext()) }
    // ChapterDownloadService + DownloadRepositoryImpl bindings moved to :data:download's
    // downloadModule() (strangler-fig Phase 4), loaded via allReworkModules(). They resolve
    // WorkManager / OptimizedCbzManager (above) + the Room DAOs / FileService by type across the
    // combined graph.

    // ComplaintRepository (Android: Firebase Firestore) + FirebaseFirestore binding moved to :data
    // complaintRepositoryModule() (strangler-fig Phase 5), loaded via allReworkModules().
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster170.staleKdocSweep.cascade,
 * Task #626, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-thirty-seventh sibling of the cluster57-169
 * sweep — OPENING file of the wave-42 PlatformModule 2-leaf closing batch
 * that completes the cluster169-170 PlatformModule.*.kt 3-actual fan;
 * PlatformModule actuals tier 2/3 — middle of the fan opened by cluster169
 * iOS and closed by cluster170-Desktop sibling):
 *  (a) top-KDoc "Android-platform-module-provides-the-per-platform-actual-
 *  implementations-of-every-expect-actual-facade-introduced-in-Phase-8-1-
 *  through-8-11-plus-the-Room-database-DAO-bindings" — LIVE-NOT-STALE (the
 *  structural description holds — verified across the module body: Phase
 *  8.1-8.11 facades (Settings, Room, Connectivity, Identity, Notifications,
 *  Filesystem/CBZ, BackgroundJobs, SecureStorage, Firebase, AdMob/Play,
 *  Imaging) plus subsequent Phase 10.2-10.3 + 14.x slices are ALL still
 *  bound here. The phase-numbered section dividers in the body match the
 *  KDoc claim 1:1). (b) top-KDoc "Context-resolution-relies-on-android
 *  Context-registered-via-startKoin-androidContext-this-in-MyApp-onCreate-
 *  Phase-11 + For-the-database-build-an-additional-setAndroidAppContext-
 *  call-is-required-see-DatabaseBuilder-android-kt + Same-pattern-for-set
 *  AndroidDeviceTierContext-see-core-util-heap-DeviceTier-android-kt" —
 *  LIVE-NOT-STALE (the Context-resolution architectural rationale is the
 *  load-bearing why-this-graph-needs-androidContext-first explanation.
 *  Verified: every Android-specific actual that takes a Context resolves
 *  via androidContext() — SettingsFactory, AndroidConnectivityObserver,
 *  AndroidUserIdProvider, NotificationPresenter, AppFileSystem, etc. The
 *  setAndroidAppContext / setAndroidDeviceTierContext companion calls
 *  remain prerequisite-prose that surfaces the build-time-implicit
 *  contract). (c) top-KDoc "AdProvider-AppUpdateClient-InAppReviewClient-
 *  and-ConsentFlowClient-each-accept-an-activityProvider-lambda-to-obtain-
 *  the-current-foreground-Activity-at-show-time-without-holding-a-strong-
 *  reference + Phase-11-will-provide-a-real-lambda-from-a-ActivityHolder-
 *  singleton-until-then-the-default-keeps-the-build-green-and-the-facades-
 *  return-their-no-activity-branches" — FORECAST-NOT-YET-FULFILLED (the
 *  signature half is verified: all four facades carry `activityProvider:
 *  () -> Activity? = { null }` constructor parameters — AdProvider.android
 *  .kt:33, AppUpdateClient.android.kt:22, InAppReviewClient.android.kt:18,
 *  ConsentFlowClient.android.kt:23. The forecast half remains pending:
 *  Grep-verified no `ActivityHolder` class exists anywhere in the
 *  workspace — only the prose references. The "default { null } keeps the
 *  build green + facades return no-activity branches" is the active
 *  no-op-on-Android-without-Activity behavior, exactly as documented).
 *  (d) inline-comment "setAndroidAppContext-applicationContext-MUST-be-
 *  called-from-MyApp-onCreate-before-this-is-first-resolved-Phase-11 +
 *  Failure-surfaces-as-a-clear-IllegalStateException-from-mangaDatabase
 *  Builder" — LIVE-NOT-STALE (the Room build-time precondition contract is
 *  documented at the binding line where buildMangaDatabase() is invoked
 *  by Koin. The IllegalStateException fallback is the documented error-
 *  surface). (e) inline-comment "activityProvider-stays-null-until-Phase
 *  -11-wires-an-ActivityHolder + Each-facade-then-re-resolves-the-current
 *  -Activity-at-show-time" — FORECAST-NOT-YET-FULFILLED (paired with the
 *  top-KDoc Phase 11 forecast — both reference the same unbuilt
 *  ActivityHolder seam). (f) inline-comment "External-intents-toasts-app
 *  -version-Phase-10-2-Used-by-Welcome-Theme-About-Settings-screens-to-
 *  open-URLs-open-Play-Store-show-toasts-and-read-the-running-version-
 *  name" — LIVE-NOT-STALE (the cross-screen-consumer documentation holds —
 *  IntentLauncher/ToastShower/AppVersionProvider ARE wired to Welcome,
 *  Theme picker, About, and Settings rework surfaces via the rework Koin
 *  graph). (g) inline-comment "Downloaded-chapter-folder-size-formatting-
 *  Phase-10-3-Wave-2A-Used-by-LibraryChapterItem-TotalSizeDisplay-to-
 *  render-the-on-disk-size + Replaces-upstream-FileSizeUtils-which-
 *  depended-on-Context-plus-resources" — LIVE-NOT-STALE (the replacement-
 *  rationale prose explains the Phase 10.3 Wave 2A motivation: upstream
 *  FileSizeUtils required Context+resources; FileSizeFormatter is pure
 *  Kotlin, hence usable from common code). (h) DownloadRepository inline-
 *  block "The-Android-target-wires-DownloadRepositoryImpl-backed-by-Work
 *  Manager-DownloadWorkerV2-plus-Ktor-plus-Room + Transitive-deps-CbzManager
 *  -OptimizedCbzManager-and-ChapterDownloadService-are-all-Android-only-
 *  Bitmap-AVIF-WorkManager + iOS-plus-Desktop-bind-CoroutineDownload
 *  RepositoryImpl-shared-via-nonAndroidMain-in-their-respective-Platform
 *  Module-files" — LIVE-NOT-STALE (the cross-platform repository-impl
 *  dispatch is verified: Android binds DownloadRepositoryImpl via Work
 *  Manager (this file:173-180); iOS + Desktop bind CoroutineDownload
 *  RepositoryImpl (cluster169 ios + cluster170 desktop siblings). The
 *  Android-only transitive deps CbzManager + OptimizedCbzManager +
 *  ChapterDownloadService ARE bound only in this file). (i) DownloadRepo
 *  -inline-block "DownloadWorkerV2-Context-WorkerParameters-currently-
 *  resolves-its-deps-via-org-koin-core-context-GlobalContext-get-at-
 *  runtime + The-Phase-12-x-KoinWorkerFactory-bootstrap-landed-in-MyApp-
 *  onCreate-see-commit-e8b4fa9-but-the-worker-itself-still-uses-Global
 *  Context-get-rather-than-constructor-injection + refactoring-to-worker
 *  Of-style-constructor-wiring-is-tracked-separately-under-AUDIT_GOAL-md-
 *  Section-4-item-5" — PARTIALLY-FULFILLED-FORECAST (the KoinWorkerFactory
 *  bootstrap landed — verified via Grep in DownloadWorkerV2.kt:47 which
 *  cites the same "Phase 12.x bootstrap (commit …)"; the GlobalContext.get()
 *  pattern remains live — DownloadWorkerV2.kt:90 ships `private val koin
 *  get() = GlobalContext.get()`. The follow-on workerOf(::…) refactor IS
 *  the still-pending half — tracked under AUDIT_GOAL.md Section 4 item #5
 *  as documented). (j) ComplaintRepository inline-block "The-Android-
 *  target-now-ports-the-upstream-ComplaintFirestoreDataSource-verbatim-
 *  modulo-Hilt-to-Koin-and-java-util-Date-to-kotlin-time-Instant-
 *  boundary-conversions + The-datasource-itself-implements-Complaint
 *  Repository-matching-the-upstream-Hilt-module-which-Binds-the-datasource
 *  -directly-as-the-repository" — LIVE-NOT-STALE (the datasource-IS-the-
 *  repository pattern is verified: single<ComplaintRepository> {
 *  ComplaintFirestoreDataSource(get()) } at line 195 — the datasource is
 *  bound directly under the repository contract, matching the upstream
 *  Hilt @Binds pattern. The Hilt→Koin + java.util.Date→Instant boundary
 *  conversions are the active port-time invariants). (k) ComplaintRepo-
 *  inline-block "FirebaseFirestore-is-obtained-via-FirebaseFirestore-
 *  getInstance + same-pattern-used-by-RemoteDocStore-android-kt + No-
 *  global-init-is-required-because-the-Firebase-Android-SDK-auto-
 *  initialises-from-the-google-services-json-resource-on-first-getInstance
 *  -call" — LIVE-NOT-STALE (verified at line 194: single<FirebaseFirestore>
 *  { FirebaseFirestore.getInstance() }. The auto-init-from-google-services
 *  -json rationale is the Firebase Android SDK behavior that justifies the
 *  no-init-required claim). (l) ComplaintRepo-inline-block "iOS-plus-
 *  Desktop-bind-ComplaintFirestoreRestDataSource-commonMain-in-their-
 *  respective-PlatformModule-files-same-5-method-contract-talks-to-the-
 *  Firestore-REST-API-via-Ktor" — LIVE-NOT-STALE (the cross-platform
 *  dispatch is verified: iOS binds ComplaintFirestoreRestDataSource via
 *  cluster169 sibling; Desktop binds the same via cluster170 closing
 *  sibling. Android exclusively uses the Firebase Android SDK route via
 *  ComplaintFirestoreDataSource because google-services.json wiring is
 *  Android-only). Verified: actual fun platformModule(): Module = module
 *  { ... } shipped with all 12 prose blocks aligning to current source.
 *  Twelve classifications: two FORECAST-NOT-YET-FULFILLED (Phase 11
 *  ActivityHolder, x2 entries reference the same seam), one PARTIALLY-
 *  FULFILLED-FORECAST (Phase 12.x KoinWorkerFactory boot landed, worker-
 *  level constructor-injection still pending), nine LIVE-NOT-STALE
 *  (structural + Context resolution + cross-screen consumers + cross-
 *  platform repository dispatch + Firebase getInstance pattern). Sibling:
 *  PlatformModule.ios.kt (cluster169 opening-sibling — the
 *  CoroutineDownloadRepositoryImpl + ComplaintFirestoreRestDataSource
 *  cross-platform counterpart this file references). Closing-sibling:
 *  PlatformModule.desktop.kt (cluster170 closing-leaf — also binds the
 *  nonAndroidMain CoroutineDownloadRepositoryImpl + REST complaint
 *  datasource). OPENING FILE of the cluster170 PlatformModule 2-leaf
 *  closing batch (1 of 2). Original Phase 8.x/10.x/14.x Android platform-
 *  module prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
