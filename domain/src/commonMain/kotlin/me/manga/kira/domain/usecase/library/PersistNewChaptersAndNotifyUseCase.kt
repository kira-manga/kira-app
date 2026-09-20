package me.manga.kira.domain.usecase.library

import me.manga.kira.core.result.AppResult
import me.manga.kira.core.result.map
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.repository.LibraryRepository

/**
 * Persist refresh-discovered chapters AND write a Notifications-screen entry for each new one
 * (native `LibraryRefreshWorker` parity). Used ONLY by the library refresh-all path — the Details
 * pull-to-refresh uses [PersistNewChaptersUseCase], which stays notification-free to match native.
 * The complete supplied batch shares one writer; any owner/write failure rolls it back. Refresh-all
 * submits ready owners independently so slower fetching siblings cannot erase confirmed work.
 * This use case writes Updates rows, not optional platform notification display.
 */
class PersistNewChaptersAndNotifyUseCase(
    private val repository: LibraryRepository,
) {
    suspend operator fun invoke(requests: List<LibraryRefreshRequest>): AppResult<Int> =
        repository.refresh(requests, notify = true).map { receipts -> receipts.sumOf { it.addedChapters } }
}
