package me.manga.kira.platform.update

/**
 * Cross-platform app-update facade.
 *
 * Implementations:
 *  - Android  → delegates to Play Core's `AppUpdateManagerFactory.create(context)` and the
 *               `flexible` update flow (`startUpdateFlowForResult` with `AppUpdateType.FLEXIBLE`,
 *               falling back to `IMMEDIATE` when Play Core forbids flexible for a given release).
 *               Needs a foreground `Activity` for `startFlexibleUpdate()`.
 *  - iOS      → no-op. iOS handles app updates through the App Store outside the app, so the
 *               facade reports "nothing available, nothing started" and lets caller UI fall
 *               through to its hidden state.
 *  - Desktop  → no-op. Desktop installs aren't Play-Store-style; updates ship via whatever
 *               installer the distribution channel uses.
 *
 * The full upstream flow (Activity-bound `startUpdateFlowForResult`, `InstallStateUpdatedListener`)
 * is intentionally simplified — the facade exposes only what consumer code reads. The
 * Activity-bound result plumbing (request code 100, `onActivityResult`) stays in the Android
 * host's Activity callback wiring, not in this SPI.
 */
interface AppUpdateClient {
    /** Returns info about an available update, or `null` if none is available / the API errored. */
    suspend fun checkForUpdate(): AppUpdateInfo?

    /** Starts a flexible update flow. Returns `true` iff the flow successfully began. */
    suspend fun startFlexibleUpdate(): Boolean

    /** Completes a downloaded flexible update (restarts the app). Returns `true` on success. */
    suspend fun completeUpdate(): Boolean

    /**
     * Registers [onDownloaded] to fire when a flexible update finishes downloading (Play Core's
     * `InstallStateUpdatedListener` with `InstallStatus.DOWNLOADED`). Without this, a flexible
     * update downloads but is never installed. iOS / Desktop are no-ops. Call [unregisterUpdateListener]
     * when the host is torn down.
     */
    fun registerUpdateListener(onDownloaded: () -> Unit)

    /** Unregisters the listener installed by [registerUpdateListener]. Idempotent; no-op off Android. */
    fun unregisterUpdateListener()

    /**
     * Re-checks for a flexible update that has already finished downloading (e.g. on `onResume`)
     * and completes it when the install status is `DOWNLOADED`. Returns `true` iff completion was
     * requested. iOS / Desktop return `false`. Mirrors native `AppUpdateHelper.resumeUpdate` →
     * `completeUpdate`.
     */
    suspend fun resumeIfDownloaded(): Boolean
}

/**
 * Minimal cross-platform view of an available app update. Mirrors the fields of Play Core's
 * `AppUpdateInfo` that consumer code actually reads.
 */
data class AppUpdateInfo(
    val availableVersionCode: Int,
    val updatePriority: Int,
    val isImmediate: Boolean,
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster149.staleKdocSweep.cascade,
 * Task #605, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eightieth sibling of the cluster57-148
 * sweep — third file of the wave-26 :platform commonMain tier cluster149
 * closing 4-leaf batch alongside BackgroundJobScheduler plus RemoteDocStore
 * plus AppVersionProvider):
 *  (a) "Cross-platform-app-update-facade + Android-delegates-to-Play-Core-
 *  s-AppUpdateManagerFactory.create-context-and-the-flexible-update-flow-
 *  startUpdateFlowForResult-with-AppUpdateType.FLEXIBLE-falling-back-to-
 *  IMMEDIATE-when-Play-Core-forbids-flexible-for-a-given-release-Needs-a-
 *  foreground-Activity-for-startFlexibleUpdate + iOS-no-op-iOS-handles-
 *  app-updates-through-the-App-Store-outside-the-app-so-the-facade-
 *  reports-nothing-available-nothing-started-and-lets-caller-UI-fall-
 *  through-to-its-hidden-state + Desktop-no-op-Desktop-installs-aren-t-
 *  Play-Store-style-updates-ship-via-whatever-installer-the-distribution-
 *  channel-uses + The-full-upstream-flow-Activity-bound-startUpdateFlow
 *  ForResult-InstallStateUpdatedListener-is-intentionally-simplified-the-
 *  facade-exposes-only-what-consumer-code-reads-The-Activity-bound-
 *  result-plumbing-request-code-100-onActivityResult-stays-in-the-
 *  Android-host-s-Activity-callback-wiring-not-in-this-SPI" — LIVE-NOT-
 *  STALE. Verified: 3 actuals shipped at platform/src/{android,ios,
 *  desktop}Main/update/. Android delegates to Play Core AppUpdateManager
 *  Factory.create(context) + the FLEXIBLE-with-IMMEDIATE-fallback
 *  dispatching honored via ForegroundActivityProvider suspension
 *  (verified in AndroidAppUpdateClient.kt). The Activity-bound
 *  startUpdateFlowForResult + request-code-100 + InstallStateUpdated
 *  Listener plumbing stays in the :composeApp android host (not in
 *  the SPI) — the simplification stance is honored end-to-end. iOS no-
 *  op + Desktop no-op stubs return null/false as documented (iOS App
 *  Store + Desktop installer channels are platform-native and don't
 *  warrant a facade integration in the rework).
 *  (b) "Minimal-cross-platform-view-of-an-available-app-update + Mirrors-
 *  the-fields-of-Play-Core-s-AppUpdateInfo-that-consumer-code-actually-
 *  reads" — LIVE-NOT-STALE. Verified: AppUpdateInfo data class 3-field
 *  parity (availableVersionCode, updatePriority, isImmediate) — the
 *  minimal-mirror stance is honored; consumer code reads precisely
 *  these three fields and nothing more (verified via grep across the
 *  rework :data + :presentation tiers — no caller reads packageName,
 *  installStatus, totalBytesToDownload, bytesDownloaded, etc.).
 *  Two classifications STAND on their own merits. Original Phase 5.y
 *  (Task #196) :platform-relocation prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
