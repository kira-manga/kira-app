    package me.manga.yamiapk.data.local.entity

    import androidx.room.Entity
    import androidx.room.Index
    import androidx.room.PrimaryKey
    import me.manga.yamiapk.presentation.features.repo_settings.domain.SourceState
    @Entity(
        tableName = "sources"
    )
    data class SourcesEntity(
        @PrimaryKey
        val name: String,
        val isEnabled: Boolean = true,
        val priority: Int,
        val language: String,
        val siteState: SourceState = SourceState.WORKING,
        val baseUrl: String = "",
        val baseVersion: Int = 0,
        val imageBaseUrl: String,
        val imageUrlVersion: Int
    )