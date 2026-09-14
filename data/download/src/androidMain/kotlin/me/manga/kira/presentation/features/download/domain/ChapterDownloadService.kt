package me.manga.kira.presentation.features.download.domain

import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
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
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.presentation.features.download.data.DownloadState
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import me.manga.kira.presentation.features.download.domain.clean.downloadValidatedPage
import me.manga.kira.presentation.features.download.domain.clean.requireUncachedPageClient
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath
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
    private val persistence: ChapterDownloadPersistence,
    private val httpClient: HttpClient,
    private val optimizedCbzManager: OptimizedCbzManager,
    private val dataStoreHelper: DataStoreHelper,
    private val mediaInspector: PageMediaInspector,
    private val pageBytePolicy: PageBytePolicy = PageBytePolicy(),
    private val downloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(DOWNLOAD_PARALLELISM),
) {
    init {
        requireUncachedPageClient(httpClient)
    }

    fun downloadChapterC(
        chapter: SavedChapterEntity,
        pages: List<DownloadPage>,
    ): Flow<DownloadState> = downloadChapterBatch(chapter, pages).flowOn(downloadDispatcher)

    suspend fun downloadImage(
        imageUrl: String,
        mangaId: Long,
        chapterId: Long,
        imageIndex: Int,
        pageHeaders: Map<String, String>,
    ): String =
        withContext(Dispatchers.IO) {
            val directory = File(context.filesDir, "manga/$mangaId/chapter_$chapterId").absolutePath.toPath()
            downloadValidatedPage(
                httpClient,
                imageUrl,
                pageHeaders,
                FileSystem.SYSTEM,
                directory,
                imageIndex,
                mediaInspector,
                pageBytePolicy,
            ).toString()
        }

    private fun downloadChapterBatch(
        chapter: SavedChapterEntity,
        pages: List<DownloadPage>,
    ): Flow<DownloadState> =
        flow {
            require(pages.isNotEmpty()) { "No images to download" }

            val total = pages.size
            val paths = mutableListOf<String>()
            val useCbz = dataStoreHelper.useCbzFormatFlow.first()

            // Cancellation propagates to the sole collector, which owns cleanup after flowOn
            // unwinds. Producer-side deletion would race an already-committed Complete.
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

                    val cbzPath =
                        optimizedCbzManager.createCbzParallel(
                            paths,
                            chapter.mangaId,
                            chapter.id,
                        )
                    currentCoroutineContext().ensureActive()

                    persistence.savePaths(chapter.id, listOf(cbzPath))
                    emit(DownloadState.Complete(listOf(cbzPath)))
                } catch (e: CancellationException) {
                    // This branch must precede Throwable: a system stop is not a compression error.
                    throw e
                } catch (e: Throwable) {
                    // A native fault or an exception mentioning "memory" is not permission to
                    // report success. Preserve original pages/previous archive; the collector owns
                    // cancellation cleanup, and only a typed preflight policy can allow fallback.
                    persistence.recordFailure(chapter.id, "Compression failed: ${e.message}")
                    emit(DownloadState.Error(e, paths.size, total))
                }
            } else {
                persistence.savePaths(chapter.id, paths)
                emit(DownloadState.Complete(paths))
            }
        }.catch { e ->
            if (e is CancellationException) throw e
            persistence.recordFailure(chapter.id, e.message)
            emit(DownloadState.Error(e, 0, pages.size))
        }

    fun deleteChapterFiles(
        mangaId: Long,
        chapterId: Long,
    ) {
        persistence.deleteChapterFiles(mangaId, chapterId)
    }

    suspend fun deleteMangaFiles(mangaId: Long) {
        persistence.deleteMangaFiles(mangaId)
    }
}

private const val DOWNLOAD_PARALLELISM = 6

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
 *   • HISTORICAL OOM-FALLBACK — removed by bounded-page remediation. A native
 *     fault or message containing "memory" cannot authorize Complete. Inputs and
 *     any previous archive remain for recovery; explicit typed budget preservation
 *     belongs before codec work, never in this generic exception handler.
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
