package me.manga.kira.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Complete local payload; a digest is consistency evidence, not signature/bundle authentication. */
@Entity(tableName = "effective_source_selection")
data class EffectiveSourceSelectionEntity(
    @PrimaryKey val id: Int = 0,
    val generation: Long,
    val payload: String,
    val payloadDigest: String,
)

/** Seeded ONLY during fresh creation / migration. Never reconstructed from the selected record. */
@Entity(tableName = "source_selection_generation")
data class SourceSelectionGenerationEntity(
    @PrimaryKey val id: Int = 0,
    val nextGeneration: Long,
)
