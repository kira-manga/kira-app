package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.model.reader.PageProgressHandle
import me.manga.kira.domain.repository.PageProgressRepository

/** Revokes a Reader-owned page slot, including its unreported or still-in-flight requests. */
class ClearPageProgressUseCase(
    private val repository: PageProgressRepository,
) {
    operator fun invoke(handle: PageProgressHandle) {
        repository.clear(handle)
    }
}
