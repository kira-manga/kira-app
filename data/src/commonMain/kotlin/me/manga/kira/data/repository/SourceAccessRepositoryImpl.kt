package me.manga.kira.data.repository

import com.russhwolf.settings.ObservableSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.domain.model.sources.SourceAccessState
import me.manga.kira.domain.repository.SourceAccessRepository

/** Multiplatform-settings implementation of the permanent source-management UI gate. */
class SourceAccessRepositoryImpl(
    private val settings: ObservableSettings,
) : SourceAccessRepository {
    private val activationMutex = Mutex()
    private val mutableState = MutableStateFlow(readState())

    override val state: StateFlow<SourceAccessState> = mutableState.asStateFlow()

    override suspend fun activatePermanently(): Boolean =
        activationMutex.withLock {
            if (settings.getInt(SOURCE_ACCESS_VERSION_KEY, LOCKED_VERSION) >= ACTIVATED_VERSION) {
                mutableState.value = SourceAccessState.ACTIVATED
                return@withLock false
            }
            settings.putInt(SOURCE_ACCESS_VERSION_KEY, ACTIVATED_VERSION)
            mutableState.value = SourceAccessState.ACTIVATED
            true
        }

    private fun readState(): SourceAccessState =
        if (settings.getInt(SOURCE_ACCESS_VERSION_KEY, LOCKED_VERSION) >= ACTIVATED_VERSION) {
            SourceAccessState.ACTIVATED
        } else {
            SourceAccessState.LOCKED
        }

    private companion object {
        const val SOURCE_ACCESS_VERSION_KEY = "source_access_version"
        const val LOCKED_VERSION = 0
        const val ACTIVATED_VERSION = 1
    }
}
