package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/**
 * Test-only manager for registry seam tests that exercise the trusted bundled tier without a
 * database or network. Incremental delivery and signature handling have their own focused tests.
 */
internal class StaticSourceUpdateManager(
    private val document: SourceConfigDocument,
) : SourceUpdateManager {
    private val mutableState =
        MutableStateFlow<UpdateState>(
            UpdateState.Active(document.revision, UpdateState.Origin.BUNDLED),
        )

    override val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    override fun activeDocument(): SourceConfigDocument = document

    override suspend fun refresh(): AppResult<SourceConfigDocument> = AppResult.Success(document)
}
