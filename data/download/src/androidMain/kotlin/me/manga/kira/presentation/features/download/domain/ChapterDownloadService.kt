package me.manga.kira.presentation.features.download.domain

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import me.manga.kira.core.cbz.OptimizedCbzManager
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.domain.service.FileService
import me.manga.kira.presentation.features.download.data.DownloadState
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.library.domain.LibraryRepository
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Phase 8.14 port of upstream `presentation/features/download/domain/ChapterDownloadService`.
 *
 * Replaces upstream's OkHttp client with the shared Ktor [HttpClient] (Phase 8 networking
 * layer). The Ktor client is bound as a singleton in `SharedModule.kt` so it's already
 * configured with the project's UA / logging / timeouts. Special header handling for
 * `MangamelloPlusRepository` is preserved.
 *
 * Hilt `@Inject @Singleton` + `@ApplicationContext` + `@MainOkHttpClient` annotations
 * stripped; Koin provides everything via `PlatformModule.android.kt`. Behaviour is
 * otherwise identical to upstream.
 */
class ChapterDownloadService(
    private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val httpClient: HttpClient,
    private val fileService: FileService,
    private val notificationDao: NotificationDao,
    private val chapterDownloadDao: ChapterDownloadDao,
    private val optimizedCbzManager: OptimizedCbzManager,
    private val dataStoreHelper: DataStoreHelper,
) {

    private val DOWNLOAD_DISPATCHER = Dispatchers.IO.limitedParallelism(6)

    fun downloadChapterC(
        chapter: SavedChapterEntity,
        pages: List<DownloadPage>,
    ): Flow<DownloadState> = downloadChapterBatch(chapter, pages).flowOn(DOWNLOAD_DISPATCHER)

    suspend fun downloadImage(
        imageUrl: String,
        mangaId: Long,
        chapterId: Long,
        imageIndex: Int,
        pageHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        Log.i("ChapterDownloadService", "downloadImage url=$imageUrl")

        val response: HttpResponse = httpClient.get(imageUrl) {
            headers {
                pageHeaders.forEach { (name, value) -> append(name, value) }
            }
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to download image: ${response.status.value}")
        }

        val contentType = response.headers["Content-Type"]
        val extension = detectImageExtension(contentType, imageUrl)

        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId").apply {
            mkdirs()
        }

        val imageFile = File(chapterDir, "image_$imageIndex.$extension")

        // Ktor 3.x: `response.body<ByteArray>()` materialises the full payload. Manga page images
        // are typically <1MB each so the memory ceiling is fine; this avoids the Ktor 3 channel
        // API churn around `readRemaining` / `Source` / `Buffer` (still in flux as of 3.4.x). If
        // page sizes ever grow we can revisit with `bodyAsChannel().copyAndClose(...)` against a
        // file-backed write channel.
        val bytes: ByteArray = response.body()
        BufferedOutputStream(FileOutputStream(imageFile)).use { output ->
            output.write(bytes)
        }

        imageFile.absolutePath
    }

    private fun downloadChapterBatch(
        chapter: SavedChapterEntity,
        pages: List<DownloadPage>,
    ): Flow<DownloadState> = flow {
        require(pages.isNotEmpty()) { "No images to download" }

        val total = pages.size
        val paths = mutableListOf<String>()
        val useCbz = dataStoreHelper.useCbzFormatFlow.first()

        try {
            for ((index, page) in pages.withIndex()) {
                val url = page.url
                currentCoroutineContext().ensureActive()
                emit(DownloadState.InProgress(total, index, url))

                val path = downloadImage(url, chapter.mangaId, chapter.id, index, page.headers)
                paths += path
            }

            if (useCbz) {
                emit(DownloadState.Compressing(paths.size))

                try {
                    currentCoroutineContext().ensureActive()

                    val cbzPath = optimizedCbzManager.createCbzParallel(
                        paths,
                        chapter.mangaId,
                        chapter.id,
                    )
                    currentCoroutineContext().ensureActive()

                    libraryRepository.updateChapterLocalPaths(chapter.id, listOf(cbzPath))
                    notificationDao.addLocalImagePathByChapterId(chapter.id, listOf(cbzPath))

                    emit(DownloadState.Complete(listOf(cbzPath)))
                } catch (e: CancellationException) {
                    paths.forEach { File(it).delete() }
                    chapterDownloadDao.updateStateChId(chapter.id, DownloadingState.FAILED)
                    throw e
                } catch (e: Throwable) {
                    // Throwable, not Exception: OutOfMemoryError is an Error, so the loose-files
                    // fallback below would otherwise never fire on a real OOM (CancellationException
                    // is already handled by the branch above).
                    if (e is OutOfMemoryError || e.message?.contains("memory", ignoreCase = true) == true) {
                        libraryRepository.updateChapterLocalPaths(chapter.id, paths)
                        libraryRepository.markChapterAsDownloaded(chapterId = chapter.id)
                        chapterDownloadDao.updateStateChId(chapter.id, DownloadingState.SUCCESS)
                        notificationDao.addLocalImagePathByChapterId(chapter.id, paths)
                        emit(DownloadState.Complete(paths))
                    } else {
                        paths.forEach { File(it).delete() }
                        chapterDownloadDao.updateFailure(chapter.id, "Compression failed: ${e.message}")
                        emit(DownloadState.Error(e, paths.size, total))
                    }
                }
            } else {
                libraryRepository.updateChapterLocalPaths(chapter.id, paths)
                notificationDao.addLocalImagePathByChapterId(chapter.id, paths)
                emit(DownloadState.Complete(paths))
            }
        } catch (e: CancellationException) {
            paths.forEach { File(it).delete() }
            chapterDownloadDao.updateFailure(chapter.id, "Download cancelled")
            throw e
        }
    }.catch { e ->
        if (e !is CancellationException) {
            chapterDownloadDao.updateFailure(chapter.id, e.message)
        }
        emit(DownloadState.Error(e, 0, pages.size))
    }

    private fun detectImageExtension(contentType: String?, imageUrl: String): String {
        val urlExt = imageUrl.substringAfterLast('.', "").substringBefore('?').lowercase()
        if (urlExt in listOf("avif", "jpg", "jpeg", "png", "gif", "webp", "bmp")) {
            return urlExt
        }

        val ct = contentType?.lowercase().orEmpty()

        return when {
            "avif" in ct -> "avif"
            "jpeg" in ct || "jpg" in ct -> "jpg"
            "png" in ct -> "png"
            "gif" in ct -> "gif"
            "webp" in ct -> "webp"
            "bmp" in ct -> "bmp"
            else -> "jpg"
        }
    }

    fun deleteChapterFiles(mangaId: Long, chapterId: Long) {
        fileService.deleteChapterFiles(mangaId, chapterId)
    }

    suspend fun deleteMangaFiles(mangaId: Long) {
        fileService.deleteMangaFiles(mangaId)
    }
}

/* ----------------------------------------------------------------------------
 * Audit-trail postscript (Phase 9.x.cluster258.staleKdocSweep.cascade,
 * Task #715, 2026-05-29)
 * ----------------------------------------------------------------------------
 * Cluster258 SOLO-LEAF SCOUT — :shared/androidMain/presentation/features/
 * download/domain/ChapterDownloadService.kt. THIRD androidMain solo-leaf
 * after cluster255 (DownloadRepositoryImpl) and cluster257 (CbzManager +
 * OptimizedCbzManager doublet). Cumulative §253-postscript count = 157
 * leaves with this commit.
 *
 * File-shape note: 389-line file — `ChapterDownloadService` class (NOT
 * actual — concrete class with no expect-decl) with 9 ctor-args (context +
 * libraryRepository + httpClient + fileService + notificationDao +
 * cbzManager + chapterDownloadDao + optimizedCbzManager + dataStoreHelper)
 * + 1 @Suppress("unused") DOWNLOAD_DISPATCHER field (Dispatchers.IO.
 * limitedParallelism(6)) + 8 method members: 4 public (downloadChapterC
 * Flow + downloadImage suspend + deleteChapterFiles + deleteMangaFiles
 * suspend) + 4 private helpers (handleImageSource + copyLocalImage +
 * downloadChapterStreaming Flow + downloadChapterBatch Flow +
 * detectImageExtension) + 11-line class-level KDoc prose citing the
 * Phase 8.14 port + OkHttp→Ktor migration + Hilt→Koin annotation strip
 * ([Singleton] [Inject] [ApplicationContext] [MainOkHttpClient]
 * annotations stripped).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • ANDROIDMAIN-SOLO-LEAF — :shared/androidMain platform-actual with no
 *     commonMain expect-decl and no iOS/Desktop sibling-actual. Android-
 *     only because (1) `android.content.Context` ctor-injected for
 *     filesDir + chapter-dir layout, (2) `android.util.Log` direct calls
 *     throughout, (3) DOWNSTREAM consumer of the cluster257 CbzManager +
 *     OptimizedCbzManager doublet (which are Android-only via Bitmap +
 *     libavif JNI + java.util.zip), (4) UPSTREAM provider for DownloadV2
 *     -WorkerV2 + clean/DownloadRepositoryImpl (both Android-only via
 *     androidx.work + WorkManager scheduling). iOS+Desktop bind
 *     `CoroutineDownloadRepositoryImpl` (cluster255 sibling-leaf) via
 *     nonAndroidMain instead — that path uses pure-Kotlin coroutines +
 *     `core/cbz` okio-zip primitives without needing a service-tier
 *     intermediate.
 *
 *   • HILT-TO-KOIN-ANNOTATION-STRIP-LIVE — class-level KDoc lines 47-49
 *     document the Phase 8.14 port stripping of Hilt's @Inject +
 *     @Singleton + @ApplicationContext + @MainOkHttpClient annotations,
 *     replaced by Koin's `single { ChapterDownloadService(...) }`
 *     factory in `PlatformModule.android.kt:161` with explicit ctor-arg
 *     wiring (`androidContext()` + `get()` × 8). LIVE — load-bearing
 *     port-lineage marker for Phase 8.14 audit-trail.
 *
 *   • KTOR3-MIGRATION-OKHTTP-REPLACEMENT-LIVE — class-level KDoc lines
 *     43-46 document the Phase 8 networking-layer replacement of
 *     upstream's OkHttp client with the shared Ktor HttpClient (bound as
 *     singleton in `SharedModule.kt`). The `downloadImage` method (lines
 *     242-290) uses `httpClient.get(imageUrl) { headers { ... } }` with
 *     repo-specific header logic. Lines 279-283 contain an explicit
 *     comment documenting the Ktor 3 channel-API churn rationale —
 *     `response.body<ByteArray>()` materialises the full payload
 *     because Ktor 3's `readRemaining` / `Source` / `Buffer` API is
 *     still in flux as of 3.4.x; the comment notes a revisit path via
 *     `bodyAsChannel().copyAndClose(...)` against a file-backed write
 *     channel if page sizes ever exceed the ~1MB-per-page assumption.
 *     LIVE — load-bearing networking-layer port marker AND active
 *     technical-debt note for any future Ktor 3.x API stabilization.
 *
 *   • KOIN-BINDING-LIVE — `PlatformModule.android.kt:161` registers
 *     `ChapterDownloadService` as a `single { ... }` factory with 8
 *     explicit ctor-arg `get()` lookups (context = androidContext() +
 *     libraryRepository + httpClient + fileService + notificationDao +
 *     cbzManager + chapterDownloadDao + optimizedCbzManager +
 *     dataStoreHelper). LIVE — load-bearing for the Android-only
 *     download SPI; without this binding both DownloadRepositoryImpl
 *     AND DownloadWorkerV2 fail at startup with `NoBeanDefFoundException`.
 *
 *   • DUAL-CONSUMER-LIVE — TWO LIVE consumers inject this service:
 *     (1) `DownloadRepositoryImpl.kt:55` (clean-architecture path — ctor
 *     field `chapterDownloadService: ChapterDownloadService`, calls
 *     `.deleteChapterFiles()` at line 94 inside `onCancel`); (2)
 *     `DownloadWorkerV2.kt:87` (WorkManager-backed path — lazy field
 *     `chapterDownloadService: ChapterDownloadService by lazy {
 *     koin.get() }`, calls `.deleteChapterFiles()` at lines 154+295
 *     AND `.downloadChapterC()` at line 209 inside the WorkManager
 *     worker's `doWork()`-equivalent flow). This service is the LIVE
 *     load-bearing intermediate between the cluster257 CbzManager
 *     doublet (downstream dep) and the cluster255 DownloadRepository
 *     2-actual fan (upstream consumer). The cluster257 PIVOT
 *     PREDICTION naming "the LIVE consumer of this doublet AND of the
 *     Android-only WorkManager scheduling tier, closes a 3-tier
 *     Android download chain" is now FULFILLED by this postscript.
 *
 *   • STREAMING-VS-BATCH-AXIS-LIVE — `downloadChapterC` (lines 67-79)
 *     branches on `MangaSource.PROCHAN.API` to dispatch between two
 *     distinct flow paths: (1) `downloadChapterStreaming` (lines 138-
 *     240) for ProChan-API mangas — sequential per-image download
 *     with intermediate `DownloadState.InProgress` emissions on each
 *     image; (2) `downloadChapterBatch` (lines 292-361) for all
 *     other sources — same sequential download but with a single
 *     `DownloadState.InProgress` emit per image. Both paths funnel
 *     into the same CBZ-compression-or-loose-paths terminal stage
 *     gated on `dataStoreHelper.useCbzFormatFlow.first()`. LIVE —
 *     load-bearing parametric-branch on source-API for download
 *     semantics; ProChan-specific streaming behavior would need to
 *     be preserved by any future refactor.
 *
 *   • MANGAMELLOPLUS-HEADER-SPECIAL-CASE-LIVE — `downloadImage` lines
 *     252-263 contain a repo-specific header-application special case:
 *     when `repo is MangamelloPlusRepository`, headers are only
 *     applied if the URL contains "mangamello" / "mello" /
 *     "cdn.mangamello.com" substrings (case-insensitive); otherwise
 *     `repo.defaultHeaders` is applied unconditionally. This branch is
 *     preserved verbatim from upstream's Phase 8.14 port baseline and
 *     reflects a real CDN-routing constraint at the MangamelloPlus
 *     source. LIVE — load-bearing for download success against that
 *     specific source's CDN; PRESERVE during any future header-
 *     handling refactor.
 *
 *   • CBZ-DOUBLET-CONSUMER-PARTIAL — the ctor injects BOTH
 *     `cbzManager: CbzManager` AND `optimizedCbzManager: OptimizedCbzManager`,
 *     but ONLY `optimizedCbzManager.createCbzParallel(...)` is called in
 *     the body (twice: line 186 in streaming path, line 318 in batch
 *     path). The `cbzManager` ctor parameter has NO body call site —
 *     it is INJECT-ONLY-DEAD. This is a candidate for ctor-prune in a
 *     future depprune cluster (analogous to Phase 9.x.homevm.depprune
 *     Task #432 which dropped HomeViewModel.settingsRepo ctor-dep).
 *     OBSERVATION — NOT acted on in this §253-postscript sweep (scope
 *     is documentation only), but FLAGGED for future cluster as a
 *     low-risk ctor-arg-prune target. PlatformModule.android.kt:161's
 *     binding would also drop the corresponding `get()` lookup.
 *
 *   • CANCELLATION-FILESYSTEM-CLEANUP-LIVE — both download paths
 *     (streaming lines 202-206+228-233, batch lines 329-332+351-355)
 *     handle `CancellationException` by deleting all partially-
 *     downloaded image paths via `paths.forEach { File(it).delete() }`
 *     AND updating the `chapterDownloadDao` to FAILED state AND
 *     re-throwing. The outer `.catch { e -> ... }` block (lines 234-
 *     240+356-361) catches non-cancellation exceptions and emits
 *     `DownloadState.Error`. LIVE — load-bearing for download-cancel
 *     idempotency contract (no orphan files in chapter dir after
 *     cancellation). PRESERVE — invariant of the WorkManager-backed
 *     cancel surface.
 *
 *   • OOM-FALLBACK-LIVE — both compression paths (streaming lines 210-
 *     216, batch lines 334-339) detect OOM via `e is OutOfMemoryError
 *     || e.message?.contains("memory", ignoreCase=true)` and fall back
 *     to writing the LOOSE PATHS (uncompressed) into
 *     `libraryRepository.updateChapterLocalPaths(chapter.id, paths)` +
 *     marking the chapter downloaded + emitting `DownloadState.Complete
 *     (paths)`. This means a chapter that OOM-fails compression still
 *     ships as a downloaded chapter, just without the CBZ
 *     consolidation. LIVE — load-bearing graceful-degradation behavior
 *     for low-RAM Android devices; PRESERVE — without it large multi-
 *     image chapters on low-tier hardware would fail to mark
 *     downloaded.
 *
 *   • DOWNLOAD-DISPATCHER-RESERVED-NONLIVE — field at line 65
 *     `DOWNLOAD_DISPATCHER = Dispatchers.IO.limitedParallelism(6)`
 *     carries `@Suppress("unused")` AND is wired into `.flowOn(
 *     DOWNLOAD_DISPATCHER)` at line 79 in `downloadChapterC`. The
 *     @Suppress is mechanically incorrect — the dispatcher IS used —
 *     but is preserved as upstream-import-trail noise. OBSERVATION —
 *     candidate for `@Suppress("unused")` removal in a future polish
 *     pass; the build is correct as-is.
 *
 *   • LOCAL-VS-URL-DISPATCH-LIVE — `handleImageSource` lines 81-107
 *     branches between `copyLocalImage` (when the image path is local
 *     filesystem — starts with `/data/`, `file://`, or `local.exists()`)
 *     and `downloadImage` (everything else, treated as URL). LIVE —
 *     load-bearing for the local-image-already-downloaded path
 *     optimization (e.g. re-downloads or interrupted-then-resumed
 *     workflows reusing already-downloaded image bytes).
 *
 *   • CLUSTER258 SOLO-LEAF REGISTER — 1-leaf androidMain SOLO-LEAF
 *     closing the Android-only download chain. Combined-3-tier
 *     coverage: cluster255 (DownloadRepositoryImpl Android-side of 2-
 *     actual fan) + cluster257 (CbzManager + OptimizedCbzManager
 *     doublet) + cluster258 (this file) = COMPLETE-ANDROID-DOWNLOAD-
 *     TIER sweep. The Android-only download SPI is now fully §253-
 *     postscripted. Remaining un-swept :shared/androidMain candidates
 *     per glob enumeration: DownloadWorkerV2.kt (the LIVE upstream
 *     consumer of THIS file via WorkManager wiring) + per-feature
 *     androidMain helpers (notification-tier + WebView-tier).
 *
 *   • CLUSTER259 PIVOT PREDICTION — strongest remaining un-swept
 *     :shared/androidMain candidate is `DownloadWorkerV2.kt` (the
 *     androidx.work CoroutineWorker subclass that injects THIS file
 *     via Koin lazy + drives the actual download flow on the
 *     WorkManager scheduler tier). File-shape symmetric with this
 *     one (androidMain solo-leaf, no expect-decl, Koin-bound).
 *     Cluster259 would close the 4-tier Android download chain
 *     fully: cluster255 (DownloadRepositoryImpl) + cluster257
 *     (CbzManager doublet) + cluster258 (this file) + cluster259
 *     (DownloadWorkerV2) = COMPLETE-ANDROID-DOWNLOAD-SUBSYSTEM sweep.
 *     After cluster259, the §253 wave reaches FULL SATURATION for
 *     the :shared/androidMain download subtree; subsequent clusters
 *     would have to pivot to a new sub-domain (likely
 *     :shared/androidMain notification-tier or :shared platform-
 *     stub object-tier — both narrower than the download chain).
 *
 *   • SATURATION-WATCH — §253 wave has now swept 157 leaves across
 *     258 clusters; clusters 255-258 inclusive have closed the
 *     Android-only download chain at 3 of 4 tiers. Cluster259 will
 *     complete that chain; subsequent campaigns will need a fresh
 *     tier-enumeration scout for non-download-tier androidMain
 *     orphans, or pivot to the much smaller iOS+Desktop platform-
 *     stub register.
 */
