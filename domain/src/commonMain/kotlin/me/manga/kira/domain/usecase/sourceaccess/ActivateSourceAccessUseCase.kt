package me.manga.kira.domain.usecase.sourceaccess

import me.manga.kira.domain.model.sources.SourceActivationLinkValidator
import me.manga.kira.domain.model.sources.SourceActivationResult
import me.manga.kira.domain.repository.SourceAccessRepository

/** Validate the simple activation marker and permanently reveal source management. */
class ActivateSourceAccessUseCase(
    private val repository: SourceAccessRepository,
) {
    suspend operator fun invoke(link: String): SourceActivationResult {
        if (!SourceActivationLinkValidator.isValid(link)) {
            return SourceActivationResult.INVALID_LINK
        }
        return if (repository.activatePermanently()) {
            SourceActivationResult.ACTIVATED
        } else {
            SourceActivationResult.ALREADY_ACTIVATED
        }
    }
}
