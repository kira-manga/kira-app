package me.manga.yamiapk.presentation.features.download.ui.test2

// In e.g. me.manga.yamiapk.presentation.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.manga.yamiapk.presentation.features.download.domain.clean.DownloadRepository
import javax.inject.Inject

@AndroidEntryPoint
class DownloadCancelReceiver : BroadcastReceiver() {
    @Inject
    lateinit var downloadRepository: DownloadRepository
    // If you need other injections, inject here.

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()

        val action = intent.action
        when (action) {
            DownloadWorkerV2.ACTION_CANCEL -> {
                val workIdString = intent.getStringExtra(DownloadWorkerV2.EXTRA_WORK_ID)
                if (workIdString != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            downloadRepository.cancelAllDownloads()
                        } catch (e: Exception) {

                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    pendingResult.finish()
                }
            }

            DownloadWorkerV2.ACTION_CANCEL_CHAPTER -> {
                val pending = pendingResult
                val chapterId = intent.getLongExtra(DownloadWorkerV2.EXTRA_CHAPTER_ID, -1L)
                val mangaId = intent.getLongExtra(DownloadWorkerV2.EXTRA_MANGA_ID, -1L)

                if (chapterId >= 0&& mangaId>=0) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            downloadRepository.cancelARunningChapter(chapterId,mangaId)
                        } catch (e: Exception) {

                        } finally {
                            pending.finish()
                        }
                    }
                } else {
                    pendingResult.finish()
                }
            }
        }
    }
}