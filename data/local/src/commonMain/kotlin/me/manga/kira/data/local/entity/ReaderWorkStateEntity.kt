package me.manga.kira.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable progress owner, independent of saved-library rows and their deletion cascades. */
@Entity(
    tableName = "reader_work_state",
    indices = [Index(value = ["api", "workUrl"], unique = true)],
)
data class ReaderWorkStateEntity(
    @PrimaryKey(autoGenerate = true)
    val workId: Long = 0,
    val api: String,
    val workUrl: String,
    @ColumnInfo(defaultValue = "0")
    val workGeneration: Long = 0,
) {
    init {
        require(workId >= 0 && workGeneration >= 0)
    }
}
