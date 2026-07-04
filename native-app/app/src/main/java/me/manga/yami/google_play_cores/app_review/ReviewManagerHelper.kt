package me.manga.yamiapk.google_play_cores.app_review

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.android.gms.tasks.Task
import com.google.android.play.core.review.ReviewManagerFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import me.manga.yamiapk.core.storage.dataStoreLong
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


@Singleton
class ReviewManagerHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEY_HAS_REVIEWED    = "has_reviewed"
        private const val KEY_FIRST_OPEN_TIME = "first_open_time"
        private val SEVEN_DAYS_MILLIS = TimeUnit.DAYS.toMillis(20)
//        private val FIVE_MINUTES_MILLIS = TimeUnit.MINUTES.toMillis(5)

    }

//    // DataStore delegates
//    @VisibleForTesting
//    internal val hasReviewedPref = context.dataStoreBoolean(
//        KEY_HAS_REVIEWED,
//        default = false
//    )

    @VisibleForTesting
    internal val firstOpenTimePref = context.dataStoreLong(
        KEY_FIRST_OPEN_TIME,
        default = 0L
    )

    /** Flow that emits `true` once 7 days have passed since first open *and* user hasn't reviewed */
    val shouldShowReviewFlow: Flow<Boolean> =
        firstOpenTimePref.flow.map { firstOpen ->

            // if this is first time reading, record now and don't show yet:
            if (firstOpen == 0L) {
                firstOpenTimePref.set(System.currentTimeMillis())
                false
            } else {

                val waited = System.currentTimeMillis() - firstOpen

                 waited >= SEVEN_DAYS_MILLIS
            }
        }.flowOn(Dispatchers.IO)

    /** Synchronous check (off main thread only) */

    /** Synchronous “should I show?” (off main thread only) */


    suspend fun shouldShowReview(): Boolean = shouldShowReviewFlow.first()

    /**
     * Fire off the in‑app review flow. You can call this once
     * `shouldShowReviewFlow` emits `true`.
     */
    suspend fun launchInAppReview(activity: Activity) {
        val shouldShow = shouldShowReview()

        if (shouldShow) {
            val manager = ReviewManagerFactory.create(context)

            // 1. await() turns the Play‑Core Task into a suspending call
            val reviewInfo = manager.requestReviewFlow().await()

            // 2. same here
            manager.launchReviewFlow(activity, reviewInfo).await()
        }
        return

    }

    suspend fun <T> Task<T>.await(
        onComplete: ((Task<T>) -> Unit)? = null
    ): T = suspendCoroutine { cont ->
        // Always notify on completion
        addOnCompleteListener { task ->

            onComplete?.invoke(task)
        }

        // Notify and resume on success
        addOnSuccessListener { result ->

            cont.resume(result)
        }

        // Notify and resume with exception on failure
        addOnFailureListener { exc ->
            cont.resumeWithException(exc)
        }
        addOnCanceledListener {

        }
    }
}




