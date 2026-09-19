package me.manga.kira.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Durable evidence of a committed transfer decision, never deleted with saved manga/chapters. */
@Entity(
    tableName = "reader_legacy_cleanup",
    primaryKeys = ["legacyKey", "capturedPayload"],
    foreignKeys = [
        ForeignKey(
            entity = ReaderChapterStateEntity::class,
            parentColumns = ["chapterId"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["chapterId"])],
)
data class ReaderLegacyCleanupEntity(
    val legacyKey: String,
    val capturedPayload: String,
    val chapterId: Long,
    @Embedded
    val capture: ReaderLegacyCapture,
    val state: ReaderLegacyCleanupState = ReaderLegacyCleanupState.PENDING,
) {
    init {
        require(chapterId > 0)
    }
}

/** The generations and copy decision observed together in the original Room writer transaction. */
data class ReaderLegacyCapture(
    val capturedWorkGeneration: Long,
    val capturedChapterGeneration: Long,
    val disposition: ReaderLegacyDisposition,
) {
    init {
        require(capturedWorkGeneration >= 0 && capturedChapterGeneration >= 0)
    }
}

/** SUPERSEDED records a native value or clear fence; neither disposition permits a second copy. */
enum class ReaderLegacyDisposition {
    COPIED,
    SUPERSEDED,
}

/** ACKED describes a returned cleanup attempt, not a durable Settings flush acknowledgement. */
enum class ReaderLegacyCleanupState {
    PENDING,
    ACKED,
    CONFLICT,
}
