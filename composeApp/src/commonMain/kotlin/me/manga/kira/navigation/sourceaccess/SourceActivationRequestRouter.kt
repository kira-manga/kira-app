package me.manga.kira.navigation.sourceaccess

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.kira.domain.model.sources.SourceActivationLinkValidator

/** In-memory bridge from Android/iOS activation links to the shared navigation host. */
class SourceActivationRequestRouter {
    private val mutablePending = MutableStateFlow(false)

    val pending: StateFlow<Boolean> = mutablePending.asStateFlow()

    /** Validate without parsing or retaining the raw link, then enqueue one activation request. */
    fun submit(link: String): Boolean {
        if (!SourceActivationLinkValidator.isValid(link)) return false
        mutablePending.value = true
        return true
    }

    fun consume() {
        mutablePending.value = false
    }
}
