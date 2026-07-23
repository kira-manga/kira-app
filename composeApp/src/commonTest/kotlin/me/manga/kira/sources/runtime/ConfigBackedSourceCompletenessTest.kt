package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.sources.config.RemoteSourceConfigManager
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Release gate for the config-backed sources (pre-release hardening, 2026-07). Config-backed sources
 * are GENERIC-ONLY — [FallbackSourceClient] is retained-but-unwired, so there is no legacy safety
 * net: a stanza that fails validation rejects the WHOLE bundled document (all generic sources lost
 * at once), and a missing endpoint is a user-visible runtime failure, not a silent legacy fallback.
 * Both failure modes must therefore be caught at build time, by this class:
 *
 *  1. the bundled document parses, with a readable error when it does not;
 *  2. the bundled document passes the shipping validator ([DefaultSourceConfigValidator]), with the
 *     per-stanza error list in the failure message;
 *  3. every `engine:"generic"` stanza resolves through the REAL production assembly
 *     ([RemoteSourceConfigManager] over [BundledSourceConfigStore] → [DefaultSourceRegistry]) —
 *     `isConfigBacked(api)` is true and `get(api)` returns the generic client. (The stanzas ARE the
 *     authority — the compiled CONFIG_BACKED_APIS allow-list was removed, MangaSource decoupling
 *     2026-07 — so this pins document ⇒ registry reachability.);
 *  4. every config-backed stanza defines every user-facing endpoint: home or featured, search,
 *     details, pages.
 *
 * [BundledSourceCatalogPolicyTest] pins the exact approved source set and order; this class pins
 * reachability and endpoint coverage. See docs/sources/ADDING_SOURCES.md.
 */
class ConfigBackedSourceCompletenessTest {
    private val document =
        when (val parsed = SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON)) {
            is AppResult.Success -> parsed.value
            is AppResult.Failure ->
                fail(
                    "CONFIG_BACKED_SOURCES_JSON does not parse — at runtime the app would fall back to an " +
                        "EMPTY document and lose every generic source: ${parsed.error}",
                )
        }

    /** The generic set, derived from the document itself — the single authority. */
    private val genericApis =
        document.sources
            .filter { it.engine == "generic" }
            .map { it.api }

    @Test
    fun bundled_document_passes_the_shipping_validator() {
        val result = DefaultSourceConfigValidator(DefaultStrategyRegistry()).validate(document)
        if (!result.isValid) {
            fail(
                "Bundled source config is INVALID. Validation is all-or-nothing: at runtime the WHOLE " +
                    "document is rejected and every generic source disappears. Errors:\n" +
                    result.errors.joinToString("\n") { "  - $it" },
            )
        }
    }

    @Test
    fun every_config_backed_api_is_reachable_through_the_real_registry_assembly() {
        // The exact production wiring from SourcesGenericModule, minus Room (bundled tier only —
        // The isolated registry test intentionally exercises only its bundled floor; signed remote
        // delivery is covered by KtorRemoteConfigSourceTest and RemoteSourceConfigManagerTest.
        val manager =
            RemoteSourceConfigManager(
                store = BundledSourceConfigStore(CONFIG_BACKED_SOURCES_JSON),
                verifier = DenyRemoteSignatureVerifier(),
                validator = DefaultSourceConfigValidator(DefaultStrategyRegistry()),
                remote = null,
            )
        val registry =
            DefaultSourceRegistry(
                updateManager = manager,
                genericClientFactory = { config -> MarkerClient("generic:${config.api}") },
            )

        val unreachable = genericApis.filterNot(registry::isConfigBacked)
        assertEquals(
            emptyList(),
            unreachable,
            "generic stanzas that isConfigBacked() cannot reach — the stanza failed validation (the " +
                "whole document would be rejected at runtime) or the registry assembly regressed",
        )

        val notGeneric = genericApis.filter { api -> registry.get(api)?.api != "generic:$api" }
        assertEquals(
            emptyList(),
            notGeneric,
            "apis whose registry.get() did not return the generic client",
        )
    }

    @Test
    fun every_config_backed_source_defines_all_user_facing_endpoints() {
        val stanzas = document.sources.filter { it.engine == "generic" }

        val problems =
            buildList {
                for (stanza in stanzas) {
                    val api = stanza.api
                    val endpoints = stanza.endpoints.keys
                    if ("home" !in endpoints && "featured" !in endpoints) {
                        add("$api: defines neither \"home\" nor \"featured\" (one is required)")
                    }
                    for (verb in listOf("search", "details", "pages")) {
                        if (verb !in endpoints) {
                            add("$api: missing endpoint \"$verb\"")
                        }
                    }
                }
            }
        assertEquals(
            emptyList(),
            problems,
            "config-backed sources have NO legacy fallback — an omitted endpoint surfaces to the user " +
                "as AppError.Validation.Required(\"endpoint:<verb>\") on every use of that verb",
        )
    }

    /** Never exercised — the completeness test only inspects [MangaSourceClient.api]. */
    private class MarkerClient(
        override val api: String,
    ) : MangaSourceClient {
        override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> = error("not exercised")

        override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> = error("not exercised")

        override suspend fun search(
            query: String,
            page: Int,
            filters: FilterSelections,
        ): AppResult<List<HomeFeedItem>> = error("not exercised")

        override suspend fun details(manga: Manga): AppResult<MangaDetails> = error("not exercised")

        override fun pages(
            manga: Manga,
            chapter: Chapter,
        ): Flow<AppResult<List<Page>>> = error("not exercised")
    }
}
