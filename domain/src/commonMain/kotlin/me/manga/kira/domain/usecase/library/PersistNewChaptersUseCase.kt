package me.manga.kira.domain.usecase.library

import me.manga.kira.core.result.AppResult
import me.manga.kira.core.result.map
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.repository.LibraryRepository

/**
 * Persist refresh-discovered chapters that aren't yet saved for an in-library manga, flagging them
 * NEW (native parity), together with narrow fetched metadata. Returns the count inserted.
 * The retained owner must still resolve; absence/replacement is a failure, never an implicit add.
 *
 * Invoked from the Details refresh success path so newly-published chapters survive leaving and
 * reopening the screen and gain the NEW badge — instead of living only in ViewModel state.
 */
class PersistNewChaptersUseCase(
    private val repository: LibraryRepository,
) {
    suspend operator fun invoke(request: LibraryRefreshRequest): AppResult<Int> =
        repository.refresh(listOf(request), notify = false).map { receipts -> receipts.sumOf { it.addedChapters } }
}
