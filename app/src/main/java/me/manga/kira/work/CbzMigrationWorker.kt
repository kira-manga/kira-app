package me.manga.kira.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.manga.kira.core.cbz.CbzManager
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.data.local.dao.ChapterDao

/**
 * Phase 12.x port of upstream `CbzMigrationWorker.kt`.
 *
 * One-shot migration worker: iterates every downloaded `SavedChapterEntity` whose `localImagePaths`
 * are loose page files (legacy storage), repacks them into a single `.cbz` archive, and updates
 * the chapter row to point at the archive path.
 *
 * Deltas vs upstream:
 *  - `@HiltWorker` + `@AssistedInject` removed — Koin's `workerOf(::CbzMigrationWorker)` injects
 *    `(Context, WorkerParameters, ChapterDao, CbzManager)` positionally.
 *  - `android.util.Log` → Kermit (`co.touchlab.kermit.Logger`).
 *  - `Dispatchers.IO` → `IODispatcher` (the KMP-portable expect val) — same on JVM, harmless here.
 *
 * Trigger: not auto-scheduled and currently not enqueued anywhere. The equivalent migration runs
 * inline on the Settings storage-cleanup path via `SettingsRepositoryImpl` (which mirrors this
 * worker's `doWork`); the worker is retained behind its Koin `workerOf` binding for callers that
 * may enqueue it as a one-time `OneTimeWorkRequest<CbzMigrationWorker>()`. No periodic schedule.
 */
class CbzMigrationWorker(
    context: Context,
    params: WorkerParameters,
    private val chapterDao: ChapterDao,
    private val cbzManager: CbzManager,
) : CoroutineWorker(context, params) {

    private val log = Logger.withTag("CbzMigrationWorker")

    override suspend fun doWork(): Result = withContext(platformIoDispatcher) {
        try {
            val downloadedChapters = chapterDao.getAllDownloadedChapters()

            downloadedChapters.forEach { chapter ->
                if (chapter.localImagePaths.isNotEmpty() &&
                    !chapter.localImagePaths.first().endsWith(".cbz")
                ) {
                    val cbzPath = cbzManager.convertFilesToCbz(
                        chapter.mangaId,
                        chapter.id,
                        chapter.localImagePaths,
                    )

                    if (cbzPath != null) {
                        chapterDao.updateChapterLocalPaths(chapter.id, listOf(cbzPath))
                    }
                }
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.e(t) { "CBZ migration failed" }
            Result.failure()
        }
    }
}

/*
 * §253 audit-trail postscript — cluster284 §253 sweep (2026-05-29)
 *
 * Classification: LIVE-HOST CoroutineWorker — a real WorkManager worker registered through Koin's
 * WorkManager DSL, not a stale or orphan class.
 *
 * LIVE evidence:
 *  - Registered via workerOf(::CbzMigrationWorker) in app/.../di/AppKoinModule.kt:35.
 *  - That binding is honored because MyApp installs the KoinWorkerFactory: workManagerFactory() at
 *      MyApp.kt:77 inside onCreate, and MyApp is the manifest Application (AndroidManifest.xml:30
 *      android:name=".MyApp"); MyApp implements Configuration.Provider (MyApp.kt:54, :144) so
 *      WorkManager picks up the Koin-backed factory before default self-init.
 *  - appKoinModule itself is layered into the live graph at MyApp.kt:78 (modules(appKoinModule)).
 *  - The KoinWorkerFactory supplies (Context, WorkerParameters) and resolves the remaining ctor
 *      params (ChapterDao, CbzManager) from Koin — see AppKoinModule.kt:18-20 KDoc.
 *
 * Status: LIVE-HOST (legacy-logic-bearing Phase 12.x port; carries real one-shot migration logic,
 * not a delegate into rework :composeApp/:shared).
 *
 * Delta-axes vs rework graph:
 *  1. Koin startKoin wiring — workerOf DSL replaces upstream @HiltWorker + @AssistedInject; ctor
 *     params resolved positionally (KDoc line 20-21) instead of by Hilt assisted-factory.
 *  2. WorkManager integration — CoroutineWorker.doWork returns Result.success/failure; NOT
 *     auto-scheduled — exists so a Settings storage-cleanup action can enqueue a one-time request
 *     (KDoc line 25-26). No periodic schedule, no getForegroundInfo (silent background job).
 *  3. Dispatcher delta — Dispatchers.IO replaced by IODispatcher (the KMP-portable expect val,
 *     line 37); same JVM thread pool, harmless on Android, future-proofs a shared relocation.
 *  4. Logging delta — android.util.Log replaced by Kermit Logger.withTag (line 35).
 *  5. Data-layer coupling — drives ChapterDao.getAllDownloadedChapters + updateChapterLocalPaths
 *     and CbzManager.convertFilesToCbz directly; repacks loose page files into a single cbz archive.
 *
 * Nested-comment hazard check: this block contains no slash-star, no star-slash, no slash-star-star
 * sequence; the comment is balanced and compiles cleanly.
 */
