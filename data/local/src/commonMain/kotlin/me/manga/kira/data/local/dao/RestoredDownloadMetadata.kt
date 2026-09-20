package me.manga.kira.data.local.dao

import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.isOwnedBy
import me.manga.kira.presentation.features.download.data.DownloadingState

/** Called only from the existing generated Room transaction; no file or native-task inference here. */
internal suspend fun ChapterArtifactCommitDao.restoredSnapshot(claim: ChapterArtifactClaim): ArtifactRepairSnapshot? {
    val record = artifact(claim.owner.chapterId) ?: return null
    if (!record.isOwnedBy(claim) || record.retiring || claim.operation != ChapterArtifactOperation.DOWNLOAD ||
        record.pendingRelativePath != null || record.pendingSizeBytes != null || record.ownsPendingPath ||
        record.retiredRelativePath != null || record.conversionSourceRoster != null
    ) return null
    val chapter = saved(record.chapterId) ?: return null
    val row = download(chapter.id) ?: return null
    val parent = repairParent(chapter.mangaId) ?: return null
    if (!claim.owner.matches(chapter) || row.id != claim.downloadId || !row.matches(chapter) ||
        !row.isActiveArtifactDownload() || row.api != parent.api
    ) return null
    return ArtifactRepairSnapshot(chapter, row, record, parent)
}

/** The file pin proved missing media; atomically retire this token and clear derived mirrors only. */
internal suspend fun ChapterArtifactCommitDao.clearMissingRestored(
    claim: ChapterArtifactClaim,
    expected: ArtifactRepairSnapshot,
): Boolean {
    val current = restoredSnapshot(claim) ?: return false
    if (!sameDownloadSnapshot(current.chapter, expected.chapter) || current.download != expected.download ||
        current.artifact != expected.artifact || current.parent != expected.parent
    ) return false
    val chapter = current.chapter
    val row = checkNotNull(current.download)
    val record = checkNotNull(current.artifact)
    check(writeSaved(ArtifactReadableUpdate(chapter.id, false, emptyList())) == 1)
    for (mirror in notifications(chapter.id, chapter.mangaId, chapter.url, current.parent.api)) {
        check(writeNotification(ArtifactReadableUpdate(mirror.id, false, emptyList())) == 1)
    }
    for (mirror in repairHistory(current.parent.api, current.parent.url, chapter.url)) {
        check(writeHistory(ArtifactReadableUpdate(mirror.id, false, emptyList())) == 1)
    }
    check(writeDownload(row.copy(state = DownloadingState.FAILED, progress = 0, sizeBytes = 0, errorMsg = null)) == row.id)
    check(writeArtifact(record.copy(retiring = true, committedToken = null, committedRelativePath = null)) == 1)
    return true
}
