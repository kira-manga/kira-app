package me.manga.kira.sources.runtime

import me.manga.kira.data.local.entity.SourceRevisionArtifactEntity
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RoomSourceCatalogStoreTest {
    private val stored =
        SourceRevisionArtifactEntity(
            api = "Azora",
            sourceRevision = 4,
            checksum = "a".repeat(64),
            canonVersion = "kcj-1",
            rawPayload = """{"api":"Azora"}""",
        )

    @Test
    fun identical_immutable_revision_can_be_reused() {
        requireSameImmutableSourceRevision(stored, stored.toContract())
    }

    @Test
    fun same_identity_with_different_checksum_or_bytes_fails_closed() {
        assertFailsWith<IllegalArgumentException> {
            requireSameImmutableSourceRevision(
                stored,
                stored.toContract().copy(checksum = "b".repeat(64)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            requireSameImmutableSourceRevision(
                stored,
                stored.toContract().copy(payload = """{"api":"Other"}"""),
            )
        }
    }

    private fun SourceRevisionArtifactEntity.toContract(): SourceRevisionArtifact =
        SourceRevisionArtifact(api, sourceRevision, checksum, canonVersion, rawPayload)
}
