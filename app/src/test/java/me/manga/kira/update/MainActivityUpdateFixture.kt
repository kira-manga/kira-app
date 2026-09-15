package me.manga.kira.update

import android.Manifest
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.widget.FrameLayout
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.manga.kira.MainActivity
import me.manga.kira.locale.LocaleOnlyTestApplication
import me.manga.kira.platform.activity.ActivityHolder
import me.manga.kira.platform.review.InAppReviewClient
import me.manga.kira.platform.storage.SecureStorage
import me.manga.kira.platform.update.AndroidAppUpdateClient
import me.manga.kira.platform.update.AppUpdateClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Local lifecycle/Koin fixture; production MainActivity and the Android update client remain real. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MainActivityUpdateFixture {
    private val dispatcher = StandardTestDispatcher()
    val review = ReviewProbe()
    lateinit var manager: RecordingAppUpdateManager
        private set
    private var controller: ActivityController<MainActivity>? = null
    private var started = false
    private var resumed = false
    private var reviewStarted = false

    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ActivityHolder.current?.let(ActivityHolder::clear)
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = RecordingAppUpdateManager(FakeAppUpdateManager(application))
        val client = AndroidAppUpdateClient(application, { ActivityHolder.current }, manager)
        val storage = eligibleReviewStorage()
        startKoin {
            modules(
                module {
                    single<AppUpdateClient> { client }
                    single<InAppReviewClient> { review }
                    single<SecureStorage> { storage }
                },
            )
        }
    }

    fun tearDown() {
        destroyActivity()
        drainMain()
        stopKoin()
        ActivityHolder.current?.let(ActivityHolder::clear)
        Dispatchers.resetMain()
    }

    fun createActivity(): MainActivity {
        check(controller == null)
        val created = Robolectric.buildActivity(MainActivity::class.java).create()
        // Run real onCreate with normal manifest/resources, without attaching its Compose tree
        // or bootstrapping unrelated screens/DI. No production rendering/startup switch is needed.
        created.get().setContentView(FrameLayout(created.get()))
        controller = created
        return created.get()
    }

    fun resumeActivity() {
        val current = checkNotNull(controller)
        if (!started) {
            current.start()
            started = true
        }
        current.resume()
        resumed = true
        drainMain()
        if (!reviewStarted) {
            review.awaitRequest()
            reviewStarted = true
        }
    }

    fun pauseActivity() {
        checkNotNull(controller).pause()
        resumed = false
        drainMain()
    }

    fun destroyActivity() {
        val current = controller ?: return
        if (resumed) pauseActivity()
        if (started) current.stop()
        current.destroy()
        controller = null
        started = false
        resumed = false
        reviewStarted = false
        drainMain()
    }

    fun drainMain() {
        dispatcher.scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        dispatcher.scheduler.runCurrent()
    }

    private fun eligibleReviewStorage(): SecureStorage =
        object : SecureStorage {
            override suspend fun get(key: String): String? =
                if (key == "first_open_time") {
                    (System.currentTimeMillis() - TimeUnit.DAYS.toMillis(21)).toString()
                } else {
                    null
                }

            override suspend fun put(
                key: String,
                value: String,
            ) = Unit

            override suspend fun remove(key: String) = Unit
        }
}

internal class ReviewProbe : InAppReviewClient {
    val requests = AtomicInteger()
    private val observed = LinkedBlockingQueue<Unit>()

    override suspend fun requestReview(): Boolean {
        requests.incrementAndGet()
        observed.add(Unit)
        return true
    }

    fun awaitRequest() {
        assertNotNull("Eligible review did not run", observed.poll(5, TimeUnit.SECONDS))
    }

    fun assertNoRequest() {
        assertNull("Review restarted on update resume", observed.poll(200, TimeUnit.MILLISECONDS))
    }
}

/**
 * Real lifecycle dispatch forwards to the real holder. This deliberately does NOT run MyApp;
 * production MyApp callback registration/order remains unchanged and source-confirmed, not tested.
 */
class AppUpdateTestApplication : LocaleOnlyTestApplication() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) = ActivityHolder.set(activity)

                override fun onActivityPaused(activity: Activity) = ActivityHolder.clear(activity)

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = ActivityHolder.clear(activity)
            },
        )
    }
}
