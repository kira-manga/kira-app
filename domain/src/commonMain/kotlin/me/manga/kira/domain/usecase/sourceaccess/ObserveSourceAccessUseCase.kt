package me.manga.kira.domain.usecase.sourceaccess

import kotlinx.coroutines.flow.StateFlow
import me.manga.kira.domain.model.sources.SourceAccessState
import me.manga.kira.domain.repository.SourceAccessRepository

/** Observe whether source-management UI is locked or permanently activated. */
class ObserveSourceAccessUseCase(
    private val repository: SourceAccessRepository,
) {
    operator fun invoke(): StateFlow<SourceAccessState> = repository.state
}
