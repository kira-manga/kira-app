package me.manga.kira.presentation.sourceaccess

import me.manga.kira.domain.model.sources.SourceActivationResult
import me.manga.kira.domain.usecase.sourceaccess.ActivateSourceAccessUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/** Coordinates activation and navigation from the Start Reading screen. */
class StartReadingViewModel(
    private val activateSourceAccess: ActivateSourceAccessUseCase,
) : MviViewModel<StartReadingState, StartReadingIntent, StartReadingEffect>(StartReadingState()) {
    override suspend fun handle(intent: StartReadingIntent) {
        when (intent) {
            is StartReadingIntent.OnActivationLinkChanged ->
                updateState {
                    it.copy(activationLink = intent.value, invalidLink = false)
                }
            StartReadingIntent.OnActivate -> activate()
            StartReadingIntent.OnImport -> emit(StartReadingEffect.OpenImport)
            StartReadingIntent.OnContinueToLibrary -> emit(StartReadingEffect.ContinueToLibrary)
        }
    }

    private suspend fun activate() {
        if (state.value.isActivating) return
        updateState { it.copy(isActivating = true, invalidLink = false) }
        try {
            when (activateSourceAccess(state.value.activationLink)) {
                SourceActivationResult.INVALID_LINK -> updateState { it.copy(invalidLink = true) }
                SourceActivationResult.ACTIVATED,
                SourceActivationResult.ALREADY_ACTIVATED,
                -> {
                    updateState { it.copy(activationLink = "") }
                    emit(StartReadingEffect.ActivationSucceeded)
                }
            }
        } finally {
            updateState { it.copy(isActivating = false) }
        }
    }
}
