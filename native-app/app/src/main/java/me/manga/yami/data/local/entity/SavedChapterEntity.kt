package me.manga.yamiapk.data.local.entity

    import android.os.Parcelable
    import androidx.room.Entity
    import androidx.room.ForeignKey
    import androidx.room.Index
    import androidx.room.PrimaryKey
    import kotlinx.parcelize.Parcelize
    import java.time.LocalDate

    @Entity(
        tableName = "saved_chapters",
        foreignKeys = [
            ForeignKey(
                entity        = SavedMangaEntity::class,
                parentColumns = ["id"],
                childColumns  = ["mangaId"],
                onDelete      = ForeignKey.CASCADE
            )
        ],
        indices = [
            Index(value = ["mangaId", "url"], unique = true)
        ]
    )
    @Parcelize
    data class SavedChapterEntity(
        @PrimaryKey(autoGenerate = true)
        val id: Long = 0,
        val mangaId: Long,
        val name: String,
        val number: String,
        val url: String,
        val date: LocalDate? = LocalDate.now(),
        val isDownloaded: Boolean = false,
        val isBookmarked: Boolean = false,
        val isRead: Boolean = false,
        val isNew: Boolean = false,
        val lastReadPage: Int = 0,
        val lastReadDate: Long = 0,
        val localImagePaths: List<String> = emptyList() // Store local paths of downloaded images
    ): Parcelable

