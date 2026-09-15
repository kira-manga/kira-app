package me.manga.kira.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable file custody, deliberately independent of deletable download history and parent FKs.
 * A revoked operation retains [token] until its file users and cleanup have actually finished.
 * Pending/retired paths must be settled before admitting another operation; this bounds recovery
 * to one operation and one committed generation per chapter without losing cleanup provenance.
 */
@Entity(tableName = "chapter_artifacts", indices = [Index(value = ["mangaId"])])
data class ChapterArtifactEntity(
    @PrimaryKey val chapterId: Long,
    val mangaId: Long,
    val chapterUrl: String,
    val token: String? = null,
    val operation: String? = null,
    val retiring: Boolean = false,
    val downloadId: Long? = null,
    val pendingRelativePath: String? = null,
    val pendingSizeBytes: Long? = null,
    val ownsPendingPath: Boolean = false,
    val committedToken: String? = null,
    val committedRelativePath: String? = null,
    val retiredRelativePath: String? = null,
    /** Ordered original/normalized loose inputs, retained until CONVERT cleanup is known complete. */
    val conversionSourceRoster: String? = null,
)

/** Retained receiver identity; never resolve a delayed operation against a new URL/title owner. */
data class ChapterArtifactOwner(val chapterId: Long, val mangaId: Long, val chapterUrl: String) {
    fun matches(chapter: SavedChapterEntity): Boolean =
        chapter.id == chapterId && chapter.mangaId == mangaId && chapter.url == chapterUrl

    companion object {
        fun of(chapter: SavedChapterEntity): ChapterArtifactOwner =
            ChapterArtifactOwner(chapter.id, chapter.mangaId, chapter.url)
    }
}

/** Original attempt authority. Keep this value through callbacks/finally; never refresh its token. */
data class ChapterArtifactClaim(
    val owner: ChapterArtifactOwner,
    val token: String,
    val operation: String,
    val downloadId: Long?,
    val pending: ChapterArtifactFile?,
    val conversionSourceRoster: String? = null,
) {
    val relativePath: String? get() = pending?.relativePath
}

/** Validated source size and the app-generated relative target are reserved before any copy. */
data class ChapterArtifactFile(val relativePath: String, val sizeBytes: Long)

/** Persisted operation names, not visible download queue states or expiring wall-clock leases. */
object ChapterArtifactOperation {
    const val DOWNLOAD = "download"
    const val RESTORE = "restore"
    const val CONVERT = "convert"
    const val DELETE = "delete"
}

/** Null means no operation owns this record; committed references may still remain. */
fun ChapterArtifactEntity.claimOrNull(): ChapterArtifactClaim? =
    token?.let { active ->
        operation?.let { kind ->
            ChapterArtifactClaim(
                ChapterArtifactOwner(chapterId, mangaId, chapterUrl), active, kind,
                downloadId,
                pendingRelativePath?.let { path -> pendingSizeBytes?.let { ChapterArtifactFile(path, it) } },
                conversionSourceRoster,
            )
        }
    }

/** Custody is stronger than publication authority: a retiring producer still owns its cleanup. */
fun ChapterArtifactEntity.isOwnedBy(claim: ChapterArtifactClaim): Boolean =
    chapterId == claim.owner.chapterId && mangaId == claim.owner.mangaId &&
        chapterUrl == claim.owner.chapterUrl && token == claim.token &&
        operation == claim.operation && downloadId == claim.downloadId &&
        pendingRelativePath == claim.relativePath && pendingSizeBytes == claim.pending?.sizeBytes &&
        conversionSourceRoster == claim.conversionSourceRoster
