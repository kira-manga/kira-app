package me.manga.kira.presentation.features.download.ui.test2

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Parcel
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import me.manga.kira.platform.locale.AndroidLocaleOwner
import me.manga.kira.platform.locale.AndroidLocaleState

/**
 * Download-module SPI fixture only: no resource getter/resolver implementation and no test Activity.
 * Actual MyApp/MainActivity proof belongs to app tests; this lower module must not depend on app.
 */
class DownloadLocaleTestApplication : Application(), AndroidLocaleOwner {
    override lateinit var androidLocaleState: AndroidLocaleState
        private set

    override fun attachBaseContext(base: Context) {
        androidLocaleState = AndroidLocaleState(base)
        super.attachBaseContext(base)
    }
}

/** Calls the real runtime reflection fallback used for this unbound worker, without launching it. */
internal fun actualDownloadWorker(context: Context): DownloadWorkerV2 {
    val factory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = null
    }
    return factory.createWorkerWithDefaultFallback(
        context, DownloadWorkerV2::class.java.name, notificationParameters(factory),
    ) as DownloadWorkerV2
}

/** Work2.11.2's pinned restricted test seam; no test scheduler, queue or foreground IPC lifecycle. */
@SuppressLint("RestrictedApi")
private fun notificationParameters(factory: WorkerFactory): WorkerParameters {
    val executor = Executor { it.run() }
    return WorkerParameters(
        UUID(0, 17),
        Data.EMPTY,
        emptyList(),
        WorkerParameters.RuntimeExtras(),
        0,
        0,
        executor,
        EmptyCoroutineContext,
        WorkManagerTaskExecutor(executor),
        factory,
        ProgressUpdater { _, _, _ -> error("A notification build must not execute worker progress IPC") },
        ForegroundUpdater { _, _, _ -> error("A notification build must not execute foreground IPC") },
    )
}

internal fun NotificationManager.seedUserChannel(id: String, importance: Int): NotificationChannel {
    val channel = NotificationChannel(id, "Owner label", importance).apply {
        description = "Owner description"
        setSound(
            Uri.parse("content://locale-test/user-sound"),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
        )
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 130, 270)
        enableLights(true)
        lightColor = 0xFF336699.toInt()
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_SECRET
    }
    createNotificationChannel(channel)
    // ShadowNotificationManager returns aliased channel objects. Compare a detached full copy.
    val parcel = Parcel.obtain()
    return try {
        checkNotNull(getNotificationChannel(id)).writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        NotificationChannel.CREATOR.createFromParcel(parcel)
    } finally {
        parcel.recycle()
    }
}
