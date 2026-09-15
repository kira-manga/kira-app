package me.manga.kira.locale

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.common.IntentSenderForResultStarter
import com.google.android.play.core.install.InstallStateUpdatedListener
import me.manga.kira.MyApp
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Preserves the real AndroidAppUpdateClient while replacing only the remote Play SDK boundary. */
@Implements(AppUpdateManagerFactory::class, isInAndroidSdk = false)
class ResourceTestAppUpdateFactoryShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun create(context: Context): AppUpdateManager {
            AppLocaleStartupGuards.requireArmed("AppUpdateManagerFactory.create")
            check(context.applicationContext is MyApp)
            AppLocaleStartupGuards.updateManagerCreations += 1
            return RecordOnlyAppUpdateManager(context)
        }
    }
}

/** A local no-update answer, record-only listeners and failures for every install/update operation. */
@Suppress("TooManyFunctions") // All overloads belong to the pinned Play SDK interface.
private class RecordOnlyAppUpdateManager(context: Context) : AppUpdateManager {
    private val noUpdate by lazy {
        FakeAppUpdateManager(context).apply { setUpdateNotAvailable() }.appUpdateInfo
    }

    override fun getAppUpdateInfo(): Task<AppUpdateInfo> {
        AppLocaleStartupGuards.requireArmed("AppUpdateManager.appUpdateInfo")
        AppLocaleStartupGuards.updateInfoRequests += 1
        return noUpdate
    }

    override fun registerListener(listener: InstallStateUpdatedListener) {
        AppLocaleStartupGuards.requireArmed("AppUpdateManager.registerListener")
        if (!AppLocaleStartupGuards.updateListeners.add(listener)) unexpected("Repeated registerListener")
    }

    override fun unregisterListener(listener: InstallStateUpdatedListener) {
        AppLocaleStartupGuards.requireArmed("AppUpdateManager.unregisterListener")
        if (!AppLocaleStartupGuards.updateListeners.remove(listener)) unexpected("Unknown unregisterListener")
    }

    override fun completeUpdate(): Task<Void> = unexpected("completeUpdate")

    override fun startUpdateFlow(
        info: AppUpdateInfo,
        activity: Activity,
        options: AppUpdateOptions,
    ): Task<Int> = unexpected("startUpdateFlow")

    override fun startUpdateFlowForResult(
        info: AppUpdateInfo,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        options: AppUpdateOptions,
    ): Boolean = unexpected("startUpdateFlowForResult(launcher)")

    override fun startUpdateFlowForResult(
        info: AppUpdateInfo,
        type: Int,
        activity: Activity,
        requestCode: Int,
    ): Boolean = unexpected("startUpdateFlowForResult(activity,type)")

    override fun startUpdateFlowForResult(
        info: AppUpdateInfo,
        type: Int,
        starter: IntentSenderForResultStarter,
        requestCode: Int,
    ): Boolean = unexpected("startUpdateFlowForResult(starter,type)")

    override fun startUpdateFlowForResult(
        info: AppUpdateInfo,
        activity: Activity,
        options: AppUpdateOptions,
        requestCode: Int,
    ): Boolean = unexpected("startUpdateFlowForResult(activity,options)")

    override fun startUpdateFlowForResult(
        info: AppUpdateInfo,
        starter: IntentSenderForResultStarter,
        options: AppUpdateOptions,
        requestCode: Int,
    ): Boolean = unexpected("startUpdateFlowForResult(starter,options)")

    private fun unexpected(operation: String): Nothing =
        AppLocaleStartupGuards.unexpectedCall("AppUpdateManager.$operation")
}
