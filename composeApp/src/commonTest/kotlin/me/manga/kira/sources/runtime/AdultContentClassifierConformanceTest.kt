package me.manga.kira.sources.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.repository.AdultContentClassifierImpl
import me.manga.kira.sources.contracts.model.EndpointSpec
import me.manga.kira.sources.contracts.model.FieldSpec
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import me.manga.kira.sources.engine.GenericSourceClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Exercises the real classifier and pinned engine through one active source registry.
 * Discovery extraction removes blank labels, so these equal-input rows use nonblank labels or
 * empty genre lists, with no configured transforms. This is not raw blank-string parity.
 */
class AdultContentClassifierConformanceTest {
    @Test
    fun classifierMatchesDiscoveryForCaseInsensitiveSubstrings() =
        runTest {
            assertConformance(
                blacklist = listOf("yaoi", "smut", "é"),
                rows =
                    listOf(
                        GenreRow("exact", listOf("Romance", "yaoi"), expectedAdult = true),
                        GenreRow("case-varied", listOf("Yaoi"), expectedAdult = true),
                        GenreRow("composite", listOf("Smut Romance"), expectedAdult = true),
                        GenreRow("infix", listOf("Romance (smutty)"), expectedAdult = true),
                        GenreRow("clean", listOf("Action", "Drama"), expectedAdult = false),
                        GenreRow("empty", emptyList(), expectedAdult = false),
                        GenreRow("unicode-case", listOf("École"), expectedAdult = true),
                        GenreRow("unicode-decomposed", listOf("e\u0301cole"), expectedAdult = false),
                    ),
            )
        }

    @Test
    fun emptyBlacklistKeepsGenresAndDoesNotClassifyAdult() =
        runTest {
            assertConformance(
                blacklist = emptyList(),
                rows =
                    listOf(
                        GenreRow("case-varied", listOf("Yaoi"), expectedAdult = false),
                        GenreRow("composite", listOf("Smut Romance"), expectedAdult = false),
                        GenreRow("empty", emptyList(), expectedAdult = false),
                    ),
            )
        }

    @Test
    fun emptyBlacklistEntryMatchesNonemptyGenreLists() =
        runTest {
            assertConformance(
                blacklist = listOf(""),
                rows =
                    listOf(
                        GenreRow("ordinary", listOf("Action"), expectedAdult = true),
                        GenreRow("composite", listOf("Smut Romance"), expectedAdult = true),
                        GenreRow("empty", emptyList(), expectedAdult = false),
                    ),
            )
        }

    @Test
    fun unknownSourceHasNoClassificationAuthorityOrClient() {
        val registry = registryFixture(blacklist = listOf("yaoi"), rows = emptyList())
        val classifier = AdultContentClassifierImpl(registry)

        assertFalse(classifier.isAdultContent("unknown-api", listOf("Yaoi")))
        assertNull(registry.descriptor("unknown-api"))
        assertNull(registry.get("unknown-api"))
    }

    private suspend fun assertConformance(
        blacklist: List<String>,
        rows: List<GenreRow>,
    ) {
        val registry = registryFixture(blacklist, rows)
        val classifier = AdultContentClassifierImpl(registry)
        val client = assertNotNull(registry.get(API))
        val home =
            when (val result = client.home(1)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> fail("home failed: ${result.error}")
            }
        val keptUrls = home.map { it.url }
        assertEquals(
            rows.filterNot { it.expectedAdult }.map { "$BASE_URL/${it.id}" },
            keptUrls,
            "discovery survivors must match the explicit row expectations",
        )
        rows.forEach { row ->
            val isAdult = classifier.isAdultContent(API, row.genres)
            assertEquals(row.expectedAdult, isAdult, "${row.id}: classifier verdict")
            assertEquals(isAdult, "$BASE_URL/${row.id}" !in keptUrls, "${row.id}: discovery parity")
        }
    }

    private fun registryFixture(
        blacklist: List<String>,
        rows: List<GenreRow>,
    ): DefaultSourceRegistry {
        val document = SourceConfigDocument(schemaVersion = 1, revision = 1, sources = listOf(source(blacklist)))
        val http = MapFakeHttp(mapOf("$BASE_URL/home?page=1" to responseBody(rows)))
        return DefaultSourceRegistry(StaticSourceUpdateManager(document)) { config ->
            GenericSourceClient(config, http, NoopHeaderStore())
        }
    }

    private fun source(blacklist: List<String>): SourceConfig =
        SourceConfig(
            api = API,
            language = "en",
            baseUrl = BASE_URL,
            engine = "generic",
            blacklistGenres = blacklist,
            endpoints =
                mapOf(
                    "home" to EndpointSpec(url = "{baseUrl}/home?page={page}", format = "json", root = "items"),
                ),
            fields =
                mapOf(
                    "item.title" to FieldSpec(path = "title"),
                    "item.url" to FieldSpec(path = "url"),
                    "item.genres" to FieldSpec(listPath = "genres[*]"),
                ),
        )

    private fun responseBody(rows: List<GenreRow>): String {
        val items =
            rows.map { row ->
                buildJsonObject {
                    put("title", row.id)
                    put("url", "/${row.id}")
                    put("genres", JsonArray(row.genres.map { JsonPrimitive(it) }))
                }
            }
        return buildJsonObject { put("items", JsonArray(items)) }.toString()
    }

    private data class GenreRow(
        val id: String,
        val genres: List<String>,
        val expectedAdult: Boolean,
    )

    private companion object {
        const val API = "adult-policy-json"
        const val BASE_URL = "https://adult-policy.example"
    }
}
