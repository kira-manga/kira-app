package me.manga.kira.work

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.LibraryChapterNotification
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.sources.contracts.MangaSourceClient

private const val DEFAULT_TOTAL_TIMEOUT_MS = 15L * 60 * 1_000

/** Adapter to the existing facade/helper; persistence and optional display stay caller-owned. */
internal interface LibraryRefreshWorkPort {
    fun library(): Flow<List<SavedMangaEntity>>

    fun source(api: String): MangaSourceClient?

    /** Best-effort metadata only; retain the prefetch owner and the response's actual locator. */
    suspend fun updateCover(
        owner: SavedWorkIdentity,
        fetched: WorkLocator,
        coverUrl: String,
    ): AppResult<Unit>

    /** Mandatory ownership check and atomic refresh, including requests with no new chapters. */
    suspend fun persistNotifications(request: LibraryRefreshRequest): AppResult<LibraryRefreshReceipt>

    suspend fun displayNotifications(notifications: List<LibraryChapterNotification>)

    suspend fun stampLastSuccess()
}

internal data class LibraryRefreshWorkTimeouts(
    val totalMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    val libraryReadMs: Long = 30_000,
    val itemMs: Long = 30_000,
    val detailsMs: Long = 20_000,
)

internal enum class LibraryRefreshWorkStop {
    EXHAUSTED,
    READ_FAILED,
    READ_TIMEOUT,
    TOTAL_TIMEOUT,
    ABORTED,
    STAMP_FAILED,
}

internal data class LibraryRefreshWorkProgress(
    val snapshotSize: Int?,
    val succeeded: Int,
    val failed: Int,
    val timedOut: Int,
    val notAttempted: Int?,
    val newChapterCount: Int,
    val stop: LibraryRefreshWorkStop?,
) {
    val attempted: Int get() = succeeded + failed + timedOut
    val isComplete: Boolean get() =
        stop == LibraryRefreshWorkStop.EXHAUSTED &&
            snapshotSize != null &&
            succeeded == snapshotSize &&
            failed == 0 &&
            timedOut == 0

    init {
        require(succeeded >= 0 && failed >= 0 && timedOut >= 0 && newChapterCount >= 0)
        require(
            if (snapshotSize == null) {
                attempted == 0 && notAttempted == null
            } else {
                notAttempted != null && notAttempted >= 0 && snapshotSize == attempted + notAttempted
            },
        )
    }
}
