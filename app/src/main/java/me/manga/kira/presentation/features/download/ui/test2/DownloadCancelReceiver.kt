package me.manga.kira.presentation.features.download.ui.test2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import org.koin.core.context.GlobalContext

/**
 * Handles the download-notification "Cancel all" / "Cancel chapter" action buttons.
 *
 * Ported from the native `DownloadCancelReceiver` (source of truth). The native receiver is a Hilt
 * `@AndroidEntryPoint` with an injected `DownloadRepository`; here the receiver is instantiated by
 * the OS, not by Koin, so dependencies are resolved from Koin's [GlobalContext] (the same idiom used
 * by `MainActivity` and `DownloadWorkerV2`, which are likewise not Koin-constructed).
 *
 * The two actions and their extras are emitted verbatim by [DownloadWorkerV2] in its foreground /
 * per-chapter progress notifications:
 *  - [DownloadWorkerV2.ACTION_CANCEL] (with [DownloadWorkerV2.EXTRA_WORK_ID]) → cancel the whole
 *    download queue.
 *  - [DownloadWorkerV2.ACTION_CANCEL_CHAPTER] (with [DownloadWorkerV2.EXTRA_CHAPTER_ID] /
 *    [DownloadWorkerV2.EXTRA_MANGA_ID]) → cancel a single running chapter.
 *
 * goAsync() keeps the receiver alive while the (suspending) cancellation work runs off the main
 * thread; `pendingResult.finish()` is always called from the coroutine's `finally`, mirroring native.
 *
 * Cancel-all parity note: native routes [DownloadWorkerV2.ACTION_CANCEL] through
 * `DownloadRepository.cancelAllDownloads()`, which (a) marks all running/queued rows as failed and
 * (b) cancels the unique WorkManager job. The KMP receiver now routes the same way — the
 * `cancelAllDownloads()` SPI member (and the backing `markAllRunningOrQueuedAsFailed()` DAO query),
 * previously pruned by Task #398 / #440, were restored for the DOWNLOAD "cancel-all marks rows
 * failed" backlog item (2026-06-01). Resolving the legacy [DownloadRepository] from Koin's
 * [GlobalContext] (rather than the rework `DownloadsActionRepository`) mirrors native, where the
 * Hilt-injected `DownloadRepository` is the bulk-cancel seam; the Android impl performs both the DB
 * "mark failed" and the WorkManager-cancel halves internally.
 */
class DownloadCancelReceiver : BroadcastReceiver() {

    private val log = Logger.withTag(TAG)

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()

        when (intent.action) {
            DownloadWorkerV2.ACTION_CANCEL -> {
                val workIdString = intent.getStringExtra(DownloadWorkerV2.EXTRA_WORK_ID)
                if (workIdString != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            GlobalContext.get().get<DownloadRepository>().cancelAllDownloads()
                        } catch (e: Exception) {
                            log.w(e) { "Failed to cancel all downloads" }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    pendingResult.finish()
                }
            }

            DownloadWorkerV2.ACTION_CANCEL_CHAPTER -> {
                val chapterId = intent.getLongExtra(DownloadWorkerV2.EXTRA_CHAPTER_ID, -1L)
                val mangaId = intent.getLongExtra(DownloadWorkerV2.EXTRA_MANGA_ID, -1L)

                if (chapterId >= 0 && mangaId >= 0) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            GlobalContext.get().get<DownloadRepository>()
                                .cancelARunningChapter(chapterId, mangaId)
                        } catch (e: Exception) {
                            log.w(e) { "Failed to cancel chapter download (chapterId=$chapterId, mangaId=$mangaId)" }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    pendingResult.finish()
                }
            }

            else -> pendingResult.finish()
        }
    }

    private companion object {
        const val TAG = "DownloadCancelReceiver"
    }
}
