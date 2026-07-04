package me.manga.kira.domain.usecase.library

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.LibraryRepository

/**
 * Persist refresh-discovered chapters AND write a Notifications-screen entry for each new one
 * (native `LibraryRefreshWorker` parity). Used ONLY by the library refresh-all path — the Details
 * pull-to-refresh uses [PersistNewChaptersUseCase], which stays notification-free to match native.
 */
class PersistNewChaptersAndNotifyUseCase(
    private val repository: LibraryRepository,
) {
    suspend operator fun invoke(manga: Manga, fetched: List<Chapter>): AppResult<Int> =
        repository.persistNewChaptersAndNotify(manga, fetched)
}
