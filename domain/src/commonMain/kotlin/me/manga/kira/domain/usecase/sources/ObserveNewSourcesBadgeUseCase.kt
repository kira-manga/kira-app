package me.manga.kira.domain.usecase.sources

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.repository.SourcesRepository

/**
 * U2 (new-sources badge): observe whether the catalog gained sources the user hasn't reviewed —
 * drives the "NEW" chip on the Home source-tab strip's edit action (native `AnimatedNew` parity).
 * Signal is set by the What's-New pipeline and cleared via [ClearNewSourcesBadgeUseCase] when the
 * user opens the source-edit surface.
 */
class ObserveNewSourcesBadgeUseCase(
    private val repository: SourcesRepository,
) {
    operator fun invoke(): Flow<Boolean> = repository.observeHasNewSources()
}
