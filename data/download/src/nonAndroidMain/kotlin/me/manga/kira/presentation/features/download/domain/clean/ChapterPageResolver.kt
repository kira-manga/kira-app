package me.manga.kira.presentation.features.download.domain.clean

import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity

/**
 * A complete, catalog-verified page set. Each page retains its own headers so no downloader needs
 * source-specific Kotlin logic.
 */
data class ResolvedChapter(
    val pages: List<DownloadPage>,
) {
    val imageUrls: List<String>
        get() = pages.map(DownloadPage::url)
}

/**
 * Resolves queued downloads exclusively through the authoritative catalog provider.
 *
 * Missing or retired sources throw from [ChapterPageProvider]; there is deliberately no nullable
 * result and no legacy repository lookup.
 */
class ChapterPageResolver(
    private val mangaDao: MangaDao,
    private val chapterPageProvider: ChapterPageProvider,
) {

    suspend fun resolve(entity: ChapterDownloadEntity): ResolvedChapter {
        val manga = mangaDao.getMangaById(entity.mangaId)
        return ResolvedChapter(
            pages =
                chapterPageProvider.pagesOrNull(
                    api = entity.api.orEmpty(),
                    mangaUrl = manga?.url.orEmpty(),
                    mangaLanguage = manga?.language.orEmpty(),
                    chapterUrl = entity.url,
                ),
        )
    }
}
