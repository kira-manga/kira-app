package me.manga.kira.update

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.InstallStateUpdatedListener

/** SDK boundary only; selection, foreground checks and recovery stay in the production client. */
internal class RecordingAppUpdateManager(
    val fake: FakeAppUpdateManager,
) : AppUpdateManager by fake {
    data class Launch(
        val info: AppUpdateInfo,
        val activity: Activity,
        val type: Int,
        val requestCode: Int,
    )

    val queries = mutableListOf<Task<AppUpdateInfo>>()
    val queryOverrides = mutableMapOf<Int, Task<AppUpdateInfo>>()
    val launches = mutableListOf<Launch>()
    val events = mutableListOf<String>()
    val listeners = mutableSetOf<InstallStateUpdatedListener>()
    val removedListeners = mutableListOf<InstallStateUpdatedListener>()
    var launchResult: Boolean? = null
    var launchFailure: Exception? = null
    var completions = 0
        private set

    override fun getAppUpdateInfo(): Task<AppUpdateInfo> {
        events += "query"
        val task = queryOverrides.remove(queries.size + 1) ?: fake.appUpdateInfo
        queries += task
        return task
    }

    override fun startUpdateFlowForResult(
        appUpdateInfo: AppUpdateInfo,
        activity: Activity,
        options: AppUpdateOptions,
        requestCode: Int,
    ): Boolean {
        events += "launch"
        launches += Launch(appUpdateInfo, activity, options.appUpdateType(), requestCode)
        launchFailure?.let { throw it }
        return launchResult ?: fake.startUpdateFlowForResult(appUpdateInfo, activity, options, requestCode)
    }

    override fun completeUpdate(): Task<Void> {
        events += "complete"
        completions++
        return fake.completeUpdate()
    }

    override fun registerListener(listener: InstallStateUpdatedListener) {
        listeners += listener
        fake.registerListener(listener)
    }

    override fun unregisterListener(listener: InstallStateUpdatedListener) {
        listeners -= listener
        removedListeners += listener
        fake.unregisterListener(listener)
    }
}
