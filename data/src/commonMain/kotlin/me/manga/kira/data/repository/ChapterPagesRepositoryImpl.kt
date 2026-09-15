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
import me.manga.kira.sources.contracts.SourceRegistry
import okio.Path
import okio.Path.Companion.toPath

/**
 * Source-backed [ChapterPagesRepository] implementation.
 *
 * Online pages always resolve through the active generic [SourceRegistry]. An absent source fails
 * closed with [AppError.Validation.SourceUnavailable]; there is no legacy or inferred fallback.
 *
 * **Downloaded-chapter local-path lookup (native parity).** Before delegating to the source, the
 * impl checks whether the chapter has been downloaded for offline reading: it resolves the saved
 * `chapterId` via `ChapterDao.getChapterIdByUrl(manga.url, chapter.url)` and reads the saved entity.
 * If it is `isDownloaded` with non-empty `localImagePaths`, it serves the pages from local files
 * instead of the network — mirroring native `ReaderViewModel` (downloaded chapters read
 * `localImagePaths`). A single `chapter_<id>.cbz` path is extracted via [CbzReader.extractImages]
 * (okio-backed, all platforms); loose `image_<n>.<ext>` paths are used in their stored page order.
 * Each local path is emitted as a `file://` URL so Coil 3's `FileUriFetcher` resolves it uniformly
 * on JVM and Native (no per-page headers — local reads don't hit the network). If ANY local page is
 * missing, invalid, unreadable or above policy, try a validated CBZ then source recovery unchanged.
 *
 * Error classification keeps the surfaced [AppError] hierarchy consistent across `:data`:
 *  - HTTP status in 400..599 → [AppError.Network.Http].
 *  - code == 0 with a connectivity hint in the message → [AppError.Network.NoConnectivity].
 *  - code == 0 with a timeout hint → [AppError.Network.Timeout].
 *  - Anything else → [AppError.Unexpected] carrying the original message.
 *  - Unknown source api → [AppError.Validation.SourceUnavailable].
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
    private val pageFiles: DownloadedPageFiles,
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
        cleanupLocksGuard.withLock {
            cleanupLocks.getOrPut(chapterId) { Mutex() }
        }

    override fun fetchPages(
        manga: Manga,
        chapter: Chapter,
    ): Flow<AppResult<List<Page>>> =
        flow {
            // Downloaded-chapter fast path (native parity): serve local files instead of re-fetching
            // from the source when the chapter has been downloaded for offline reading. Falls through
            // to the network path when the chapter isn't downloaded or no readable local files exist.
            val localPages = localPagesOrNull(manga, chapter)
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
        }.catch { t ->
            if (t is CancellationException) throw t
            emit(AppResult.Failure(classifyThrowable(t)))
        }.flowOn(dispatchers.io)

    /** Only a complete validated local roster wins over source recovery. */
    private suspend fun localPagesOrNull(
        manga: Manga,
        chapter: Chapter,
    ): List<Page>? {
        val entity =
            chapterDao
                .getChapterIdByUrl(manga.url, chapter.url)
                ?.let { chapterDao.getChapterByIdSuspend(it) }
                ?.takeIf { it.isDownloaded && it.localImagePaths.isNotEmpty() }
                ?: return null
        val local =
            cleanupLockFor(entity.id).withLock {
                val single = entity.localImagePaths.singleOrNull()
                if (single != null && single.endsWith(".cbz", ignoreCase = true)) {
                    extractLocalArchive(entity.mangaId, entity.id, single)
                } else {
                    pageFiles.resolve(entity.mangaId, entity.id, entity.localImagePaths)
                        ?: extractLocalArchive(entity.mangaId, entity.id, stored = null)
                }
            }
        return local.takeIf { it.isNotEmpty() }?.map { Page(url = toFileUrl(it.toString()), headers = emptyMap()) }
    }

    private suspend fun extractLocalArchive(
        mangaId: Long,
        chapterId: Long,
        stored: String?,
    ): List<Path> {
        val canonical = cbzReader.cbzPath(mangaId, chapterId)
        val candidates =
            buildList {
                if (runCatchingCancellable { cbzReader.cbzExists(mangaId, chapterId) }.getOrDefault(false)) {
                    add(canonical)
                }
                if (stored != null) add(stored.toPath())
            }.distinct()
        for (candidate in candidates) {
            val extracted =
                try {
                    cbzReader.extractImages(candidate, mangaId, chapterId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            if (extracted.isNotEmpty()) return extracted
        }
        return emptyList()
    }

    /**
     * Build an RFC-8089 `file://` URL from a local path. A bare `"file://$path"` is malformed for
     * Windows Desktop (packageMsi): `C:\Users\…\chapter_5.cbz` would yield `file://C:\…` (authority
     * "C:", unescaped backslashes), and spaces / non-ASCII in the user-profile path go through
     * unencoded. okio normalizes separators to `/`; we then prefix `file://`, ensure a leading
     * slash so the authority is empty (Unix `/d/1` → `file:///d/1`, Windows `C:/…` → `file:///C:/…`),
     * and percent-encode each segment.
     */
    private fun toFileUrl(path: String): String {
        val normalized = path.toPath().toString().replace('\\', '/')
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
            val safe =
                ch in 'A'..'Z' ||
                    ch in 'a'..'z' ||
                    ch in '0'..'9' ||
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

    override fun clearExtractedPages(
        manga: Manga,
        chapter: Chapter,
    ) {
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
                val chapterId = chapterDao.getChapterIdByUrl(manga.url, chapter.url) ?: return@runCatchingCancellable
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

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { this.contains(it) }

    private companion object {
        private const val HEX = "0123456789ABCDEF"
    }
}
