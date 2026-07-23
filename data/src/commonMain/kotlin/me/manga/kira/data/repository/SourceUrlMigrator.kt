package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.HistoryDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.dao.NotificationDao

/**
 * Rewrites stored manga / chapter / history / notification URLs for one source from an old host to a
 * new one (path/query/fragment preserved) when that source's base URL changes — the host-move
 * migration. Extracted (Sources Migration — Phase 2) so it is implemented and tested ONCE; its
 * single writer today is [SourceCatalogSyncRepositoryImpl], the config-driven sync
 * (config.baseUrl-compare + `previousHosts` alias sweep). The other historical writer — the
 * remote-endpoint refresh (`SourceRegistryRefreshRepositoryImpl`, version-compare) — was deleted
 * in SourceRegistry retirement Phase 6.
 *
 * **Selective mode (SourceRegistry retirement Phase 3):** when [migratePageUrls]/[migrateImageUrls]
 * receive a non-null `fromHosts` set (the config `previousHosts`/`previousImageHosts` alias sweep),
 * only URLs whose host is in the set are rewritten — everything else, including user-mirror hosts
 * config does not know about, is left untouched. Hosts are compared exactly (lowercased, port
 * stripped). A null `fromHosts` keeps the classic indiscriminate per-api rewrite.
 *
 * Uses the top-level [replaceBaseUrl] helper (same package), which no-ops when the URL is unchanged
 * or the new base is blank/scheme-less, so a steady-state migration costs zero writes and a bad base
 * can never corrupt a stored URL. Best-effort failure isolation ([migrationUnit]) is two-level:
 * each table pass is
 * isolated so one table can't abort the others, and each row write so a single failing row (e.g. a
 * rewritten url colliding with another `saved_manga` row's UNIQUE url — bare `update()` ABORTs
 * instead of REPLACE-deleting + cascading) is skipped while the REST of the table still migrates.
 * Without the per-row level, one deterministic collision re-aborted the remainder of its table on
 * every launch (2026-07 audit).
 */
class SourceUrlMigrator(
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val historyDao: HistoryDao,
    private val notificationDao: NotificationDao,
) {
    /** Page-URL pass: rewrite stored manga/chapter/history/notification URLs to [newBaseUrl]'s host. */
    suspend fun migratePageUrls(
        apiName: String,
        newBaseUrl: String,
        fromHosts: Set<String>? = null,
    ) = migratePageUrls(apiName, newBaseUrl, fromHosts, failClosed = false)

    /**
     * Atomic-catalog variant of [migratePageUrls]. Any failed read or write is propagated so the
     * caller can roll back the enclosing catalog activation transaction.
     */
    suspend fun migratePageUrlsStrict(
        apiName: String,
        newBaseUrl: String,
        fromHosts: Set<String>? = null,
    ) = migratePageUrls(apiName, newBaseUrl, fromHosts, failClosed = true)

    /** Image-URL pass: rewrite stored cover/image URLs against the new IMAGE host only. */
    suspend fun migrateImageUrls(
        apiName: String,
        newImageBaseUrl: String,
        fromHosts: Set<String>? = null,
    ) = migrateImageUrls(apiName, newImageBaseUrl, fromHosts, failClosed = false)

    /**
     * Atomic-catalog variant of [migrateImageUrls]. Any failed read or write is propagated so the
     * caller can roll back the enclosing catalog activation transaction.
     */
    suspend fun migrateImageUrlsStrict(
        apiName: String,
        newImageBaseUrl: String,
        fromHosts: Set<String>? = null,
    ) = migrateImageUrls(apiName, newImageBaseUrl, fromHosts, failClosed = true)

    private suspend fun migratePageUrls(
        apiName: String,
        newBaseUrl: String,
        fromHosts: Set<String>?,
        failClosed: Boolean,
    ) = migrationUnit(failClosed) {
        updateMangaUrls(apiName, newBaseUrl, fromHosts, failClosed)
        updateChapterUrls(apiName, newBaseUrl, fromHosts, failClosed)
        updateHistoryUrls(apiName, newBaseUrl, fromHosts, failClosed)
        updateNotificationUrls(apiName, newBaseUrl, fromHosts, failClosed)
    }

    private suspend fun migrateImageUrls(
        apiName: String,
        newImageBaseUrl: String,
        fromHosts: Set<String>?,
        failClosed: Boolean,
    ) = migrationUnit(failClosed) {
        updateMangaImageUrls(apiName, newImageBaseUrl, fromHosts, failClosed)
        updateHistoryImageUrls(apiName, newImageBaseUrl, fromHosts, failClosed)
        updateNotificationImageUrls(apiName, newImageBaseUrl, fromHosts, failClosed)
    }

    private suspend fun updateMangaUrls(
        apiName: String,
        newBaseUrl: String,
        fromHosts: Set<String>?,
        failClosed: Boolean,
    ) = migrationUnit(failClosed) {
        mangaDao.getMangaByApi(apiName).forEach { manga ->
            migrationUnit(failClosed) {
                val newUrl = rewrite(manga.url, newBaseUrl, fromHosts)
                if (newUrl != manga.url) mangaDao.update(manga.copy(url = newUrl))
            }
        }
    }

    private suspend fun updateChapterUrls(
        apiName: String,
        newBaseUrl: String,
        fromHosts: Set<String>?,
        failClosed: Boolean,
    ) = migrationUnit(failClosed) {
        // Chapters carry no `api` column → reach them per-manga via the manga ids.
        mangaDao.getMangaIdsByApi(apiName).forEach { mangaId ->
            chapterDao.getChaptersByMangaIdR(mangaId).forEach { chapter ->
                migrationUnit(failClosed) {
                    val newUrl = rewrite(chapter.url, newBaseUrl, fromHosts)
                    if (newUrl != chapter.url) chapterDao.updateChapter(chapter.copy(url = newUrl))
                }
            }
        }
    }

    private suspend fun updateHistoryUrls(
        apiName: String,
        newBaseUrl: String,
        fromHosts: Set<String>?,
        failClosed: Boolean,
    ) = migrationUnit(failClosed) {
        historyDao.getHistoryByApi(apiName).forEach { item ->
            migrationUnit(failClosed) {
                val newManga = rewrite(item.mangaUrl, newBaseUrl, fromHosts)
                val newChapter = rewrite(item.chapterUrl, newBaseUrl, fromHosts)
                if (newManga != item.mangaUrl || newChapter != item.chapterUrl) {
                    historyDao.updateHistory(item.copy(mangaUrl = newManga, chapterUrl = newChapter))
                }
            }
        }
    }

    private suspend fun updateNotificationUrls(
        apiName: String,
        newBaseUrl: String,
        fromHosts: Set<String>?,
        failClosed: Boolean,
    ) = migrationUnit(failClosed) {
        notificationDao.getNotificationsByApi(apiName).forEach { n ->
            migrationUnit(failClosed) {
                val newManga = rewrite(n.mangaUrl, newBaseUrl, fromHosts)
                val newChapter = rewrite(n.chapterUrl, newBaseUrl, fromHosts)
                if (newManga != n.mangaUrl || newChapter != n.chapterUrl) {
                    notificationDao.updateNotification(n.copy(mangaUrl = newManga, chapterUrl = newChapter))
                }
            }
        }
    }

    private suspend fun updateMangaImageUrls(
        apiName: String,
        newImageBaseUrl: String,
        fromHosts: Set<String>?,
        failClosed: Boolean,
    ) = migrationUnit(failClosed) {
        mangaDao.getMangaByApi(apiName).forEach { manga ->
            migrationUnit(failClosed) {
                val newImage = rewrite(manga.imageUrl, newImageBaseUrl, fromHosts)
                if (newImage != manga.imageUrl) mangaDao.update(manga.copy(imageUrl = newImage))
            }
        }
    }

    private suspend fun updateHistoryImageUrls(
        apiName: String,
        newImageBaseUrl: String,
        fromHosts: Set<String>?,
        failClosed: Boolean,
    ) = migrationUnit(failClosed) {
        historyDao.getHistoryByApi(apiName).forEach { item ->
            migrationUnit(failClosed) {
                val newImage = rewrite(item.mangaImageUrl, newImageBaseUrl, fromHosts)
                if (newImage != item.mangaImageUrl) historyDao.updateHistory(item.copy(mangaImageUrl = newImage))
            }
        }
    }

    private suspend fun updateNotificationImageUrls(
        apiName: String,
        newImageBaseUrl: String,
        fromHosts: Set<String>?,
        failClosed: Boolean,
    ) = migrationUnit(failClosed) {
        notificationDao.getNotificationsByApi(apiName).forEach { n ->
            migrationUnit(failClosed) {
                val newImage = rewrite(n.mangaImageUrl, newImageBaseUrl, fromHosts)
                if (newImage != n.mangaImageUrl) notificationDao.updateNotification(n.copy(mangaImageUrl = newImage))
            }
        }
    }

    /**
     * Run one isolated migration unit — a whole table pass or a single row's rewrite+write —
     * swallowing non-cancellation errors so one failure can't abort its siblings. At row level this
     * is what lets a UNIQUE-url collision in `saved_manga` (which ABORTs that update) skip just
     * that row while every following row still migrates.
     */
    private suspend inline fun migrationUnit(
        failClosed: Boolean,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (failure: Throwable) {
            if (failClosed) throw failure
        }
    }
}

/**
 * Swap the scheme+host of [originalUrl] for [newBaseUrl]'s scheme+host, preserving the original's
 * path/query/fragment (B6 #1).
 *
 * KMP-portable, dependency-free reimplementation of native's `replaceBaseUrl` (which used the
 * JVM-only `java.net.URI`). For `https://old.com/p?q#f` + `https://new.com` →
 * `https://new.com/p?q#f`. Only the ORIGIN (`scheme://host[:port]`) of [newBaseUrl] is used — any
 * path it carries is dropped, because the stored URL's own path already embeds the source's path
 * prefix. Appending the full base used to double a path-bearing base (2026-07 audit: a SwatManga
 * `https://new/v2/api/v1` move would have produced `…/v2/api/v1/v2/api/v1/{id}` on every stored
 * row). Any input without a `://` scheme, or any malformed input, is returned UNCHANGED — matching
 * native's catch-and-return-original guard, so a weird stored URL is never corrupted.
 *
 * `internal` (not `private`) so [ReplaceBaseUrlTest] can exercise the host-swap directly. (Moved
 * here from `SourceRegistryRefreshRepositoryImpl` when that endpoint path was deleted —
 * SourceRegistry retirement Phase 6.)
 */
internal fun replaceBaseUrl(
    originalUrl: String,
    newBaseUrl: String,
): String {
    return try {
        // Guard the *replacement* base too: a blank / scheme-less new base (e.g. a config stanza
        // whose `baseUrl`/`imageBase` is absent → defaults to "") would strip the host off every
        // stored URL. Leave the original untouched in that case (B6 #1 — never corrupt a stored
        // URL).
        val newBaseValid =
            newBaseUrl.contains(SCHEME_SEPARATOR) && newBaseUrl.substringAfter(SCHEME_SEPARATOR).isNotBlank()
        val schemeIdx = originalUrl.indexOf(SCHEME_SEPARATOR)
        if (!newBaseValid || schemeIdx < 0) return originalUrl
        val authorityStart = schemeIdx + SCHEME_SEPARATOR.length
        // The authority (host[:port]) ends at the first '/', '?' or '#'; everything from there on
        // (path + query + fragment) is preserved verbatim.
        var remainderStart = -1
        for (i in authorityStart until originalUrl.length) {
            val c = originalUrl[i]
            if (c == '/' || c == '?' || c == '#') {
                remainderStart = i
                break
            }
        }
        val remainder = if (remainderStart < 0) "" else originalUrl.substring(remainderStart)
        urlOrigin(newBaseUrl) + remainder
    } catch (_: Throwable) {
        // Intentionally broad + silent (B6 #1): any parse weirdness must return the ORIGINAL
        // stored URL unchanged rather than risk corrupting it.
        originalUrl
    }
}

/**
 * The origin (`scheme://host[:port]`) of [url] — its path/query/fragment dropped, trailing `/`
 * never included. Same authority-delimiting rules as [urlHost]. Callers guarantee [url] carries a
 * `://` scheme with a non-blank remainder.
 */
private fun urlOrigin(url: String): String {
    val authorityStart = url.indexOf(SCHEME_SEPARATOR) + SCHEME_SEPARATOR.length
    var end = url.length
    for (i in authorityStart until url.length) {
        val c = url[i]
        if (c == '/' || c == '?' || c == '#') {
            end = i
            break
        }
    }
    return url.substring(0, end)
}

private const val SCHEME_SEPARATOR = "://"

/** [replaceBaseUrl], gated on the URL's current host when a [fromHosts] filter is active. */
private fun rewrite(
    url: String,
    newBaseUrl: String,
    fromHosts: Set<String>?,
): String {
    if (fromHosts != null && urlHost(url) !in fromHosts) return url
    return replaceBaseUrl(url, newBaseUrl)
}

/**
 * The host of [url] — lowercased, port stripped — or null when [url] has no `://` scheme or a blank
 * authority. Companion to [replaceBaseUrl]: the same authority-delimiting rules ('/', '?', '#'), used
 * by the selective migration filter and pinned by [SourceUrlMigratorTest].
 */
internal fun urlHost(url: String): String? {
    val schemeIdx = url.indexOf(SCHEME_SEPARATOR)
    if (schemeIdx < 0) return null
    val authorityStart = schemeIdx + SCHEME_SEPARATOR.length
    var end = url.length
    for (i in authorityStart until url.length) {
        val c = url[i]
        if (c == '/' || c == '?' || c == '#') {
            end = i
            break
        }
    }
    val host = url.substring(authorityStart, end).substringBefore(':').lowercase()
    return host.ifBlank { null }
}
