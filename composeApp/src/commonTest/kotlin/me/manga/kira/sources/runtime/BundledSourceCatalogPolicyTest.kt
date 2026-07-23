package me.manga.kira.sources.runtime

import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Release gate for the exact approved generic-only offline floor. */
class BundledSourceCatalogPolicyTest {
    private val document =
        (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value

    @Test
    fun bundled_catalog_contains_exactly_the_approved_twelve_in_order() {
        assertEquals(APPROVED_APIS, document.sources.map { it.api })
        assertTrue(document.sources.all { it.engine == "generic" && it.lifecycle == "active" })
    }

    @Test
    fun bundled_catalog_contains_no_legacy_or_kotlin_stanza() {
        assertTrue(document.sources.none { it.engine == "legacy" || it.engine.startsWith("kotlin:") })
    }

    @Test
    fun bundled_catalog_passes_the_shipping_validator() {
        val validation = DefaultSourceConfigValidator(DefaultStrategyRegistry()).validate(document)
        assertEquals(emptyList(), validation.errors)
    }

    private companion object {
        val APPROVED_APIS =
            listOf(
                "Azora",
                "Mangamello",
                "Mangamello Plus",
                "SwatManga",
                "Lekmanga",
                "Team X",
                "DilarV2",
                "3asq",
                "Demonicscans",
                "Mangabuddy",
                "Zazamanga",
                "Tapas",
            )
    }
}
