package me.manga.kira.details

import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.MutableStateFlow
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepositoryImpl
import me.manga.kira.presentation.features.download.ui.test2.AndroidChallengeCase
import me.manga.kira.presentation.features.download.ui.test2.GATE_TIMEOUT_SECONDS
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Same held-constraint WorkManager setup as the existing worker cancellation fixture. */
internal class AndroidChallengeWorkQueue(android: AndroidChallengeCase) : AutoCloseable {
    private val previous = WorkManagerImpl.getInstance()
    private val workManager = run {
        // Never satisfy CONNECTED: real adapter writes/work requests are observed, workers run explicitly.
        WorkManagerTestInitHelper.initializeTestWorkManager(android.storage.context)
        WorkManagerImpl.getInstance(android.storage.context)
    }
    private val actual = DownloadRepositoryImpl(
        workManager, android.rows.realDao, android.service, android.rows.artifacts, android.rows.operations,
    )
    val enqueued = MutableStateFlow<List<Triple<Long, String, String>>>(emptyList())
    val engine: DownloadRepository = object : DownloadRepository by actual {
        override suspend fun enqueueChapterDownload(chapter: SavedChapterEntity, title: String, mangaApi: String) {
            actual.enqueueChapterDownload(chapter, title, mangaApi)
            enqueued.value = enqueued.value + Triple(chapter.id, title, mangaApi)
        }
    }

    fun assertHeldRequests(count: Int) {
        val work = workManager.getWorkInfosForUniqueWork("mangaDownloadv2").get(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(count, work.size)
        assertTrue(work.all { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED })
    }

    override fun close() {
        check(WorkManagerImpl.getInstance() === workManager) { "WorkManager ownership changed" }
        try {
            workManager.cancelAllWork().result.get(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            try {
                WorkManagerTestInitHelper.closeWorkDatabase()
            } finally {
                WorkManagerImpl.setDelegate(previous)
            }
        }
    }
}
