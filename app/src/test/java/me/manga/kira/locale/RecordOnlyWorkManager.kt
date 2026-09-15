package me.manga.kira.locale

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkRequest
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import me.manga.kira.work.LibraryRefreshScheduling
import me.manga.kira.work.LibraryRefreshWorker
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Work2.11.2 calls its Kotlin companion, including through the Java static forwarding methods. */
@Implements(className = "androidx.work.WorkManager\$Companion", isInAndroidSdk = false)
class ResourceTestWorkManagerShadow {
    @Implementation
    fun isInitialized(): Boolean = AppLocaleStartupGuards.isWorkInitialized()

    @Implementation
    fun initialize(context: Context, configuration: Configuration) {
        AppLocaleStartupGuards.initializeWork(context, configuration)
    }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun getInstance(context: Context): WorkManager = AppLocaleStartupGuards.currentWorkManager()

    @Implementation
    fun getInstance(): WorkManager = AppLocaleStartupGuards.currentWorkManager()
}

/** Implements the SDK contract without constructing WorkManagerImpl, its database or schedulers. */
@Suppress("TooManyFunctions") // The third-party abstract SDK requires these fail-closed overrides.
internal class RecordOnlyWorkManager(override val configuration: Configuration) : WorkManager() {
    val periodicRequests = mutableListOf<PeriodicWorkRequest>()

    @SuppressLint("RestrictedApi")
    override fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ): Operation {
        AppLocaleStartupGuards.requireArmed("WorkManager.enqueueUniquePeriodicWork")
        if (
            uniqueWorkName != LibraryRefreshScheduling.PERIODIC_WORK_NAME ||
            existingPeriodicWorkPolicy != ExistingPeriodicWorkPolicy.UPDATE ||
            request.workSpec.workerClassName != LibraryRefreshWorker::class.java.name
        ) {
            unexpected("enqueueUniquePeriodicWork with another request")
        }
        periodicRequests += request
        return object : Operation {
            override fun getState(): LiveData<Operation.State> = MutableLiveData<Operation.State>(Operation.SUCCESS)

            override fun getResult(): ListenableFuture<Operation.State.SUCCESS> =
                Futures.immediateFuture(Operation.SUCCESS)
        }
    }

    override fun enqueue(requests: List<WorkRequest>): Nothing = unexpected("enqueue")

    override fun beginWith(requests: List<OneTimeWorkRequest>): Nothing = unexpected("beginWith")

    override fun beginUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        requests: List<OneTimeWorkRequest>,
    ): Nothing = unexpected("beginUniqueWork")

    override fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        requests: List<OneTimeWorkRequest>,
    ): Nothing = unexpected("enqueueUniqueWork")

    override fun cancelWorkById(id: UUID): Nothing = unexpected("cancelWorkById")

    override fun cancelAllWorkByTag(tag: String): Nothing = unexpected("cancelAllWorkByTag")

    override fun cancelUniqueWork(uniqueWorkName: String): Nothing = unexpected("cancelUniqueWork")

    override fun cancelAllWork(): Nothing = unexpected("cancelAllWork")

    override fun createCancelPendingIntent(id: UUID): Nothing = unexpected("createCancelPendingIntent")

    override fun pruneWork(): Nothing = unexpected("pruneWork")

    override fun getLastCancelAllTimeMillisLiveData(): Nothing = unexpected("getLastCancelAllTimeMillisLiveData")

    override fun getLastCancelAllTimeMillis(): Nothing = unexpected("getLastCancelAllTimeMillis")

    override fun getWorkInfoByIdLiveData(id: UUID): Nothing = unexpected("getWorkInfoByIdLiveData")

    override fun getWorkInfoByIdFlow(id: UUID): Nothing = unexpected("getWorkInfoByIdFlow")

    override fun getWorkInfoById(id: UUID): Nothing = unexpected("getWorkInfoById")

    override fun getWorkInfosByTagLiveData(tag: String): Nothing = unexpected("getWorkInfosByTagLiveData")

    override fun getWorkInfosByTagFlow(tag: String): Nothing = unexpected("getWorkInfosByTagFlow")

    override fun getWorkInfosByTag(tag: String): Nothing = unexpected("getWorkInfosByTag")

    override fun getWorkInfosForUniqueWorkLiveData(uniqueWorkName: String): Nothing =
        unexpected("getWorkInfosForUniqueWorkLiveData")

    override fun getWorkInfosForUniqueWorkFlow(uniqueWorkName: String): Nothing =
        unexpected("getWorkInfosForUniqueWorkFlow")

    override fun getWorkInfosForUniqueWork(uniqueWorkName: String): Nothing = unexpected("getWorkInfosForUniqueWork")

    override fun getWorkInfosLiveData(workQuery: WorkQuery): Nothing = unexpected("getWorkInfosLiveData")

    override fun getWorkInfosFlow(workQuery: WorkQuery): Nothing = unexpected("getWorkInfosFlow")

    override fun getWorkInfos(workQuery: WorkQuery): Nothing = unexpected("getWorkInfos")

    override fun updateWork(request: WorkRequest): Nothing = unexpected("updateWork")

    private fun unexpected(operation: String): Nothing =
        AppLocaleStartupGuards.unexpectedCall("WorkManager.$operation")
}
