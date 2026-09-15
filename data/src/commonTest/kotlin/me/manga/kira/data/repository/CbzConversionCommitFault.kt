package me.manga.kira.data.repository

import me.manga.kira.data.local.dao.ChapterArtifactCommitDao
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.SavedChapterEntity
import okio.IOException

/** Fail around the generated Room transaction, never replace it with a modeled commit. */
internal class ConversionCommitFault(
    private val delegate: ChapterArtifactCommitDao,
    private val before: (suspend () -> Unit)? = null,
    private val after: (suspend () -> Unit)? = null,
    var unreadableOutcome: Boolean = false,
) : ChapterArtifactCommitDao by delegate {
    override suspend fun commitConversion(
        claim: ChapterArtifactClaim, expected: SavedChapterEntity, paths: List<String>, sizeBytes: Long,
    ): Boolean {
        before?.invoke()
        return delegate.commitConversion(claim, expected, paths, sizeBytes).also { after?.invoke() }
    }

    override suspend fun readConversionOutcome(claim: ChapterArtifactClaim, canonicalPath: String, sizeBytes: Long?) =
        if (unreadableOutcome) throw IOException("conversion readback unavailable")
        else delegate.readConversionOutcome(claim, canonicalPath, sizeBytes)
}
