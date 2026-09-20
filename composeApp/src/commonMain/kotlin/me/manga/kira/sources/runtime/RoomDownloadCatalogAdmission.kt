package me.manga.kira.sources.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.download.selection.DownloadCatalogAdmission
import me.manga.kira.data.download.selection.DownloadCatalogNotReady
import me.manga.kira.data.local.MangaWriteTransaction
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.sources.config.SourceSelectionBootstrap
import me.manga.kira.sources.contracts.SourceSelectionUnavailable

/**
 * Composition must bind the same actual Room writer/provider database and the operation gate used
 * by selection/removal. The concrete provider is also the graph's SourceAliasSnapshotProvider.
 * No permissive default, readiness cache, bootstrap-in-operation or retry policy exists here.
 */
class RoomDownloadCatalogAdmission(
    private val bootstrap: SourceSelectionBootstrap,
    private val operations: DownloadOperationExclusion,
    private val writer: MangaWriteTransaction,
    private val aliases: RoomSourceAliasSnapshotProvider,
) : DownloadCatalogAdmission {
    override suspend fun prepareLocal(): AppResult<Unit> = bootstrap.prepareLocal()

    override suspend fun <T> withAdmittedOperation(block: suspend (DownloadOperationExclusion.Operation) -> T): T =
        operations.withOperation { operation ->
            try {
                writer.write { aliases.readInTransaction() }
            } catch (_: SourceSelectionUnavailable) {
                // Only the pre-capture proof refusal is mapped, after its writer has unwound.
                throw DownloadCatalogNotReady()
            }
            currentCoroutineContext().ensureActive()
            // The immutable selection was checked under Room; the operation, not Room, spans I/O.
            // Process readiness may subsequently invalidate without revoking this cleanup lifetime.
            block(operation)
        }
}
