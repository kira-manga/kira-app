package me.manga.kira.domain.usecase.reader

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.repository.PageProgressRepository

/**
 * Observes the per-page download/decode progress for the page at [pageUrl].
 *
 * DIP seam (contract §6): the Reader VM depends on this use case, never on
 * [PageProgressRepository] directly — mirroring the established "VM → use case → repository"
 * shape of the other reader collaborators ([ObserveChapterBookmarkUseCase],
 * [LoadPagePositionUseCase], …). The use case wraps only the read half of the repository
 * ([PageProgressRepository.observe]); the `report` half is driven by the `:platform` interceptors,
 * and the `clear` half is exposed to the Reader VM's teardown through [ClearPageProgressUseCase].
 */
class ObservePageProgressUseCase(
    private val repository: PageProgressRepository,
) {
    operator fun invoke(pageUrl: String): Flow<PageDownloadProgress> =
        repository.observe(pageUrl)
}
