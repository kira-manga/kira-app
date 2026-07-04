package me.manga.yamiapk.google_play_cores.app_update

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import androidx.core.net.toUri

object AppUpdateHelper {
    private const val TAG = "AppUpdateHelper"
    private const val FLEXIBLE = AppUpdateType.FLEXIBLE
    private const val IMMEDIATE = AppUpdateType.IMMEDIATE
    private const val REQUEST_CODE = 100

    private lateinit var appUpdateManager: AppUpdateManager
    private var listener: InstallStateUpdatedListener? = null
    private var isInitialized = false

    fun init(context: Context) {
        try {
            appUpdateManager = AppUpdateManagerFactory.create(context.applicationContext)
            isInitialized = true
            Log.d(TAG, "AppUpdateManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AppUpdateManager", e)
            isInitialized = false
        }
    }

    /**
     * Checks for available updates and starts the chosen flow.
     */
    fun checkForUpdate(
        activity: Activity,
        immediate: Boolean = false,
        onUpdateNotAvailable: (() -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        if (!isInitialized) {
            Log.w(TAG, "AppUpdateManager not initialized")
            onError?.invoke(IllegalStateException("AppUpdateManager not initialized"))
            return
        }

        // Check if activity is valid
        if (activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "Activity is finishing or destroyed, cannot check for updates")
            return
        }

        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                try {
                    handleUpdateInfo(activity, info, immediate, onUpdateNotAvailable)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling update info", e)
                    onError?.invoke(e)
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to get app update info", exception)
                onError?.invoke(exception)
            }
    }

    private fun handleUpdateInfo(
        activity: Activity,
        info: AppUpdateInfo,
        immediate: Boolean,
        onUpdateNotAvailable: (() -> Unit)?
    ) {
        when {
            info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE -> {
                when {
                    immediate && info.isUpdateTypeAllowed(IMMEDIATE) -> {
                        Log.d(TAG, "Starting immediate update")
                        startUpdate(activity, IMMEDIATE, info)
                    }
                    info.isUpdateTypeAllowed(FLEXIBLE) -> {
                        Log.d(TAG, "Starting flexible update")
                        startUpdate(activity, FLEXIBLE, info)
                    }
                    else -> {
                        Log.d(TAG, "Update available but not allowed for current type")
                        onUpdateNotAvailable?.invoke()
                    }
                }
            }
            info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                Log.d(TAG, "Update already in progress")
                if (immediate && info.isUpdateTypeAllowed(IMMEDIATE)) {
                    startUpdate(activity, IMMEDIATE, info)
                }
            }
            else -> {
                Log.d(TAG, "No update available or update not available")
                onUpdateNotAvailable?.invoke()
            }
        }
    }

    private fun startUpdate(activity: Activity, @AppUpdateType type: Int, info: AppUpdateInfo) {
        try {
            // Additional validation before starting update
            if (activity.isFinishing || activity.isDestroyed) {
                Log.w(TAG, "Cannot start update: Activity is finishing or destroyed")
                return
            }

            Log.d(TAG, "Attempting to start update flow for type: $type")

            appUpdateManager.startUpdateFlowForResult(
                info,
                type,
                activity,
                REQUEST_CODE
            )
        } catch (e: IntentSender.SendIntentException) {
            Log.e(TAG, "IntentSender.SendIntentException when starting update", e)
            // Handle the specific exception that was causing crashes
            handleUpdateStartFailure(activity, e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error when starting update", e)
            handleUpdateStartFailure(activity, e)
        }
    }

    private fun handleUpdateStartFailure(activity: Activity, exception: Exception) {
        // You can implement custom logic here, such as:
        // - Showing a dialog to user
        // - Redirecting to Play Store manually
        // - Logging to analytics
        Log.w(TAG, "Update start failed, you may want to handle this gracefully")

        // Optional: Show user a message or redirect to Play Store manually
        // redirectToPlayStore(activity)
    }

    /**
     * Call from Activity.onResume() to resume any pending update or show completion prompt.
     */
    fun resumeUpdate(activity: Activity, onDownloaded: () -> Unit) {
        if (!isInitialized) {
            Log.w(TAG, "AppUpdateManager not initialized")
            return
        }

        if (activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "Cannot resume update: Activity is finishing or destroyed")
            return
        }

        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                try {
                    when (info.installStatus()) {
                        InstallStatus.DOWNLOADED -> {
                            Log.d(TAG, "Update downloaded, ready to install")
                            onDownloaded()
                        }
                        InstallStatus.DOWNLOADING -> {
                            Log.d(TAG, "Update still downloading")
                        }
                        InstallStatus.FAILED -> {
                            Log.w(TAG, "Update failed")
                        }
                        else -> {
                            Log.d(TAG, "Update status: ${info.installStatus()}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error resuming update", e)
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to resume update", exception)
            }
    }

    /**
     * Registers a listener to receive download progress.
     */
    fun registerListener(
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onDownloaded: () -> Unit,
        onFailed: ((Int) -> Unit)? = null
    ) {
        if (!isInitialized) {
            Log.w(TAG, "AppUpdateManager not initialized")
            return
        }

        try {
            listener = InstallStateUpdatedListener { state ->
                when (state.installStatus()) {
                    InstallStatus.DOWNLOADING -> {
                        val downloaded = state.bytesDownloaded()
                        val total = state.totalBytesToDownload()
                        Log.d(TAG, "Download progress: $downloaded/$total")
                        onProgress(downloaded, total)
                    }
                    InstallStatus.DOWNLOADED -> {
                        Log.d(TAG, "Download completed")
                        onDownloaded()
                    }
                    InstallStatus.FAILED -> {
                        Log.w(TAG, "Installation failed with error code: ${state.installErrorCode()}")
                        onFailed?.invoke(state.installErrorCode())
                    }
                    InstallStatus.CANCELED -> {
                        Log.d(TAG, "Installation canceled by user")
                    }
                    else -> {
                        Log.d(TAG, "Install status: ${state.installStatus()}")
                    }
                }
            }.also {
                appUpdateManager.registerListener(it)
                Log.d(TAG, "Update listener registered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register update listener", e)
        }
    }

    /**
     * Unregisters the download-progress listener. Call from Activity.onDestroy().
     */
    fun unregisterListener() {
        if (!isInitialized) {
            return
        }

        try {
            listener?.let {
                appUpdateManager.unregisterListener(it)
                Log.d(TAG, "Update listener unregistered")
            }
            listener = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister update listener", e)
        }
    }

    /**
     * Completes installation after a flexible update download.
     */
    fun completeUpdate() {
        if (!isInitialized) {
            Log.w(TAG, "AppUpdateManager not initialized")
            return
        }

        try {
            appUpdateManager.completeUpdate()
            Log.d(TAG, "Update completion requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete update", e)
        }
    }

    /**
     * Check if the manager is properly initialized
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * Optional: Redirect user to Play Store manually if in-app update fails
     */
    private fun redirectToPlayStore(activity: Activity) {
        try {
            val packageName = activity.packageName
            val playStoreIntent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                "market://details?id=$packageName".toUri()
            )

            if (playStoreIntent.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(playStoreIntent)
            } else {
                // Fallback to web version
                val webIntent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    "https://play.google.com/store/apps/details?id=$packageName".toUri()
                )
                activity.startActivity(webIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to redirect to Play Store", e)
        }
    }
}