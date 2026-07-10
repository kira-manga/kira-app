package me.manga.kira.sources.runtime

import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.sources.contracts.HeaderStore
import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.SourceRequest
import me.manga.kira.sources.contracts.SourceResponse
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.engine.GenericSourceClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Lekmanga filter-migration PARITY (config-driven filters pilot, 2026-07 —
 * CONFIG_DRIVEN_FILTERS_PLAN.md §6): the bundled stanza's JSON-driven filter requests must match
 * the legacy `MangaLekRepositoryv2` behavior this migration replaces. The legacy spec is READ
 * (never edited) straight from the read-only `sources_repositry/` source file, so a drift between
 * the stanza's option lists and the legacy `allGenres`/`genreToMetaKeyMap` fails here rather than
 * shipping.
 *
 * Legacy request shape (MangaLekRepositoryv2.kt:374-408): POST madara form to
 * `{base}/wp-admin/admin-ajax.php` — `vars[wp-manga-genre]` carries the genre CSV
 * (genresSearchFormBody:385, sortFormBody:399), `vars[meta_key]` carries the sort's mapped
 * meta-key (sortFormBody:400, `genreToMetaKeyMap`:602-607), static madara keys around them.
 *
 * Documented deliberate divergences (server-equivalent, pinned below):
 *  - unselected filters OMIT their key (legacy SORT sent an empty-string `vars[wp-manga-genre]`);
 *  - `vars[posts_per_page]` stays 25 (the pre-existing generic-stanza divergence from legacy's
 *    20/25 split);
 *  - the 4 legacy sort labels collapse to their 2 DISTINCT meta-key values (`_latest_update`,
 *    `_wp_manga_views`) — duplicate backend values are validation-rejected and observable
 *    behavior is unchanged (3 legacy labels sent the same key).
 */
class LekmangaFilterParityTest {
    private class RecordingHttp : HttpExecutor {
        val requests = mutableListOf<SourceRequest>()

        override suspend fun execute(request: SourceRequest): SourceResponse {
            requests += request
            return SourceResponse(status = 200, body = "<html></html>")
        }
    }

    private object NoHeaders : HeaderStore {
        override suspend fun headersFor(api: String): Map<String, String> = emptyMap()

        override suspend fun save(api: String, headers: Map<String, String>) = Unit
    }

    private fun lekmangaConfig(): SourceConfig {
        val document =
            when (val parsed = SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON)) {
                is AppResult.Success -> parsed.value
                is AppResult.Failure -> fail("bundled document must parse: ${parsed.error}")
            }
        return document.sources.firstOrNull { it.api == "Lekmanga" } ?: fail("Lekmanga stanza missing")
    }

    /** The read-only legacy spec source this migration must stay faithful to. */
    private fun legacySpec(): String {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (dir.resolve("settings.gradle.kts").isFile) {
                return dir
                    .resolve(
                        "sources/legacy/src/commonMain/kotlin/me/manga/kira/" +
                            "sources_repositry/ar/mangalek/MangaLekRepositoryv2.kt",
                    ).readText()
            }
            dir = dir.parentFile ?: return@repeat
        }
        fail("could not locate the repo root from ${System.getProperty("user.dir")}")
    }

    @Test
    fun the_stanza_genre_options_match_the_legacy_allGenres_verbatim_in_declaration_order() {
        val spec = legacySpec()
        val genresBlock = spec.substringAfter("override val allGenres: Set<String> = setOf(").substringBefore(")")
        val legacyGenres = Regex("\"([^\"]+)\"").findAll(genresBlock).map { it.groupValues[1] }.toList()
        assertTrue(legacyGenres.size > 100, "extraction sanity: got ${legacyGenres.size} legacy genres")

        val stanzaGenres =
            lekmangaConfig().filters.firstOrNull { it.id == "genres" }
                ?: fail("Lekmanga stanza must declare the genres filter")
        // Legacy sends the DISPLAY string verbatim (value == label), one list, declaration order.
        assertEquals(legacyGenres, stanzaGenres.options.map { it.value })
        assertTrue(stanzaGenres.options.all { it.label.isEmpty() || it.label == it.value })
    }

    @Test
    fun the_stanza_sort_options_match_the_legacy_meta_key_map() {
        val spec = legacySpec()
        val mapBlock = spec.substringAfter("private val genreToMetaKeyMap = mapOf(").substringBefore(")")
        val legacyPairs =
            Regex("\"([^\"]+)\" to \"([^\"]+)\"").findAll(mapBlock).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue(legacyPairs.isNotEmpty(), "extraction sanity: legacy sort map found")

        val stanzaSort =
            lekmangaConfig().filters.firstOrNull { it.id == "sort" }
                ?: fail("Lekmanga stanza must declare the sort filter")
        // Every stanza sort value must be a real legacy meta-key, and every DISTINCT legacy
        // meta-key must be reachable (labels collapse; values don't).
        val legacyKeys = legacyPairs.map { it.second }.toSet()
        assertEquals(legacyKeys, stanzaSort.options.map { it.value }.toSet())
        // Each stanza label must be a legacy label that mapped to exactly that key.
        for (option in stanzaSort.options) {
            assertTrue(
                legacyPairs.any { (label, key) -> key == option.value && label == option.label },
                "sort option ${option.value}/${option.label} is not a legacy label→meta_key pair",
            )
        }
    }

    @Test
    fun sort_plus_genre_selection_produces_the_legacy_sortFormBody_shape() =
        runTest {
            val http = RecordingHttp()
            val client = GenericSourceClient(lekmangaConfig(), http, NoHeaders)

            val result =
                client.search(
                    query = "solo",
                    page = 1,
                    filters =
                        FilterSelections(
                            mapOf(
                                "sort" to listOf("_wp_manga_views"),
                                "genres" to listOf("fantasy", "إنتقام"),
                            ),
                        ),
                )
            assertTrue(result is AppResult.Success, "expected success, got $result")

            val request = http.requests.single()
            assertTrue(request.url.endsWith("/wp-admin/admin-ajax.php"), request.url)
            val form = request.formBody ?: fail("madara search must POST a form")
            val byKey = form.toMap()
            // Legacy sortFormBody parity (MangaLekRepositoryv2.kt:396-408): static madara keys +
            // the query + the genre CSV + the mapped meta-key.
            assertEquals("madara_load_more", byKey["action"])
            assertEquals("madara-core/content/content-archive", byKey["template"])
            assertEquals("0", byKey["page"])
            assertEquals("solo", byKey["vars[s]"])
            assertEquals("meta_value_num", byKey["vars[orderby]"])
            assertEquals("1", byKey["vars[paged]"])
            assertEquals("right", byKey["vars[sidebar]"])
            assertEquals("fantasy,إنتقام", byKey["vars[wp-manga-genre]"], "genre CSV — legacy comma-join parity")
            assertEquals("_wp_manga_views", byKey["vars[meta_key]"], "sort meta-key parity")
        }

    @Test
    fun genre_only_selection_matches_the_legacy_genresSearchFormBody_no_meta_key() =
        runTest {
            val http = RecordingHttp()
            val client = GenericSourceClient(lekmangaConfig(), http, NoHeaders)

            client.search(query = "", page = 1, filters = FilterSelections(mapOf("genres" to listOf("fantasy"))))

            val byKey = http.requests.single().formBody.orEmpty().toMap()
            assertEquals("fantasy", byKey["vars[wp-manga-genre]"])
            assertTrue("vars[meta_key]" !in byKey, "legacy genresSearchFormBody carries no meta_key")
            assertEquals("", byKey["vars[s]"], "genre browse runs with a blank query — legacy parity")
        }

    @Test
    fun plain_search_is_byte_identical_to_the_pre_migration_stanza_request() =
        runTest {
            val http = RecordingHttp()
            val client = GenericSourceClient(lekmangaConfig(), http, NoHeaders)

            client.search(query = "one", page = 1)

            val byKey = http.requests.single().formBody.orEmpty().toMap()
            // No filter keys on a plain search: the legacy normalSearchFormBody (lines 374-383)
            // carries neither, and the filters declare no defaults.
            assertTrue("vars[wp-manga-genre]" !in byKey, byKey.toString())
            assertTrue("vars[meta_key]" !in byKey, byKey.toString())
            assertEquals("one", byKey["vars[s]"])
            assertEquals("25", byKey["vars[posts_per_page]"], "pre-existing stanza divergence stays 25")
        }
}
