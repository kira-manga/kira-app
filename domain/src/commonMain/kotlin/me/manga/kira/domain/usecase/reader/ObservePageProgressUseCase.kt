package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.model.reader.PageProgressObservation
import me.manga.kira.domain.repository.PageProgressRepository

/** Acquires a Reader-owned page slot; the caller releases it through [ClearPageProgressUseCase]. */
class ObservePageProgressUseCase(
    private val repository: PageProgressRepository,
) {
    operator fun invoke(pageUrl: String): PageProgressObservation = repository.observe(pageUrl)
}
