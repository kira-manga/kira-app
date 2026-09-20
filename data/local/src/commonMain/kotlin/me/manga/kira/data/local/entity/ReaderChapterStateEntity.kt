package me.manga.kira.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A durable chapter anchor; null progress is distinct from an explicitly saved page zero. */
@Entity(
    tableName = "reader_chapter_state",
    foreignKeys = [
        ForeignKey(
            entity = ReaderWorkStateEntity::class,
            parentColumns = ["workId"],
            childColumns = ["workId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["workId", "chapterUrl"], unique = true)],
)
data class ReaderChapterStateEntity(
    @PrimaryKey(autoGenerate = true)
    val chapterId: Long = 0,
    val workId: Long,
    val chapterUrl: String,
    @ColumnInfo(defaultValue = "0")
    val chapterGeneration: Long = 0,
    val pageIndex: Int? = null,
) {
    init {
        require(chapterId >= 0 && workId > 0 && chapterGeneration >= 0)
        require(pageIndex == null || pageIndex >= 0)
    }
}
