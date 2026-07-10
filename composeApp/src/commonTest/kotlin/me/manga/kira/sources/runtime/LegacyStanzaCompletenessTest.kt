package me.manga.kira.sources.runtime

import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry
import me.manga.kira.sources_repositry.data.MangaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SourceRegistry retirement Phase 4 (Option A): the bundled config document is the literal single
 * authority for EVERY source the app has ever shipped — each legacy api carries a metadata-only
 * `engine:"legacy"` stanza (lifecycle/host metadata, no behavior). This test is the LEGACY
 * completeness gate: a [MangaSource] scraper without a stanza, or a legacy stanza without a
 * scraper, fails the build, so authority can never silently fragment for the legacy set.
 *
 * MangaSource decoupling (2026-07): GENERIC stanzas are deliberately exempt from the enum — adding
 * a config-backed source requires ONLY a JSON stanza, never a [MangaSource] entry
 * (docs/sources/MANGASOURCE_DECOUPLING_PLAN.md). Where a generic stanza happens to share an api
 * with a legacy scraper (the 12 converted pilots), the language must still agree with the enum so
 * stored rows keyed on (api, language) stay coherent.
 */
class LegacyStanzaCompletenessTest {
    private val document =
        (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value

    @Test
    fun every_registry_api_has_exactly_one_stanza_with_a_matching_language() {
        val byApi = document.sources.groupBy { it.api }

        val missing = MangaSource.entries.map { it.API }.filter { byApi[it]?.size != 1 }
        assertEquals(emptyList(), missing, "registry apis without exactly one config stanza")

        for (source in MangaSource.entries) {
            val stanza = byApi.getValue(source.API).single()
            assertEquals(source.LANGUAGE.Language, stanza.language, "language mismatch for ${source.API}")
        }
    }

    @Test
    fun the_bundled_document_validates_with_the_shipping_validator() {
        val result = DefaultSourceConfigValidator(DefaultStrategyRegistry()).validate(document)
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun legacy_stanzas_are_metadata_only() {
        val legacy = document.sources.filter { it.engine == "legacy" }
        for (stanza in legacy) {
            assertTrue(
                stanza.endpoints.isEmpty() && stanza.fields.isEmpty(),
                "${stanza.api} must carry lifecycle metadata only, no executable behavior",
            )
        }
    }

    @Test
    fun every_legacy_stanza_names_a_shipped_scraper() {
        // A metadata-only legacy stanza whose api matches NO compiled scraper is a ghost: the
        // catalog sync would manage a row for a source that can never run. Generic stanzas are
        // exempt — they ARE the source (no Kotlin required).
        val registryApis = MangaSource.entries.mapTo(mutableSetOf()) { it.API }
        val ghosts =
            document.sources
                .filter { it.engine != "generic" }
                .map { it.api }
                .filter { it !in registryApis }
        assertEquals(emptyList(), ghosts, "legacy stanzas naming no shipped scraper")
    }
}
