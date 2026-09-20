package me.manga.kira.data.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.platform.download.BackgroundTransport
import me.manga.kira.platform.filesystem.chapterDir
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Native-list seam only: an exact task is observed, then disappears before reconcile's next query.
 * The real engine/Room still decide admission and retries; this is not OS-lifetime evidence. */
internal fun BackgroundTransport.observeRecoveredOnce(claim: ChapterArtifactClaim, pages: Set<Int> = setOf(0)): BackgroundTransport {
    val real = this
    return object : BackgroundTransport by real {
        private var observed = false
        override suspend fun inFlightPages(chapterId: Long, attemptToken: String): Set<Int> {
            if (!observed && chapterId == claim.owner.chapterId && attemptToken == claim.token) {
                observed = true
                return pages
            }
            return real.inFlightPages(chapterId, attemptToken)
        }
    }
}

/** Use genuine download names, not the CBZ fixture's unrelated conversion-input filenames. */
internal suspend fun IosCbzFinalizationFixture.downloadNamed(chapter: IosCbzChapter): IosCbzChapter {
    val directory = appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
    val pages = chapter.pages.entries.mapIndexed { index, (path, bytes) ->
        val target = directory / "image_$index.png"
        system.atomicMove(path, target)
        target to bytes
    }.toMap()
    val saved = chapter.saved.copy(
        isDownloaded = true, isBookmarked = true, lastReadPage = 7,
        localImagePaths = pages.keys.map { it.toString() },
    )
    db.backupDao().updateChapterRow(saved)
    return chapter.copy(saved = saved, pages = pages)
}

internal suspend fun IosCbzFinalizationFixture.offlineMirrors(chapter: IosCbzChapter): Pair<ChapterNotification, HistoryItemD> {
    val manga = assertNotNull(db.mangaDao().getMangaById(chapter.saved.mangaId))
    val notification = ChapterNotification(
        api = manga.api, language = manga.language, mangaId = manga.id,
        mangaTitle = manga.title, mangaImageUrl = manga.imageUrl, mangaUrl = manga.url,
        chapterId = chapter.saved.id, chapterNumber = chapter.saved.number, chapterUrl = chapter.saved.url,
        notificationDate = LocalDate(2024, 1, 2), isRead = true, isDownloaded = true,
        localImagePaths = chapter.saved.localImagePaths,
    )
    val notificationId = db.notificationDao().insertNotificationsList(listOf(notification)).single()
    val history = HistoryItemD(
        api = manga.api, language = manga.language, mangaId = 0,
        mangaUrl = manga.url, mangaTitle = manga.title, mangaImageUrl = manga.imageUrl,
        chapterUrl = chapter.saved.url, chapterTitle = chapter.saved.name, isDownloaded = true,
        localImagePaths = chapter.saved.localImagePaths, lastReadDate = LocalDateTime(2024, 2, 3, 4, 5),
        lastReadPage = 7, totalPages = 19,
    )
    val historyId = db.backupDao().insertHistoryRow(history)
    return notification.copy(id = notificationId) to history.copy(id = historyId)
}

internal suspend fun IosCbzFinalizationFixture.assertMirrorsCleared(mirrors: Pair<ChapterNotification, HistoryItemD>) {
    val (notification, history) = mirrors
    assertEquals(notification.copy(isDownloaded = false, localImagePaths = emptyList()),
        db.notificationDao().getNotificationByChapterId(notification.chapterId))
    assertEquals(history.copy(isDownloaded = false, localImagePaths = emptyList()),
        db.backupDao().getAllHistoryOnce().single { it.id == history.id })
}
