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
 * `engine:"legacy"` stanza (lifecycle/host metadata, no behavior). This test is the completeness
 * gate: adding a source to the [MangaSource] registry without a config stanza (or vice versa)
 * fails the build, so authority can never silently fragment again.
 */
class LegacyStanzaCompletenessTest {
    private val document =
        (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value

    @Test
    fun every_registry_api_has_exactly_one_stanza_with_the_right_engine_and_language() {
        val byApi = document.sources.groupBy { it.api }

        val missing = MangaSource.entries.map { it.API }.filter { byApi[it]?.size != 1 }
        assertEquals(emptyList(), missing, "registry apis without exactly one config stanza")

        for (source in MangaSource.entries) {
            val stanza = byApi.getValue(source.API).single()
            val expectGeneric = source.API in CONFIG_BACKED_APIS
            assertEquals(
                expectGeneric,
                stanza.engine == "generic",
                "engine mismatch for ${source.API} (config-backed apis are generic, all others legacy)",
            )
            assertEquals(source.LANGUAGE.Language, stanza.language, "language mismatch for ${source.API}")
        }
    }

    @Test
    fun the_bundled_document_validates_with_the_shipping_validator() {
        val result = DefaultSourceConfigValidator(DefaultStrategyRegistry()).validate(document)
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun legacy_stanzas_are_metadata_only_and_never_config_backed() {
        val legacy = document.sources.filter { it.engine == "legacy" }
        val expectedCount = MangaSource.entries.count { it.API !in CONFIG_BACKED_APIS }
        assertEquals(expectedCount, legacy.size)
        for (stanza in legacy) {
            assertTrue(stanza.api !in CONFIG_BACKED_APIS, "${stanza.api} must not be config-backed")
            assertTrue(
                stanza.endpoints.isEmpty() && stanza.fields.isEmpty(),
                "${stanza.api} must carry lifecycle metadata only, no executable behavior",
            )
        }
    }

    @Test
    fun every_stanza_api_is_a_known_source_and_every_generic_stanza_is_config_backed() {
        // The reverse direction (2026-07 audit — previously one-directional): a stanza for an api
        // in NEITHER the registry NOR CONFIG_BACKED_APIS passed all gates, yet an engine="generic"
        // ghost stanza would be seeded into the catalog by the sync (seedIfGeneric). Every stanza
        // must name a known source, and every generic stanza must be in CONFIG_BACKED_APIS.
        val registryApis = MangaSource.entries.mapTo(mutableSetOf()) { it.API }
        val ghosts = document.sources.map { it.api }.filter { it !in registryApis }
        assertEquals(emptyList(), ghosts, "config stanzas naming no known source")

        val strayGeneric =
            document.sources
                .filter { it.engine == "generic" }
                .map { it.api }
                .filter { it !in CONFIG_BACKED_APIS }
        assertEquals(emptyList(), strayGeneric, "generic stanzas not declared in CONFIG_BACKED_APIS")
    }
}
