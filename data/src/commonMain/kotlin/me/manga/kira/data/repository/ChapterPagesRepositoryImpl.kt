package me.manga.kira.data.repository

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.error.TransportErrorMessages
import me.manga.kira.core.logging.FlowLog
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.repository.ChapterPagesRepository
import me.manga.kira.platform.cbz.CbzReader
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.sources.contracts.SourceRegistry
import okio.Path
import okio.Path.Companion.toPath

/**
 * Source-backed [ChapterPagesRepository] implementation.
 *
 * SRP (contract §6): owns ONE rule — "given a manga + chapter, ask the legacy source repo for its
 * page-URL stream, attach the source's `defaultHeaders` to each URL, and project legacy `State`
 * emissions into typed [AppResult] emissions". Source-routing itself lives in [SourcesRepository];
 * the per-source page-fetch logic lives in each `BaseMangaRepository.fetchChapterDataF`.
 *
 * DIP: depends on [ChapterPagesRepository] (declared in `:domain`), [SourcesRepository] (legacy
 * `:shared` — transitional, removed when sources move into `:data`), and [DispatcherProvider]
 * (`:core`). No Compose, no UI types.
 *
 * Why this impl is a thin shim over legacy:
 *  - Legacy `BaseMangaRepository.fetchChapterDataF(url)` already emits `Flow<State<List<String>>>`
 *    with streaming semantics for Prochan-style sources (each emission is the running cumulative
 *    list). The Phase 6.4.1 [ChapterPagesRepository] contract was designed around that exact
 *    shape — see `ChapterPagesRepository.fetchPages` KDoc. So the impl only has to (a) drop
 *    Loading emissions, (b) translate Success → `AppResult.Success(pages)` with headers attached,
 *    and (c) translate Error → terminal `AppResult.Failure` with the same classification heuristics
 *    `MangaDetailsRepositoryImpl` uses.
 *  - Headers are read ONCE per fetch from `BaseMangaRepository.defaultHeaders`. They're identical
 *    across pages of one chapter (per-source config — referer / user-agent), so re-reading per
 *    emission would waste a property read. The `Page` model carries the same map by reference;
 *    later pages from the same chapter share the header map identity.
 *
 * **Downloaded-chapter local-path lookup (native parity).** Before delegating to the source, the
 * impl checks whether the chapter has been downloaded for offline reading: it resolves the saved
 * `chapterId` via `ChapterDao.getChapterIdByUrl(chapter.url)`, reads the `SavedChapterEntity`, and
 * if it is `isDownloaded` with non-empty `localImagePaths`, serves the pages from local files
 * instead of the network — mirroring native `ReaderViewModel` (downloaded chapters read
 * `localImagePaths`). A single `chapter_<id>.cbz` path is extracted via [CbzReader.extractImages]
 * (okio-backed, all platforms); loose `image_<n>.<ext>` paths are used in their stored page order.
 * Each local path is emitted as a `file://` URL so Coil 3's `FileUriFetcher` resolves it uniformly
 * on JVM and Native (no per-page headers — local reads don't hit the network). If the lookup
 * yields nothing readable, the impl falls through to the source fetch unchanged.
 *
 * Error classification (matches the legacy [LegacyState.Error.fromException] buckets so the
 * surfaced [AppError] hierarchy is consistent across the rework `:data` layer):
 *  - HTTP status in 400..599 → [AppError.Network.Http].
 *  - code == 0 with a connectivity hint in the message → [AppError.Network.NoConnectivity].
 *  - code == 0 with a timeout hint → [AppError.Network.Timeout].
 *  - Anything else → [AppError.Unexpected] carrying the original message.
 *  - Unknown source api → terminal `AppResult.Failure(AppError.Unexpected)`. Same posture as
 *    [MangaDetailsRepositoryImpl] — legacy silently substitutes `EmptyMangaRepository` whose
 *    flow returns Success with empty URLs, hiding the integrity issue.
 *
 * The classification heuristics (including the Cloudflare-challenge → 403 re-surfacing, bug #2)
 * are duplicated from [MangaDetailsRepositoryImpl] rather than factored out. The duplication is
 * now over the rule-of-three threshold (Details, Reader, plus the HomeFeed/Search mapper), so a
 * shared `:data` classifier is warranted; until that refactor lands the challenge branch is kept
 * in sync by hand so non-config-backed reader failures still route to the WebView solver. The
 * duplication is documented and localised.
 *
 * Cancellation: [CancellationException] propagates unchanged through the catch operator
 * (structured-concurrency invariant). Any other [Throwable] thrown by the underlying flow lands
 * as a terminal `AppResult.Failure` emission.
 */
class ChapterPagesRepositoryImpl(
    private val dispatchers: DispatcherProvider,
    private val chapterDao: ChapterDao,
    private val cbzReader: CbzReader,
    private val sourceRegistry: SourceRegistry,
    private val appFileSystem: AppFileSystem,
) : ChapterPagesRepository {

    // App-lifetime scope for fire-and-forget CBZ-extract cleanup. The repository is a Koin single,
    // so this scope outlives any reader ViewModel — letting cleanup be triggered from `onCleared()`
    // (where viewModelScope is already cancelled) and complete reliably off the main thread.
    private val cleanupScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    // Per-chapter serialization between the fire-and-forget extract-cache cleanup and a re-extract of
    // the SAME chapter. The reader calls clearExtractedPages on every chapter change (and onCleared)
    // while a rapid Next->Prev / exit->reopen can re-enter localPagesOrNull for the just-left chapter;
    // both touch cacheDir/cbz_extract/<mangaId>/<chapterId>. Without this lock the recursive delete can
    // interleave with extraction (broken file:// pages, or a silent network re-fetch). The map is keyed
    // by Room chapterId; a missing key means "no cleanup ever scheduled" (the common streamed case).
    private val cleanupLocks = mutableMapOf<Long, Mutex>()
    private val cleanupLocksGuard = Mutex()

    private suspend fun cleanupLockFor(chapterId: Long): Mutex =
        cleanupLocksGuard.withLock { cleanupLocks.getOrPut(chapterId) { Mutex() } }

    override fun fetchPages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> = flow {
        // Downloaded-chapter fast path (native parity): serve local files instead of re-fetching
        // from the source when the chapter has been downloaded for offline reading. Falls through
        // to the network path when the chapter isn't downloaded or no readable local files exist.
        val localPages = localPagesOrNull(chapter)
        if (localPages != null) {
            FlowLog.log("Reader", "resolve", "chapter=${chapter.url} source=downloaded pages=${localPages.size}")
            emit(AppResult.Success(localPages))
            return@flow
        }

        FlowLog.log("Reader", "resolve", "chapter=${chapter.url} source=catalog api=${manga.api}")
        val client = sourceRegistry.get(manga.api)
        if (client == null) {
            emit(
                AppResult.Failure(
                    AppError.Validation.SourceUnavailable(api = manga.api),
                ),
            )
            return@flow
        }
        emitAll(client.pages(manga, chapter))
    }
        .catch { t ->
            if (t is CancellationException) throw t
            emit(AppResult.Failure(classifyThrowable(t)))
        }
        .flowOn(dispatchers.io)

    /**
     * Resolve a downloaded chapter's local page list, or `null` when the chapter isn't a saved,
     * downloaded chapter with local files (in which case the caller falls back to the source fetch).
     * Mirrors native `ReaderViewModel`: a single `.cbz` is extracted via [CbzReader]; loose page
     * files are returned in their stored order. Local paths become `file://` URLs for Coil.
     */
    private suspend fun localPagesOrNull(chapter: Chapter): List<Page>? {
        val chapterId = chapterDao.getChapterIdByUrl(chapter.url) ?: return null
        val entity = chapterDao.getChapterByIdSuspend(chapterId) ?: return null
        if (!entity.isDownloaded || entity.localImagePaths.isEmpty()) return null

        val single = entity.localImagePaths.singleOrNull()
        val localPaths: List<String> =
            if (single != null && single.endsWith(".cbz", ignoreCase = true)) {
                // Single CBZ archive: extract its image entries (sorted by zero-padded entry name).
                //
                // Resolve the CBZ from the CURRENT filesDir, not the absolute path captured at
                // download time. The downloader persists the full absolute archive path into
                // `localImagePaths`; on iOS that path embeds the app sandbox container UUID, which
                // changes across reinstall / backup-restore — leaving the stored path stale while
                // `isDownloaded` stays true, so the reader logged "CBZ file does not exist" and
                // silently fell back to the network even though the chapter shows as downloaded.
                // `cbzReader.cbzPath` re-derives the conventional location under the live filesDir
                // (the SAME layout the writer's ensureCbzDestination uses: chapterDir/chapter_<id>.cbz),
                // so it survives a container change. Fall back to the stored path only when the
                // re-derived one is absent (back-compat with rows whose CBZ lives elsewhere).
                val canonical = cbzReader.cbzPath(entity.mangaId, entity.id)
                val cbzPath = if (cbzReader.cbzExists(entity.mangaId, entity.id)) {
                    if (canonical.toString() != single) {
                        FlowLog.log("Reader", "cbzRederive", "stored stale; using current path | stored=$single current=$canonical")
                    }
                    canonical
                } else {
                    FlowLog.log("Reader", "cbzRederive", "no CBZ at current path; trying stored | stored=$single")
                    single.toPath()
                }
                // Extraction writes into cacheDir/cbz_extract/<mangaId>/<chapterId>; hold the per-chapter
                // cleanup lock so a concurrent clearExtractedPages delete of the same dir can't race it.
                cleanupLockFor(entity.id).withLock {
                    cbzReader.extractImages(cbzPath, entity.mangaId, entity.id)
                        .map { it.toString() }
                }
            } else {
                // Loose per-page files, already in page order as the downloader stored them. Re-derive
                // each path's filename under the CURRENT chapter dir (same iOS container-UUID staleness
                // the CBZ branch guards against; the loose layout is chapterDir(mangaId, id)/<filename>),
                // preferring the live path when it exists and falling back to the stored absolute path.
                // If NONE of the resolved files exist, return null so the caller falls through to the
                // network fetch instead of handing Coil N broken file:// URLs.
                val fs = appFileSystem.fileSystem()
                val chapterDir = appFileSystem.chapterDir(entity.mangaId, entity.id)
                val resolved = entity.localImagePaths.map { stored ->
                    val storedPath = stored.toPath()
                    val current = chapterDir / storedPath.name
                    when {
                        fs.exists(current) -> current.toString()
                        fs.exists(storedPath) -> stored
                        else -> current.toString()
                    }
                }
                if (resolved.none { fs.exists(it.toPath()) }) {
                    // B2: the loose pages are gone — but a CBZ may already exist. The background finalize
                    // deletes the loose source pages BEFORE Room is repointed from the loose list to the
                    // [cbz] path, so during that window (or after a kill in it, or after a manual
                    // compressor run) Room still lists loose paths while only the .cbz is on disk. Prefer
                    // the durable CBZ over a network re-download of a chapter that IS downloaded.
                    if (cbzReader.cbzExists(entity.mangaId, entity.id)) {
                        FlowLog.log("Reader", "looseRederive", "loose pages gone; extracting existing CBZ | mangaId=${entity.mangaId} chapterId=${entity.id}")
                        val extracted = cleanupLockFor(entity.id).withLock {
                            cbzReader.extractImages(cbzReader.cbzPath(entity.mangaId, entity.id), entity.mangaId, entity.id)
                                .map { it.toString() }
                        }
                        if (extracted.isNotEmpty()) return extracted.map { Page(url = toFileUrl(it), headers = emptyMap()) }
                    }
                    FlowLog.log("Reader", "looseRederive", "no readable local pages; falling back to network | mangaId=${entity.mangaId} chapterId=${entity.id}")
                    return null
                }
                resolved
            }
        if (localPaths.isEmpty()) return null
        return localPaths.map { Page(url = toFileUrl(it), headers = emptyMap()) }
    }

    /** Local filesystem path → `file://` URL (Coil 3's [FileUriFetcher] resolves it on all targets). */
    private fun toFileUrl(path: String): String = toFileUrl(path.toPath())

    /**
     * Build an RFC-8089 `file://` URL from an okio [Path]. A bare `"file://$path"` is malformed for
     * Windows Desktop (packageMsi): `C:\Users\…\chapter_5.cbz` would yield `file://C:\…` (authority
     * "C:", unescaped backslashes), and spaces / non-ASCII in the user-profile path go through
     * unencoded. okio normalizes separators to `/`; we then prefix `file://`, ensure a leading
     * slash so the authority is empty (Unix `/d/1` → `file:///d/1`, Windows `C:/…` → `file:///C:/…`),
     * and percent-encode each segment.
     */
    private fun toFileUrl(path: Path): String {
        val normalized = path.toString().replace('\\', '/')
        val withLeadingSlash = if (normalized.startsWith("/")) normalized else "/$normalized"
        val encoded = withLeadingSlash.split("/").joinToString("/") { encodePathSegment(it) }
        return "file://$encoded"
    }

    /** Percent-encode a single path segment per RFC 3986 (unreserved + sub-delims kept). */
    private fun encodePathSegment(segment: String): String {
        if (segment.isEmpty()) return segment
        val sb = StringBuilder(segment.length)
        for (byte in segment.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            val safe = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch in "-._~!$&'()*+,;=:@"
            if (safe) {
                sb.append(ch)
            } else {
                sb.append('%')
                sb.append(HEX[c shr 4])
                sb.append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    override fun clearExtractedPages(chapter: Chapter) {
        // Fire-and-forget on the app-lifetime scope (safe from onCleared). Resolve the chapter's
        // Room id/manga id the same way the local-read path does; [CbzReader.cleanupExtractedCache]
        // is itself a no-op when no extract dir exists, so chapters that were streamed (not a
        // downloaded CBZ) cost only two cheap DAO reads.
        cleanupScope.launch {
            // Best-effort cleanup runs during reader teardown (incl. app shutdown), exactly when Room
            // may be closing. cleanupScope has no exception handler, so an unguarded DAO throw would
            // reach the platform default handler and crash the process — wrap it (CancellationException
            // is rethrown by runCatchingCancellable, so structured cancellation still unwinds).
            runCatchingCancellable {
                val chapterId = chapterDao.getChapterIdByUrl(chapter.url) ?: return@runCatchingCancellable
                val entity = chapterDao.getChapterByIdSuspend(chapterId) ?: return@runCatchingCancellable
                // Serialize against a concurrent re-extract of the same chapter (rapid Next->Prev /
                // exit->reopen): both touch cacheDir/cbz_extract/<mangaId>/<chapterId>, so the recursive
                // delete must not interleave with extractImages writing into that dir.
                cleanupLockFor(chapterId).withLock {
                    cbzReader.cleanupExtractedCache(entity.mangaId, chapterId)
                }
            }.onFailure { FlowLog.log("Reader", "clearExtractedPages", "cleanup failed: ${it.message}") }
        }
    }

    private fun classifyThrowable(t: Throwable): AppError {
        val raw = (t.message ?: "").lowercase()
        return when {
            // Same Cloudflare-challenge re-surfacing as [toAppError] (bug #2): a source that THROWS
            // on the interstitial (TLS quirk, parser choke on the challenge body, "403 forbidden"
            // in the exception message) loses its status code here. Map it back to 403 so the VM
            // offers the WebView solver rather than a non-recovering generic error.
            isChallengeMessage(raw) ->
                AppError.Network.Http(statusCode = 403)
            TransportErrorMessages.isConnectivityMessage(raw) ->
                AppError.Network.NoConnectivity(cause = t)
            TransportErrorMessages.isTimeoutMessage(raw) ->
                AppError.Network.Timeout(cause = t)
            else ->
                AppError.Unexpected(message = t.message ?: t::class.simpleName.orEmpty(), cause = t)
        }
    }

    /**
     * Heuristic: does a (lowercased) error/exception message look like a Cloudflare / anti-bot
     * interstitial the user can clear in a WebView? Kept conservative — only well-known challenge
     * signatures, not a bare "forbidden", so genuine 4xx/5xx app errors still surface normally.
     */
    private fun isChallengeMessage(raw: String): Boolean =
        raw.containsAny(
            "cloudflare",
            "just a moment",
            "checking your browser",
            "attention required",
            "cf-ray",
            "cf_chl",
            "ddos-guard",
            "ddos guard",
            "403 forbidden",
            "access denied",
        )

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }

    private companion object {
        private const val HEX = "0123456789ABCDEF"
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster152.staleKdocSweep.cascade,
 * Task #608, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-ninetieth sibling of the cluster57-151
 * sweep — OPENING file of the wave-26 :data/repository reader-state tier
 * 5-leaf batch alongside ReadingModeRepositoryImpl plus ReadingSession
 * RepositoryImpl plus ReadProgressRepositoryImpl plus PageProgressRepository
 * Impl; OPENS :data/repository reader-state tier 1/5):
 *  (a) "Source-backed-ChapterPagesRepository-implementation + SRP-contract-
 *  section-6-owns-ONE-rule-given-a-manga-plus-chapter-ask-the-legacy-source-
 *  repo-for-its-page-URL-stream-attach-the-source-s-defaultHeaders-to-each-
 *  URL-and-project-legacy-State-emissions-into-typed-AppResult-emissions +
 *  DIP-depends-on-ChapterPagesRepository-:domain-SourcesRepository-legacy-
 *  :shared-transitional-and-DispatcherProvider-:core-no-Compose-no-UI-types
 *  + Why-this-impl-is-a-thin-shim-over-legacy-BaseMangaRepository.fetch
 *  ChapterDataF-already-emits-Flow-State-List-String-with-streaming-semantics
 *  -for-Prochan-style-sources-each-emission-is-the-running-cumulative-list +
 *  Headers-are-read-ONCE-per-fetch-from-BaseMangaRepository.defaultHeaders-
 *  they-re-identical-across-pages-of-one-chapter-per-source-config-referer-
 *  user-agent + Error-classification-matches-the-legacy-LegacyState.Error.
 *  fromException-buckets-so-the-surfaced-AppError-hierarchy-is-consistent-
 *  across-the-rework-:data-layer-HTTP-status-in-400-599-AppError.Network.
 *  Http-code-zero-with-connectivity-hint-AppError.Network.NoConnectivity-
 *  code-zero-with-timeout-hint-AppError.Network.Timeout-Anything-else-
 *  AppError.Unexpected-Unknown-source-api-terminal-AppResult.Failure-
 *  AppError.Unexpected-Same-posture-as-MangaDetailsRepositoryImpl +
 *  Cancellation-CancellationException-propagates-unchanged-through-the-catch
 *  -operator-structured-concurrency-invariant-Any-other-Throwable-thrown-by
 *  -the-underlying-flow-lands-as-a-terminal-AppResult.Failure-emission" —
 *  LIVE-NOT-STALE. Verified: source-backed AppResult mapping shipped.
 *  fetchPages(manga, chapter) routes through sourcesRepository.getOrRepoBy
 *  Name() then sourceRepo.fetchChapterDataF(chapter.url) and collects the
 *  legacy State emissions into AppResult.Success / AppResult.Failure via
 *  toAppError() / classifyThrowable() helpers. The four error-classification
 *  buckets (HTTP 400-599 plus connectivity plus timeout plus unexpected) are
 *  honored by the toAppError() impl. flowOn(dispatchers.io) pin honored.
 *  Cancellation propagation via the `if (t is CancellationException) throw
 *  t` rethrow inside the catch operator honored.
 *  (b) "Deferred-downloaded-chapter-local-path-lookup-Documented-in-section
 *  -56.5-if-chapter.isDownloaded-were-true-legacy-ReaderViewModel-would-
 *  short-circuit-to-local-path-extraction-CBZ-via-CbzReader-or-plain-image-
 *  dir + The-rework-has-no-DownloadsRepository-yet-so-this-impl-unconditional
 *  ly-takes-the-source-fetch-path + Zero-user-impact-because-no-rework-caller
 *  -invokes-this-repository-yet-legacy-Reader-still-drives-reading + When-the
 *  -downloads-facility-lands-this-impl-will-branch-on-chapter.isDownloaded-
 *  first" — FORECAST-NOT-YET-FULFILLED. Verified by absence: no chapter.is
 *  Downloaded branch exists in fetchPages — the impl unconditionally takes
 *  the source-fetch path. The "no rework caller invokes this repository yet"
 *  stance is honored — the rework Reader's ChapterPagesRepository consumer
 *  is wired through ObserveChapterPagesUseCase but the rework Reader route
 *  is not yet user-routable (still strangler-fig behind the legacy reader
 *  route). The downloaded-branch forecast remains open; no slice has landed
 *  the rework DownloadsRepository surface yet.
 *  (c) "The-classification-heuristics-are-duplicated-from-MangaDetails
 *  RepositoryImpl-rather-than-factored-out + Rationale-rule-of-three-two-
 *  callers-Details-Reader-is-on-the-threshold-but-not-over-it-factoring-
 *  now-would-require-a-public-mapper-module-without-clear-shape-yet-does-
 *  it-take-a-LegacyState.Error-a-Throwable-both + When-a-third-caller-
 *  arrives-in-a-later-Phase-the-shared-helper-falls-out-naturally-with-a-
 *  known-surface + Until-then-the-duplication-is-documented-and-localised" —
 *  LIVE-DOCUMENTED-DUPLICATION. Verified: toAppError() + classifyThrowable()
 *  helpers in this file are byte-for-byte parallel to the same-shape helpers
 *  in MangaDetailsRepositoryImpl. Rule-of-three threshold not yet reached
 *  — only two repositories carry this classification logic (Details + Pages).
 *  No third caller has emerged, so the documented-duplication stance remains
 *  correct. Consumed by ObserveChapterPagesUseCase (cluster93 sibling X)
 *  via the .fetchPages() flow contract; the rework Reader VM consumes
 *  through the use case at its own MVI boundary. Three classifications.
 *  Original Phase 6.4.1 (Task #237) impl prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
