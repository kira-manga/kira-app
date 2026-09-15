package me.manga.kira.data.local.dao

import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.ChapterConversionRoster
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.isOwnedBy

/** A failed suspend return is not evidence of rollback. UNKNOWN never authorizes deletion. */
enum class ChapterConversionOutcome { COMMITTED, NOT_COMMITTED, UNKNOWN }

/** Invoked only by the generated [ChapterArtifactCommitDao.readConversionOutcome] transaction. */
internal suspend fun ChapterArtifactCommitDao.conversionOutcome(
    claim: ChapterArtifactClaim,
    canonicalPath: String,
    sizeBytes: Long?,
): ChapterConversionOutcome {
    val record = artifact(claim.owner.chapterId) ?: return ChapterConversionOutcome.UNKNOWN
    if (!record.isOwnedBy(claim) || claim.operation != ChapterArtifactOperation.CONVERT ||
        record.committedRelativePath != null || record.retiredRelativePath != null ||
        claim.pending != null || record.ownsPendingPath
    ) return ChapterConversionOutcome.UNKNOWN
    val current = saved(claim.owner.chapterId) ?: return ChapterConversionOutcome.UNKNOWN
    if (!claim.owner.matches(current) || !current.isDownloaded) return ChapterConversionOutcome.UNKNOWN
    val api = mangaApi(current.mangaId) ?: return ChapterConversionOutcome.UNKNOWN
    val row = download(current.id)
    if (row != null && (row.id != claim.downloadId || !row.matches(current) ||
            row.isActiveArtifactDownload() || row.api != api)
    ) return ChapterConversionOutcome.UNKNOWN
    val mirrors = notifications(current.id, current.mangaId, current.url, api)
    return if (record.committedToken == claim.token) committedConversion(claim, canonicalPath, sizeBytes, current, row, mirrors)
    else uncommittedConversion(claim, canonicalPath, current, mirrors)
}

private fun uncommittedConversion(
    claim: ChapterArtifactClaim,
    canonicalPath: String,
    chapter: SavedChapterEntity,
    mirrors: List<ChapterNotification>,
): ChapterConversionOutcome {
    val originalPaths = claim.conversionSourceRoster?.let { ChapterConversionRoster.decode(it).map { source -> source.storedPath } }
        // A pre-v16 uncommitted receipt carries no deletion authority. It can only release a
        // still-loose metadata snapshot, after the caller validates its current-owner paths.
        ?: chapter.localImagePaths.takeIf { paths -> paths.isNotEmpty() && paths.none { it.endsWith(".cbz", true) } }
        ?: return ChapterConversionOutcome.UNKNOWN
    if (chapter.localImagePaths != originalPaths ||
        mirrors.any { referencesConversion(it.localImagePaths, claim, canonicalPath) }
    ) return ChapterConversionOutcome.UNKNOWN
    return ChapterConversionOutcome.NOT_COMMITTED
}

private fun committedConversion(
    claim: ChapterArtifactClaim,
    canonicalPath: String,
    sizeBytes: Long?,
    chapter: SavedChapterEntity,
    row: ChapterDownloadEntity?,
    mirrors: List<ChapterNotification>,
): ChapterConversionOutcome =
    if (sizeBytes == null || sizeBytes <= 0 || (row != null && row.sizeBytes != sizeBytes) ||
        !referencesConversion(chapter.localImagePaths, claim, canonicalPath) ||
        mirrors.any { !it.isDownloaded || !referencesConversion(it.localImagePaths, claim, canonicalPath) }
    ) ChapterConversionOutcome.UNKNOWN else ChapterConversionOutcome.COMMITTED

/** Only the same owner-qualified canonical reference survives an iOS sandbox-root change. */
private fun referencesConversion(paths: List<String>, claim: ChapterArtifactClaim, canonicalPath: String): Boolean {
    val path = paths.singleOrNull() ?: return false
    if ('\\' in path || '\u0000' in path || path.split('/').any { it == ".." || it == "." }) return false
    val suffix = "manga/${claim.owner.mangaId}/chapter_${claim.owner.chapterId}/chapter_${claim.owner.chapterId}.cbz"
    return path == canonicalPath || path.endsWith("/$suffix")
}
