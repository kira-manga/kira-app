package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.first
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import me.manga.kira.sources.contracts.SourceRegistry

/**
 * Sources Migration — Phase 3. Composition-root implementation of the `:shared` [ChapterPageProvider]
 * routing seam: resolves a chapter's page image URLs (+ headers) for a **config-backed** source through
 * the [SourceRegistry], so chapter DOWNLOADS go through the same generic config-driven path as reads
 * instead of calling the legacy scraper directly.
 *
 * Routing is generic-only:
 *  - Missing source client → throw [GenericPagesFailedException]. No legacy download path exists.
 *  - Config-backed, generic `Success` → the generic page URLs (+ `Page.headers` for cookies/Referer/UA)
 *    mapped to [DownloadPage]s.
 *  - Config-backed, generic `Failure` (or an empty / no emission) → **throw** [GenericPagesFailedException].
 *    The download engines' worker catches it and marks the chapter FAILED (a clear error). It is NEVER
 *    `null` for a config-backed source, so the engines never fall back to the legacy scraper here.
 *
 * The generic `pages` endpoint of every config-backed source is templated on `{chapterUrl}`; [mangaUrl] /
 * [mangaLanguage] are forwarded for completeness (a future source whose pages endpoint references
 * `{itemUrl}`) and to build a faithful [Manga].
 */
class GenericPagesFailedException(message: String) : Exception(message)

class RegistryChapterPageProvider(
    private val sourceRegistry: SourceRegistry,
) : ChapterPageProvider {

    override suspend fun pagesOrNull(
        api: String,
        mangaUrl: String,
        mangaLanguage: String,
        chapterUrl: String,
    ): List<DownloadPage> {
        val client = sourceRegistry.get(api)
            ?: throw GenericPagesFailedException("source unavailable for api=$api")

        val manga = Manga(
            api = api,
            language = mangaLanguage,
            title = "",
            url = mangaUrl,
            coverUrl = "",
            rating = null,
            genres = emptyList(),
        )
        val chapter = Chapter(
            number = "",
            name = "",
            url = chapterUrl,
            date = null,
            isDownloaded = false,
            isBookmarked = false,
        )

        return when (val result = client.pages(manga, chapter).first()) {
            is AppResult.Success ->
                // A config-backed Success with zero pages is a selector/extraction failure (real chapters
                // always have images) → fail clearly rather than download an empty chapter; never legacy.
                result.value.takeIf { it.isNotEmpty() }?.map { DownloadPage(url = it.url, headers = it.headers) }
                    ?: throw GenericPagesFailedException("generic pages() returned no pages for api=$api chapter=$chapterUrl")
            // Generic failure is surfaced as a clear download failure — the legacy scraper is NOT executed.
            is AppResult.Failure ->
                throw GenericPagesFailedException("generic pages() failed for api=$api chapter=$chapterUrl: ${result.error}")
        }
    }
}
