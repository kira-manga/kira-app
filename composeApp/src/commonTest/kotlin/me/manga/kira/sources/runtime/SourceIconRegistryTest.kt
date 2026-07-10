package me.manga.kira.sources.runtime

import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.SourceConfigParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the packaged-drawable icon registry contract (MangaSource decoupling, 2026-07):
 * keys are unique and match the validator vocabulary, lookups are total (unknown → null, never a
 * crash), and — the build gate — every `icon.resourceKey` referenced by a bundled stanza actually
 * resolves, so a stanza can never ship pointing at a drawable this build doesn't map.
 */
class SourceIconRegistryTest {
    private val keyVocabulary = Regex("[a-z0-9_]{1,64}")

    @Test
    fun registry_keys_are_unique() {
        val keys = SourceIconRegistry.entries.map { it.first }
        assertEquals(keys.distinct(), keys, "duplicate resourceKey in SourceIconRegistry")
    }

    @Test
    fun registry_keys_match_the_validator_vocabulary() {
        val bad = SourceIconRegistry.entries.map { it.first }.filterNot { it.matches(keyVocabulary) }
        assertEquals(emptyList(), bad, "keys outside [a-z0-9_]{1,64} would be rejected at config validation")
    }

    @Test
    fun known_key_resolves_and_unknown_key_returns_null() {
        assertNotNull(SourceIconRegistry.resolve("azora"))
        assertNull(SourceIconRegistry.resolve("no_such_icon"))
    }

    @Test
    fun every_bundled_stanza_icon_key_resolves_in_the_registry() {
        val document = (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value
        val unresolved =
            document.sources
                .mapNotNull { stanza ->
                    stanza.icon
                        ?.resourceKey
                        ?.takeIf { it.isNotBlank() }
                        ?.let { stanza.api to it }
                }.filter { (_, key) -> SourceIconRegistry.resolve(key) == null }
        assertEquals(
            emptyList(),
            unresolved,
            "stanzas referencing a resourceKey this build's SourceIconRegistry cannot resolve — the " +
                "row would silently fall back to the initials avatar",
        )
    }
}
