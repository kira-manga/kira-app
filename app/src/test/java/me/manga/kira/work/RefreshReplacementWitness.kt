package me.manga.kira.work

import android.content.Context
import androidx.work.ListenableWorker
import me.manga.kira.core.util.notification.CHAPTER_NOTIFICATION_CHANNEL
import me.manga.kira.core.util.notification.NotificationPostingShadow
import me.manga.kira.core.util.notification.NotificationRoomFixture
import me.manga.kira.data.local.entity.ChapterNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.TimeUnit

/** Starts/joins a real replacement worker after its predecessor's cancellation has settled. */
internal class RefreshReplacementWitness(
    private val context: Context,
    private val room: NotificationRoomFixture,
    private val posting: NotificationPostingShadow,
) {
    suspend fun assertNoRepeat(committed: List<ChapterNotification>, onRun: (NotificationWorkerRun) -> Unit) {
        val replacement = NotificationWorkerRun().also(onRun)
        val covers = NoCoverExpected()
        val chapters = room.db.chapterDao().getChaptersByMangaIdR(committed.first().mangaId)
        try {
            replacement.start(
                notificationRefreshWorker(
                    context, room.repository(), room.helper(covers), chapters.asReversed(), replacement::captureExecution,
                ),
            )
            replacement.join()
            assertEquals(
                ListenableWorker.Result.success(),
                replacement.future.get(NOTIFICATION_WAIT_MILLIS, TimeUnit.MILLISECONDS),
            )
            assertEquals(committed, room.updates())
            assertEquals(0, covers.calls)
            assertTrue(posting.posted.none { it.second.channelId == CHAPTER_NOTIFICATION_CHANNEL })
        } finally {
            replacement.close()
        }
    }
}
