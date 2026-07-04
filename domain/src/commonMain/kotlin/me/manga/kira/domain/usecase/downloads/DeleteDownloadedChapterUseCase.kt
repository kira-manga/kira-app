package me.manga.kira.domain.usecase.downloads

import me.manga.kira.domain.repository.DownloadsActionRepository

/**
 * Use case: FULLY delete a chapter's downloaded content identified by [chapterId] — clears the
 * `isDownloaded` flag, deletes the on-disk files (all platforms), and drops the queue row.
 *
 * This is the Details-screen "delete downloaded" / multi-select-delete / delete-all path (native
 * parity with `LibraryRepository.deleteDownloadedChapters`). Distinct from [DeleteDownloadUseCase],
 * which is the Downloads-screen row-only delete (keeps the files + badge).
 *
 * Contract §6 SRP: one rule — "fully delete a downloaded chapter and report success/failure".
 * Contract §6 DIP: depends on [DownloadsActionRepository], not the `:data` impl or legacy facade.
 */
class DeleteDownloadedChapterUseCase(
    private val repository: DownloadsActionRepository,
) {
    suspend operator fun invoke(chapterId: Long): Result<Unit> =
        repository.deleteDownloadedChapter(chapterId)
}
