package me.manga.kira.sources.config

import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigMergerTest {

    private fun source(api: String, priority: Int = 0, label: String = api) =
        SourceConfig(api = api, language = "en", baseUrl = "https://$api.test", priority = priority, displayName = label)

    private fun doc(revision: Long, vararg sources: SourceConfig) =
        SourceConfigDocument(schemaVersion = 1, revision = revision, sources = sources.toList())

    @Test
    fun empty_input_yields_empty_floor() {
        val merged = ConfigMerger.merge(emptyList())
        assertEquals(-1, merged.revision)
        assertEquals(emptyList(), merged.sources)
    }

    @Test
    fun later_document_overrides_same_api_at_equal_priority() {
        val merged = ConfigMerger.merge(
            listOf(
                doc(1, source("a", label = "low")),
                doc(2, source("a", label = "high")),
            ),
        )
        assertEquals("high", merged.sources.single { it.api == "a" }.displayName)
        assertEquals(2, merged.revision)
    }

    @Test
    fun higher_priority_resists_override_from_later_document() {
        val merged = ConfigMerger.merge(
            listOf(
                doc(1, source("a", priority = 50, label = "pinned")),
                doc(2, source("a", priority = 0, label = "later")),
            ),
        )
        assertEquals("pinned", merged.sources.single { it.api == "a" }.displayName)
    }

    @Test
    fun union_of_distinct_apis_is_preserved() {
        val merged = ConfigMerger.merge(
            listOf(
                doc(1, source("a"), source("b")),
                doc(2, source("c")),
            ),
        )
        assertEquals(setOf("a", "b", "c"), merged.sources.map { it.api }.toSet())
    }
}
