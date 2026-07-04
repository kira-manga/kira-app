package me.manga.yamiapk

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.android.gms.ads.MobileAds
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import me.manga.yamiapk.core.util.notification.NotificationHelper
import me.manga.yamiapk.google_play_cores.app_update.AppUpdateHelper
import me.manga.yamiapk.sources_repositry.pt.manhastro.ManhastroDadosStore
import javax.inject.Inject

@HiltAndroidApp
class MyApp : Application(), Configuration.Provider{
    @Inject lateinit var workerFactory: HiltWorkerFactory
//    private lateinit var defaultHandler: Thread.UncaughtExceptionHandler

    companion object {
        private const val REFRESH_WORK_NAME = "LibraryRefresh"
    }
    override fun onCreate() {
        super.onCreate()
        // Initialize once
        try {
            AppUpdateHelper.init(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize AppUpdateHelper", e)
        }
        FirebaseApp.initializeApp(this)

//        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
//
//        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
//            // Report to your crash‑reporting backend…
//            // Optionally restart your main activity:
//            FirebaseCrashlytics.getInstance().apply {
//                recordException(throwable)
//                // optional: force a synchronous upload
//                sendUnsentReports()
//            }
//            // 2) start CrashActivity **on the main thread**
//            Handler(Looper.getMainLooper()).post {
//                CrashActivity.start(this@MyApp, stack)
//            }
////            defaultHandler.uncaughtException(thread, throwable)
//
//
//        }
        NotificationHelper.init(this)
        MobileAds.initialize(this) { initializationStatus ->
            // Optional: log or handle initialization complete
        }

//        FirebaseApp.initializeApp(this)

//        scheduleLibraryRefresh()



    }


//    override fun onTrimMemory(level: Int) {
//        super.onTrimMemory(level)

//        if (level >= TRIM_MEMORY_RUNNING_LOW) {
//            dadosStore.clear()
//        }
//    }
//    private fun scheduleLibraryRefresh() {
//        val wm = WorkManager.getInstance(this)
//        val constraints = Constraints.Builder()
//            .setRequiredNetworkType(NetworkType.CONNECTED)
//            .build()
//        // Build a 5‑hourly periodic request, with first run after 5 hours
//        val periodicRequest = PeriodicWorkRequestBuilder<LibraryRefreshWorker>(
//            5, TimeUnit.HOURS
//           )
//            .setInitialDelay(1, TimeUnit.MINUTES)
//            .setConstraints(constraints)
//            .build()
//
//        wm.enqueueUniquePeriodicWork(
//            REFRESH_WORK_NAME,
//            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,    // if already scheduled, leave it alone
//            periodicRequest
//        )
//
//
//        WorkManager.getInstance(this)
//            .getWorkInfoByIdLiveData(periodicRequest.id)
//            .observeForever { info ->
//                if (info != null) {
//                }
//            }
//
//    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

}



