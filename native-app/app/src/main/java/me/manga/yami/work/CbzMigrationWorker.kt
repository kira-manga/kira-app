package me.manga.yamiapk.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.manga.yamiapk.core.cbz.CbzManager
import me.manga.yamiapk.data.local.dao.ChapterDao

@HiltWorker
class CbzMigrationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val chapterDao: ChapterDao,
    private val cbzManager: CbzManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Get all downloaded chapters
            val downloadedChapters = chapterDao.getAllDownloadedChapters() // You'll need to add this query

            downloadedChapters.forEach { chapter ->
                if (chapter.localImagePaths.isNotEmpty() &&
                    !chapter.localImagePaths.first().endsWith(".cbz")) {

                    // Convert to CBZ
                    val cbzPath = cbzManager.convertFilesToCbz(
                        chapter.mangaId,
                        chapter.id,
                        chapter.localImagePaths
                    )

                    if (cbzPath != null) {
                        // Update database with new CBZ path
                        chapterDao.updateChapterLocalPaths(chapter.id, listOf(cbzPath))
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("CbzMigrationWorker", "Migration failed", e)
            Result.failure()
        }
    }
}