package me.manga.kira.data.repository

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.logging.FlowLog
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.HistoryDao
import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.mapper.toLibraryManga
import me.manga.kira.data.mapper.toNewSavedChapterEntity
import me.manga.kira.data.mapper.toSavedChapterEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.domain.repository.ReadProgressRepository
import me.manga.kira.domain.service.FileService
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository

/**
 * Room-backed [LibraryRepository] implementation.
 *
 * SRP (contract §6): implements the library aggregate contract and nothing else. Chapter
 * reactions / history / notifications live in their own future repository impls.
 *
 * DIP: depends on the [LibraryRepository] interface from :domain, the [MangaDao] / [LibraryDeo]
 * DAOs from :shared (transitional), and [DispatcherProvider] from :core. No Compose, no UI types.
 *
 * Membership semantics (preservation guarantee, contract §13 / baseline §3):
 *   The legacy schema uses `api + title` as the de-facto composite key (see
 *   [MangaDao.getIdByApiAndTitle]). The new domain key is the triple `(api, language, title)`,
 *   but for membership lookups we project to `(api, title)` to match the legacy wire format
 *   verbatim. Language is preserved on the entity and exposed back through the domain model;
 *   only the lookup ignores it.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster23.staleKdocSweep.cascade,
 * Task #479, 2026-05-28): one fulfilled-forecast citation appears
 * inside the method-level `toggleLiked` KDoc below:
 *  - The `toggleLiked` KDoc closes with "Phase 9.x retires the legacy
 *    DAO reach". HALF-FULFILLED — Phase 8.y.library.swap (§346)
 *    re-pointed `Screen.Library`'s rendering adapter to the rework
 *    UI; Phase 9.x.library.retire (§347) deleted the orphan legacy
 *    `:shared` Library screen; Phase 9.x.deadcomposable.retire
 *    (§348) pruned the legacy LibraryViewModel's dead composables.
 *    HOWEVER — the legacy [MangaDao] + [LibraryDeo] Room DAOs STILL
 *    EXIST as the cell of truth that this impl delegates to via
 *    `mangaDao: MangaDao` + `libraryDeo: LibraryDeo` constructor
 *    params (verified at the constructor signature below). The
 *    "Phase 9.x retires the legacy DAO reach" forecast was
 *    HALF-FULFILLED at the route-swap layer (§346) + UI-retire layer
 *    (§347 + §348); the DAO-reach itself remains as the rework's
 *    Room transport backbone. Mirror of §477 library cluster +
 *    §475-478 partially-fulfilled-inversion precedent.
 * The class-level SRP / DIP / membership-semantics sub-section + the
 * `toggleLiked` / `toggleWatchingNow` strangler-fig method KDocs +
 * the `toNewEntity` projection rule all stand on their own merits
 * past the §§346 + 347 + 348 fulfilled landings. The
 * LibraryRepositoryImpl remains LIVE as the canonical Room-backed
 * implementation for the rework library surface. Original §253-era
 * prose preserved verbatim per the audit-trail-preservation
 * convention — the citation is historical record of the design
 * lineage including the deferred-DAO-reach-retire forecast that was
 * subsequently half-fulfilled across §§346 + 347 + 348 (UI retired,
 * DAO transport retained).
 */
@OptIn(ExperimentalTime::class)
class LibraryRepositoryImpl(
    private val mangaDao: MangaDao,
    private val libraryDeo: LibraryDeo,
    private val chapterDao: ChapterDao,
    private val notificationDao: NotificationDao,
    private val historyDao: HistoryDao,
    private val chapterDownloadDao: ChapterDownloadDao,
    // Legacy download engine — used by purgeManga to cancel an in-flight download of the manga
    // being removed (so the engine can't write an orphan CBZ into the just-deleted directory).
    private val downloadRepository: DownloadRepository,
    private val fileService: FileService,
    // Clears per-chapter resume positions (stored in settings, keyed by chapter url) on removal.
    private val readProgress: ReadProgressRepository,
    private val dispatchers: DispatcherProvider,
) : LibraryRepository {

    override fun observeLibrary(): Flow<List<LibraryManga>> =
        combine(
            // distinctUntilChanged (2026-07 audit): Room invalidation re-emits on ANY write to the
            // observed tables — every chapter write during a bulk refresh re-ran the full-table
            // GROUP BY + this whole-library map + the VM's applyView per emission. Deduping the
            // structurally-equal inputs (data-class rows) collapses that to one recompute per real
            // change; the output dedup below stops identical lists reaching the VM at all.
            mangaDao.getAllSavedMangaFlow().distinctUntilChanged(),
            mangaDao.getAllChapterMetricsFlow().distinctUntilChanged(),
        ) { mangas, metrics ->
            val byId = metrics.associateBy { it.mangaId }
            mangas.map { entity ->
                val m = byId[entity.id]
                entity.toLibraryManga(
                    totalChapters = m?.totalChapters ?: 0,
                    readCount = m?.readCount ?: 0,
                    downloadedCount = m?.downloadedCount ?: 0,
                    lastReadTs = m?.lastReadTs,
                    bookmarkedCount = m?.bookmarkedCount ?: 0,
                )
            }
        }.distinctUntilChanged()
            .flowOn(dispatchers.io)

    override fun observeIsInLibrary(
        api: String,
        language: String,
        title: String,
    ): Flow<Boolean> =
        mangaDao.getAllSavedMangaFlow()
            .map { list -> list.any { it.api == api && it.title == title } }
            .distinctUntilChanged()
            .flowOn(dispatchers.io)

    override suspend fun get(
        api: String,
        language: String,
        title: String,
    ): AppResult<LibraryManga?> = runCatchingStorage {
        withContext(dispatchers.io) {
            val id = mangaDao.getIdByApiAndTitle(api, title) ?: return@withContext null
            val entity = mangaDao.getMangaById(id) ?: return@withContext null
            entity.toLibraryManga(
                totalChapters = 0,
                readCount = 0,
                downloadedCount = 0,
                lastReadTs = null,
                bookmarkedCount = 0,
            )
        }
    }

    /**
     * Native parity (`MangaRepository.save` → `LibraryDeo.saveMangaWithChapters`): persist the
     * manga row AND its chapter list atomically. `saveMangaWithChapters` upserts the manga (IGNORE
     * conflict), resolves/creates its id, and inserts only chapter URLs not already saved — so an
     * idempotent re-add is a no-op and a partial chapter list tops up without duplicating rows.
     *
     * `[chapters].reversed()` mirrors native's `...toSavedEntities(1).reversed()` call-site detail:
     * the source ships chapters newest-first, so reversing lands them oldest-first in Room and the
     * autoincrement `id` ascends with chapter recency (matching `ChapterDao.getChaptersByMangaId`'s
     * `ORDER BY id ASC`). The per-chapter `mangaId` is a placeholder here — the DAO re-stamps it
     * with the resolved id inside the transaction.
     */
    override suspend fun addToLibrary(manga: Manga, chapters: List<Chapter>): AppResult<Unit> =
        runCatchingStorage {
            withContext(dispatchers.io) {
                libraryDeo.saveMangaWithChapters(
                    manga = manga.toNewEntity(),
                    chapters = chapters.reversed().map { it.toSavedChapterEntity() },
                )
            }
        }

    /**
     * Native parity (`MangaRepository.removeManga`): a removal must purge BOTH the DB rows AND the
     * on-disk downloaded files, or the "downloaded" badge/size (read from `chapter_downloads`) and
     * the orphaned `manga/$id/` directory survive the removal. `removeMangaWithChapters` clears the
     * sibling tables (incl. `chapter_downloads`) in one transaction; `deleteMangaFiles` then drops
     * the whole `manga/$id` directory recursively (no-op when absent).
     */
    /**
     * Native parity (`LibraryDetailsViewModel.refreshChapters`): for an in-library manga, diff the
     * freshly-fetched chapter list against the saved URLs and insert ONLY the genuinely-new ones,
     * flagged `isNew = true` with a `fetchedAt = now` discovery timestamp. Idempotent — the unique
     * `(mangaId, url)` index + `OnConflict.IGNORE` mean a re-refresh inserts nothing and never resets
     * `isNew` on already-saved chapters. `.reversed()` matches the add path so autoincrement `id`
     * stays oldest→newest. Returns the count inserted; 0 when the manga isn't in the library.
     */
    override suspend fun persistNewChapters(
        api: String,
        language: String,
        title: String,
        fetched: List<Chapter>,
    ): AppResult<Int> = runCatchingStorage {
        withContext(dispatchers.io) { insertNewChapters(api, title, fetched).second.size }
    }

    /**
     * Refresh-all variant (native `LibraryRefreshWorker` parity): persist the new chapters AND write a
     * `notifications` row per genuinely-new chapter so it surfaces in the Notifications/Updates screen.
     * `chapterId` is the freshly-inserted `saved_chapters` row id (resolved by url); rows are inserted
     * with the entity defaults (autogen id, `notificationDate` = today, `isRead`/`isDownloaded` = false).
     * De-dup is intrinsic — only the [newOnes] just inserted are notified.
     */
    override suspend fun persistNewChaptersAndNotify(manga: Manga, fetched: List<Chapter>): AppResult<Int> =
        runCatchingStorage {
            withContext(dispatchers.io) {
                val (mangaId, newOnes) = insertNewChapters(manga.api, manga.title, fetched)
                if (mangaId > 0L && newOnes.isNotEmpty()) {
                    // Resolve all new chapter ids in ONE query scoped by mangaId (was N+1 url-only
                    // LIMIT-1 reads): mangaId-scoping also prevents a chapter url legally reused under
                    // a DIFFERENT manga from attaching the notification to the wrong manga's row.
                    val idByUrl = chapterDao.getChapterIdsByUrlForManga(mangaId, newOnes.map { it.url })
                    val notifications = newOnes.mapNotNull { ch ->
                        val chapterId = idByUrl[ch.url] ?: return@mapNotNull null
                        ChapterNotification(
                            api = manga.api,
                            language = manga.language,
                            mangaId = mangaId,
                            mangaTitle = manga.title,
                            mangaImageUrl = manga.coverUrl,
                            mangaUrl = manga.url,
                            chapterId = chapterId,
                            chapterNumber = ch.number,
                            chapterUrl = ch.url,
                        )
                    }
                    if (notifications.isNotEmpty()) notificationDao.insertNotificationsList(notifications)
                }
                newOnes.size
            }
        }

    /**
     * Shared diff+insert for the two persist paths. Resolves the manga id, diffs [fetched] against the
     * saved chapter urls, and inserts ONLY the genuinely-new ones (isNew=true, fetchedAt=now, reversed
     * so autoincrement id ascends oldest→newest; IGNORE on the unique (mangaId,url) index makes it
     * idempotent). Returns (mangaId, newOnes); mangaId is 0 and newOnes empty when not in library.
     */
    private suspend fun insertNewChapters(
        api: String,
        title: String,
        fetched: List<Chapter>,
    ): Pair<Long, List<Chapter>> {
        val mangaId = mangaDao.getIdByApiAndTitle(api, title) ?: run {
            FlowLog.log("Details", "persistNew", "title=$title skipped=not-in-library")
            return 0L to emptyList()
        }
        val savedUrls = libraryDeo.getSavedChapterUrls(mangaId).toSet()
        val newOnes = fetched.filter { it.url !in savedUrls }
        FlowLog.log("Details", "persistNew", "mangaId=$mangaId fetched=${fetched.size} new=${newOnes.size}")
        if (newOnes.isNotEmpty()) {
            val now = Clock.System.now().toEpochMilliseconds()
            libraryDeo.insertChapters(
                newOnes.reversed().map { it.toNewSavedChapterEntity(mangaId = mangaId, fetchedAt = now) },
            )
        }
        return mangaId to newOnes
    }

    /**
     * Native parity (`LibraryRefreshWorker.updateMangaImageUrlEverywhere`): when a refresh fetches a
     * rotated cover URL, rewrite it across `saved_manga`, `history` (by mangaId AND by mangaUrl — the
     * rework writes some history rows with mangaId=0, so the url path catches those) and
     * `notifications`. Resolves the id by `(api, title)` (legacy composite key); no-op when the manga
     * isn't in the library or the URL already matches. Cross-platform repair for Desktop/iOS, which
     * run only the inline refresh and have no WorkManager worker doing this.
     */
    override suspend fun updateCoverIfChanged(
        api: String,
        language: String,
        title: String,
        newCoverUrl: String,
    ): AppResult<Unit> = runCatchingStorage {
        withContext(dispatchers.io) {
            val id = mangaDao.getIdByApiAndTitle(api, title) ?: return@withContext Unit
            val entity = mangaDao.getMangaById(id) ?: return@withContext Unit
            if (entity.imageUrl == newCoverUrl) return@withContext Unit
            mangaDao.updateManga(entity.copy(imageUrl = newCoverUrl))
            historyDao.updateMangaImageUrlByUrl(entity.url, newCoverUrl)
            historyDao.updateMangaImageUrl(id, newCoverUrl)
            notificationDao.updateMangaImageUrl(id, newCoverUrl)
        }
    }

    override suspend fun removeFromLibrary(
        api: String,
        language: String,
        title: String,
    ): AppResult<Unit> = runCatchingStorage {
        withContext(dispatchers.io) {
            val id = mangaDao.getIdByApiAndTitle(api, title) ?: return@withContext Unit
            purgeManga(id)
        }
    }

    override suspend fun removeAllFromLibrary(keys: List<MangaKey>): AppResult<Int> =
        runCatchingStorage {
            withContext(dispatchers.io) {
                // #21: count only rows that actually existed and were purged — a key with no
                // saved_manga row (already removed / never saved) is skipped and must NOT inflate
                // the "Removed N items" toast.
                var purged = 0
                for (key in keys) {
                    val id = mangaDao.getIdByApiAndTitle(key.api, key.title) ?: continue
                    purgeManga(id)
                    purged++
                }
                purged
            }
        }

    /**
     * Fully erase a manga's data on library removal. Reads the url + chapter urls BEFORE deleting
     * the rows (they're needed to clear the url-keyed stores), then:
     *  - removeMangaWithChapters: saved_manga + saved_chapters + chapter_downloads + notifications
     *    (by mangaId) + history (by mangaId), in one transaction;
     *  - deleteMangaFiles: the on-disk manga/$id directory;
     *  - removeHistoryByUrl + removeNotificationsByUrl: belt-and-braces clear of any history /
     *    notification rows the rework wrote with mangaId=0 (history) or a divergent id;
     *  - readProgress.clear: the per-chapter resume positions in settings (url-keyed, no FK).
     */
    private suspend fun purgeManga(id: Long) {
        val mangaUrl = mangaDao.getMangaById(id)?.url
        val chapterUrls = libraryDeo.getSavedChapterUrls(id)
        // Cancel any in-flight download of this manga BEFORE deleting its rows + on-disk dir. Otherwise
        // the engine keeps downloading the running chapter, recreates manga/$id/ via mkdirs(), and
        // writes an orphan CBZ that nothing references (the rows are gone and a re-add gets a new id).
        // cancelARunningChapter cancels the worker/active job AND deletes that chapter's partial files.
        // Best-effort: a cancel failure must not abort the removal (the files are deleted below anyway).
        runCatchingCancellable {
            chapterDownloadDao.getActiveDownloadChapterIdsForManga(id).forEach { chapterId ->
                downloadRepository.cancelARunningChapter(chapterId, id)
            }
        }
        libraryDeo.removeMangaWithChapters(id)
        fileService.deleteMangaFiles(id)
        mangaUrl?.let {
            libraryDeo.removeHistoryByUrl(it)
            libraryDeo.removeNotificationsByUrl(it)
        }
        chapterUrls.forEach { readProgress.clear(it) }
    }

    /**
     * Strangler-fig impl: look up the legacy entity by `(api, title)`, flip the `isLiked` bit
     * on the row, persist via `MangaDao.updateManga` (`@Update onConflict = REPLACE`). Mirrors
     * the legacy `LibraryViewModel.toggleLiked` write path verbatim — same DAO method, same
     * `entity.copy(isLiked = !isLiked)` semantics — so the disk cell stays bit-for-bit identical
     * across the rework / legacy boundary while the route-swap is pending. Phase 9.x retires
     * the legacy DAO reach.
     *
     * Membership-absent (manga not in library) returns success silently — same posture as
     * `removeFromLibrary` / `removeAllFromLibrary` (graceful no-op rather than a failure).
     * The action-row only renders for in-library cards so the absent-key branch is defensive.
     *
     * §179 (Task #345). Closes the `LibraryManga.isLiked` "mutation still owned by legacy" KDoc.
     */
    override suspend fun toggleLiked(key: MangaKey): AppResult<Unit> = runCatchingStorage {
        withContext(dispatchers.io) {
            val id = mangaDao.getIdByApiAndTitle(key.api, key.title) ?: return@withContext Unit
            val entity = mangaDao.getMangaById(id) ?: return@withContext Unit
            mangaDao.updateManga(entity.copy(isLiked = !entity.isLiked))
        }
    }

    /**
     * Strangler-fig impl: same shape as [toggleLiked] — see that method's KDoc for the
     * boundary narrative. Flips `isWatchingNow` on the legacy entity and persists via the
     * legacy DAO's `@Update` method.
     *
     * §179 (Task #345).
     */
    override suspend fun toggleWatchingNow(key: MangaKey): AppResult<Unit> = runCatchingStorage {
        withContext(dispatchers.io) {
            val id = mangaDao.getIdByApiAndTitle(key.api, key.title) ?: return@withContext Unit
            val entity = mangaDao.getMangaById(id) ?: return@withContext Unit
            mangaDao.updateManga(entity.copy(isWatchingNow = !entity.isWatchingNow))
        }
    }

    /**
     * Bump `lastOpenTimestamp` to now for the saved manga; no-op when it isn't in the library
     * (`getIdByApiAndTitle` returns null). Feeds the LAST_READ sort (native parity — see
     * [LibraryRepository.markOpened]).
     */
    override suspend fun markOpened(api: String, language: String, title: String): AppResult<Unit> =
        runCatchingStorage {
            withContext(dispatchers.io) {
                val id = mangaDao.getIdByApiAndTitle(api, title) ?: return@withContext Unit
                mangaDao.updateLastOpenTimestamp(id, Clock.System.now().toEpochMilliseconds())
            }
        }

    /**
     * Project a domain [Manga] onto a fresh [SavedMangaEntity]. Fields not modeled in the domain
     * (description, status, imageUrl mismatch handling, flags) default to legacy-safe values so
     * existing UI that still reads them stays sane during the migration.
     */
    private fun Manga.toNewEntity(): SavedMangaEntity {
        val now = Clock.System.now().toEpochMilliseconds()
        return SavedMangaEntity(
            api = api,
            language = language,
            url = url,
            imageUrl = coverUrl,
            title = title,
            description = "",
            status = "",
            rating = rating?.toString(),
            genres = genres,
            savedTimestamp = now,
            lastOpenTimestamp = now,
        )
    }
}

/**
 * Wraps a suspending block that touches storage into an [AppResult] with [AppError.Storage.Io]
 * on unexpected failure. Cancellation is rethrown unchanged so structured concurrency works.
 */
private inline fun <T> runCatchingStorage(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    AppResult.Failure(AppError.Storage.Io(cause = t))
}
