package me.manga.kira.work

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import io.ktor.client.HttpClient
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import me.manga.kira.MyApp
import me.manga.kira.R
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.locale.RealApplicationLocaleTest
import me.manga.kira.locale.assertOnlyChannelLabelsChanged
import me.manga.kira.locale.seedUserChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.annotation.experimental.LazyApplication.LazyLoad.ON

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], application = MyApp::class, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
@LazyApplication(ON)
class LibraryRefreshResourceLocaleTest : RealApplicationLocaleTest() {
    private var database: MangaDatabase? = null
    private var resolvedHttpClient: HttpClient? = null

    @After
    fun closeResolvedDependencies() {
        try {
            resolvedHttpClient?.close()
        } finally {
            database?.close()
        }
    }

    @Test
    fun realKoinWorkerCreatesLocalizedForegroundChannelBeforeDoWorkAndRefreshesNextBuild() = runBlocking {
        val worker = actualWorker()
        val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
        assertSame(app, worker.applicationContext)
        assertNull(manager.getNotificationChannel(CHANNEL_ID))
        selectLanguage("fr")
        val first = worker.getForegroundInfo()
        assertEquals(42, first.notificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, first.foregroundServiceType)
        assertText(first.notification, "Mise à jour de la bibliothèque", "Démarrage…")
        assertChannelLabels("Mise à jour de la bibliothèque", "Affiche la progression de la mise à jour en arrière-plan")
        selectLanguage("ar")
        val next = worker.getForegroundInfo()
        assertText(next.notification, "جاري تحديث المكتبة", "يبدأ…")
        assertChannelLabels("تحديث المكتبة", "إظهار تقدم تحديث المكتبة في الخلفية")
        assertText(first.notification, "Mise à jour de la bibliothèque", "Démarrage…")
    }

    @Test
    fun titleNestedProgressAndChannelUseOneSnapshotEvenIfSelectionChangesDuringBuild() {
        val worker = actualWorker()
        selectLanguage("fr")
        val notification = worker.buildNotification(
            text = { resources ->
                val status = resources.getString(R.string.notification_finishing_up)
                selectLanguage("ar")
                resources.getString(R.string.notification_refresh_progress, status, 2, 3, 1)
            },
            progress = 67,
        )
        assertText(notification, "Mise à jour de la bibliothèque", "Finalisation… (2/3 terminés, 1 échoués)")
        assertEquals(67, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertChannelLabels("Mise à jour de la bibliothèque", "Affiche la progression de la mise à jour en arrière-plan")
        val next = worker.buildNotification(
            text = { it.getString(R.string.notification_refresh_failed, "fixture") },
            isComplete = true,
            isError = true,
        )
        assertText(next, "فشل تحديث المكتبة", "فشل التحديث: fixture")
        assertEquals(0, next.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertChannelLabels("تحديث المكتبة", "إظهار تقدم تحديث المكتبة في الخلفية")
    }

    @Test
    fun sameIdRelabelKeepsUserImportanceSoundVibrationAndBlockedState() = runBlocking {
        val worker = actualWorker()
        val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
        val high = manager.seedUserChannel(CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH)
        selectLanguage("fr")
        worker.getForegroundInfo()
        manager.assertOnlyChannelLabelsChanged(
            high, "Mise à jour de la bibliothèque", "Affiche la progression de la mise à jour en arrière-plan",
        )
        val blocked = manager.seedUserChannel(CHANNEL_ID, NotificationManager.IMPORTANCE_NONE)
        selectLanguage("ar")
        worker.getForegroundInfo()
        manager.assertOnlyChannelLabelsChanged(blocked, "تحديث المكتبة", "إظهار تقدم تحديث المكتبة في الخلفية")
    }

    private fun actualWorker(): LibraryRefreshWorker {
        val host = app
        database = GlobalContext.get().get<MangaDatabase>()
        // The real factory consumes the real workerOf registration and its actual collaborators.
        // No verify()-only graph check, test worker subclass or replacement DI binding.
        val factory = KoinWorkerFactory()
        return try {
            checkNotNull(
                factory.createWorker(host, LibraryRefreshWorker::class.java.name, notificationParameters(factory)),
            ) as LibraryRefreshWorker
        } finally {
            captureResolvedHttpClient()
        }
    }

    @OptIn(KoinInternalApi::class)
    private fun captureResolvedHttpClient() {
        val koin = GlobalContext.get()
        val factory = koin.instanceRegistry.instances.values.firstOrNull {
            it.beanDefinition.primaryType == HttpClient::class && it.beanDefinition.qualifier == null
        }
        // getOrNull alone would create an unused client. Inspect creation first, including when
        // the real worker factory failed after constructing only part of its dependency graph.
        if (factory?.isCreated(null) == true) resolvedHttpClient = koin.get<HttpClient>()
    }

    private fun assertText(notification: Notification, title: String, body: String) {
        assertEquals(title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(body, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    private fun assertChannelLabels(name: String, description: String) {
        val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
        val channel = checkNotNull(manager.getNotificationChannel(CHANNEL_ID))
        assertEquals(name, channel.name.toString())
        assertEquals(description, channel.description)
    }

    private companion object {
        const val CHANNEL_ID = "library_refresh"
    }
}

/** Pinned Work2.11.2 test-only construction seam, not a scheduler/installed-WorkManager fixture. */
@SuppressLint("RestrictedApi")
private fun notificationParameters(factory: WorkerFactory): WorkerParameters {
    val executor = Executor { it.run() }
    return WorkerParameters(
        UUID(0, 42),
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
