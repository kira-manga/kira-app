package me.manga.kira.data.repository

import me.manga.kira.core.result.AppResult
import me.manga.kira.data.download.selection.DownloadCatalogAdmission
import me.manga.kira.platform.download.DownloadOperationExclusion

/** Test-only selection seam; the actual Room provider has separate integration coverage. */
internal class TestDownloadCatalogAdmission(
    private val operations: DownloadOperationExclusion,
    private val prepare: suspend () -> Unit = {},
    private val checkReady: suspend () -> Unit = {},
) : DownloadCatalogAdmission {
    override suspend fun prepareLocal(): AppResult<Unit> {
        prepare()
        return AppResult.Success(Unit)
    }

    override suspend fun <T> withAdmittedOperation(block: suspend (DownloadOperationExclusion.Operation) -> T): T =
        operations.withOperation { operation ->
            checkReady()
            block(operation)
        }
}
