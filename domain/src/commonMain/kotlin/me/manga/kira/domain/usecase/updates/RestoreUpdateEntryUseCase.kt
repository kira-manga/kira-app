package me.manga.kira.domain.usecase.updates

import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.domain.repository.UpdatesRepository

/**
 * Restore a previously deleted update entry (snackbar "Undo"). Re-inserts the row preserving its
 * id/date/position. Pairs with [DeleteUpdateEntryUseCase] in the immediate-delete + restore model.
 */
class RestoreUpdateEntryUseCase(
    private val repository: UpdatesRepository,
) {
    suspend operator fun invoke(entry: UpdateEntry) = repository.restoreEntry(entry)
}
