package me.manga.kira.platform.update

import android.content.Context
import co.touchlab.kermit.Logger
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import me.manga.kira.platform.activity.ForegroundActivityProvider

/**
 * Android actual for [AppUpdateClient].
 *
 * Delegates to Play Core's `AppUpdateManager`. `startFlexibleUpdate()` requires a foreground
 * `Activity` to host the Play Store consent dialog — the [activityProvider] is a
 * [ForegroundActivityProvider] and follows the same convention as `AndroidInAppReviewClient`.
 *
 * Verbatim semantic port from legacy
 * `:shared/androidMain/.../core/update/AppUpdateClient.android.kt`. Preserves:
 *  - `applicationContext` unwrap (avoids retaining Activity in the manager singleton).
 *  - "Prefer flexible; fall back to immediate" availability logic in [checkForUpdate].
 *  - `REQUEST_CODE = 100` for `startUpdateFlowForResult` (Play Core surfaces the result through
 *    the Activity's `onActivityResult` — host wiring depends on this exact value).
 *  - "Return false on any throw" success semantics across all three methods.
 */
class AndroidAppUpdateClient(
    context: Context,
    private val activityProvider: ForegroundActivityProvider = { null },
) : AppUpdateClient {

    private val log = Logger.withTag(TAG)
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(context.applicationContext)

    @Volatile
    private var installListener: InstallStateUpdatedListener? = null

    override suspend fun checkForUpdate(): AppUpdateInfo? {
        return try {
            val info = manager.appUpdateInfo.await()
            if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return null
            val isImmediate = !info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) &&
                info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            AppUpdateInfo(
                availableVersionCode = info.availableVersionCode(),
                updatePriority = info.updatePriority(),
                isImmediate = isImmediate,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "checkForUpdate failed" }
            null
        }
    }

    override suspend fun startFlexibleUpdate(): Boolean {
        val activity = activityProvider() ?: run {
            log.w { "startFlexibleUpdate: no foreground Activity available" }
            return false
        }
        return try {
            val info = manager.appUpdateInfo.await()
            if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return false
            if (!info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) return false
            manager.startUpdateFlowForResult(
                info,
                activity,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                REQUEST_CODE,
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "startFlexibleUpdate failed" }
            false
        }
    }

    override suspend fun completeUpdate(): Boolean {
        return try {
            manager.completeUpdate().await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "completeUpdate failed" }
            false
        }
    }

    override fun registerUpdateListener(onDownloaded: () -> Unit) {
        // Idempotent: replace any prior listener so repeated registration can't leak one.
        unregisterUpdateListener()
        val listener = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                onDownloaded()
            }
        }
        try {
            manager.registerListener(listener)
            installListener = listener
        } catch (e: Exception) {
            log.w(e) { "registerUpdateListener failed" }
        }
    }

    override fun unregisterUpdateListener() {
        val listener = installListener ?: return
        installListener = null
        try {
            manager.unregisterListener(listener)
        } catch (e: Exception) {
            log.w(e) { "unregisterUpdateListener failed" }
        }
    }

    override suspend fun resumeIfDownloaded(): Boolean {
        return try {
            val info = manager.appUpdateInfo.await()
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                manager.completeUpdate().await()
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "resumeIfDownloaded failed" }
            false
        }
    }

    private companion object {
        const val TAG = "AppUpdateClient.android"
        const val REQUEST_CODE = 100
    }
}

/*
 * §253 audit-trail postscript — cluster276 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE-INTERFACE-CONTRACT (Android actual, binding pending).
 *
 * This is the Android leaf of the 3-actual AppUpdateClient platform-facade fan
 * (Phase 5.z.2 relocation, Task #189). The shared commonMain interface
 * me.manga.kira.platform.update.AppUpdateClient and its AppUpdateInfo data
 * class were already swept in cluster149 (Task #605) — see the audit-trail block
 * appended at platform/.../platform/update/AppUpdateClient.kt lines 43-86, which
 * documents the 3 actuals shipped at platform src android-ios-desktop Main update.
 *
 * LIVE evidence: the AppUpdateClient SPI is unambiguously LIVE. The legacy
 * :shared twin me.manga.kira.core.update.AppUpdateClient is bound per-platform
 * (PlatformModule.android.kt:123 "single { AppUpdateClient(androidContext()) }",
 * PlatformModule.ios.kt:107, PlatformModule.desktop.kt:107) and eager-init'd at
 * app/.../MyApp.kt:115 "get<AppUpdateClient>()". The contract this :platform
 * actual fulfils is therefore demonstrably consumed end-to-end in the legacy graph.
 *
 * Binding-state caveat: this rework :platform class (package platform.update, NOT
 * core.update) is NOT YET referenced by any composeApp rework Koin module — grep of
 * composeApp for AndroidAppUpdateClient / platform.update.AppUpdateClient returns
 * zero hits, matching the cluster149 expect-decl note that no rework bind site is
 * cited yet. It awaits a future PlatformReworkModule host wiring; classified
 * FULFILLED-PORT (relocated + SOLID-audited per SOLID_AUDIT.md File 2 of 4 lines
 * 2849-2873) rather than STALE because the interface it implements is LIVE.
 *
 * Delta-axes (this Android actual's distinct approach):
 *  1. Platform API — Google Play Core: AppUpdateManagerFactory.create on the
 *     applicationContext, appUpdateInfo Task, startUpdateFlowForResult, completeUpdate.
 *     The lone non-no-op actual of the three.
 *  2. Threading — suspend over Play Core Tasks via kotlinx-coroutines-play-services
 *     "await()"; no explicit dispatcher hop, await suspends on the calling context.
 *  3. Error handling — every method wraps in try-catch, logs via Kermit warn, and
 *     returns the SPI safe default (null for checkForUpdate, false otherwise). Never throws.
 *  4. DI binding mechanism — Koin constructor injection: Context plus a
 *     ForegroundActivityProvider lambda (typealias hoisted Phase 5.z.cleanup, Task #195);
 *     same shape as AndroidInAppReviewClient. The lambda defaults to "{ null }".
 *  5. Activity-bound plumbing parity — REQUEST_CODE 100 plus onActivityResult result
 *     surfacing stays in the Android host, NOT in the SPI; FLEXIBLE-with-IMMEDIATE-
 *     fallback availability logic lives in checkForUpdate, matching the legacy verbatim port.
 *  6. Behavioural-contract parity across the fan — Android does real work; iOS and
 *     Desktop are deliberate no-ops returning null-or-false. All three honour the
 *     "report nothing available, nothing started" contract so caller UI falls through
 *     to its hidden state on non-Android platforms.
 *
 * Nested-comment hazard check: this file has exactly 1 pre-existing legitimate
 * comment opener — the class-level KDoc block (lines 13-27) — plus this appended
 * block, for 2 openers total. This appended block is balanced: one opener, one
 * closer, and zero forbidden interior delimiter sequences in the prose.
 */
