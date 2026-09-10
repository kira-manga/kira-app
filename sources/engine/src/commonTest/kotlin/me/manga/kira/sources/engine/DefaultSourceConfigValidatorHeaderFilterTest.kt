package me.manga.kira.sources.engine

import me.manga.kira.sources.contracts.model.EndpointSpec
import me.manga.kira.sources.contracts.model.FilterConditionSpec
import me.manga.kira.sources.contracts.model.FilterDefinition
import me.manga.kira.sources.contracts.model.FilterOptionSpec
import me.manga.kira.sources.contracts.model.FilterRequestSpec
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultSourceConfigValidatorHeaderFilterTest {
    private val validator = DefaultSourceConfigValidator(DefaultStrategyRegistry())

    @Test
    fun header_names_are_exact_ascii_http_tokens_not_repaired_names() {
        val invalid =
            listOf(
                "",
                " ",
                " X-Lang",
                "X-Lang ",
                "X Lang",
                "\tX-Lang",
                "X-Lang\t",
                "X-\r\nLang",
                "X-\u0000Lang",
                "X-\u001fLang",
                "X-\u007fLang",
                "X-Láng",
                "X-İ",
                "X:Lang",
                "genre[]",
                "X(Lang)",
                "X/Lang",
                "X\\Lang",
                "X,Lang",
                "X;Lang",
                "X=Lang",
            )
        invalid.forEach { name -> assertHeaderRejected(header(name), INVALID_NAME) }
    }

    @Test
    fun forbidden_and_sensitive_header_names_are_case_insensitive() {
        listOf("CoOkIe", "sEt-CoOkIe", "PrOxY-AuThOrIzAtIoN").forEach { name ->
            assertHeaderRejected(header(name), FORBIDDEN_NAME)
        }
        listOf(
            "AuThOrIzAtIoN",
            "X-aPi-KeY",
            "ApI-kEy",
            "X-AuTh-ToKeN",
            "X-aCcEsS-tOkEn",
            "X-SeCrEt-Metadata",
            "PaSsWoRd-Hint",
        ).forEach { name -> assertHeaderRejected(header(name), SENSITIVE_NAME) }
    }

    @Test
    fun sensitive_filter_rejection_does_not_depend_on_control_values_or_visibility() {
        val visible = header("X-Visible", "visible", "toggle").copy(default = "false")
        sensitiveControls().forEach { filter ->
            assertHeaderRejected(filter, SENSITIVE_NAME, listOf(visible))
        }
        val hidden =
            header("Authorization").copy(
                visibleWhen = listOf(FilterConditionSpec("visible", listOf("true"))),
            )
        assertHeaderRejected(hidden, SENSITIVE_NAME, listOf(visible))
        assertHeaderRejected(header("Authorization").copy(default = "Bearer null", required = true), SENSITIVE_NAME)
    }

    @Test
    fun nonsensitive_tokens_and_static_public_placeholder_remain_valid() {
        listOf("X-Lang", "X-Content-Lang", "Accept", "0", "A!#\$%&'*+-.^_`|~09").forEach { name ->
            val source = source(listOf(header(name))).copy(headers = mapOf("authorization" to "Bearer null"))
            val result = validator.validate(document(source))
            assertTrue(result.isValid)
            assertEquals(emptyList(), result.errors)
        }
    }

    @Test
    fun all_findings_survive_without_echoing_submitted_filter_data() {
        val result = validator.validate(document(source(sentinelFilters())))
        val expected = sentinelErrors()

        assertFalse(result.isValid)
        assertEquals(expected.toSet(), result.errors.toSet())
        assertEquals(expected.size, result.errors.size)
        // Inspect the COMPLETE result, including findings from the dependent safe-header filter.
        SENTINELS.forEach { sentinel ->
            assertFalse(sentinel in result.toString(), "validation must not echo synthetic filter data")
        }
    }

    private fun sensitiveControls(): List<FilterDefinition> =
        listOf(
            header("Authorization", type = "select").copy(options = listOf(FilterOptionSpec("synthetic_option"))),
            header("X-Secret-List", type = "multiselect").copy(
                options = listOf(FilterOptionSpec("synthetic_option")),
                defaults = listOf("synthetic_option"),
                request = FilterRequestSpec("header", "X-Secret-List", "csv", "synthetic_delimiter"),
            ),
            header("X-Auth-Token", type = "toggle").copy(
                default = "false",
                request =
                    FilterRequestSpec(
                        "header",
                        "X-Auth-Token",
                        trueValue = "synthetic_on",
                        falseValue = "synthetic_off",
                        omitIfEmpty = false,
                    ),
            ),
            header("X-Password-Hint"),
            header("Api-Key", type = "number"),
        )

    private fun sentinelFilters(): List<FilterDefinition> =
        listOf(
            header("Authorization", "single", "select").copy(
                options = listOf(FilterOptionSpec(DUPLICATE), FilterOptionSpec(DUPLICATE)),
                default = SELECT_DEFAULT,
            ),
            header("X-Api-Key", "multi", "multiselect").copy(
                options = listOf(FilterOptionSpec("valid")),
                defaults = listOf(MULTI_DEFAULT),
                request = FilterRequestSpec("header", "X-Api-Key", encode = "csv", delimiter = CSV_DELIMITER),
            ),
            header("X-Auth-Token", "toggle", "toggle").copy(
                default = TOGGLE_DEFAULT,
                request = FilterRequestSpec("header", "X-Auth-Token", trueValue = TRUE_WIRE, falseValue = FALSE_WIRE),
            ),
            header("X-Secret-$HEADER_NAME", "number", "number").copy(default = NUMBER_DEFAULT),
            header("X-Lang", "dependent").copy(
                visibleWhen = listOf(FilterConditionSpec("single", listOf(CONDITION))),
            ),
        ) + overlappingFilters()

    private fun overlappingFilters(): List<FilterDefinition> {
        val included =
            header("Authorization", "included", "multiselect").copy(
                options = listOf(FilterOptionSpec(OVERLAP)),
                defaults = listOf(OVERLAP),
            )
        return listOf(included, included.copy(id = "excluded", excludeOf = "included"))
    }

    private fun sentinelErrors(): List<String> =
        listOf(
            finding("single", "options: duplicate option value (selection would be ambiguous)"),
            finding("single", "default: value is not a declared option value"),
            finding("multi", "defaults: value is not a declared option value"),
            finding("toggle", "default: toggle default must be 'true' or 'false'"),
            finding("number", "default: value is not numeric"),
            finding("dependent", "visibleWhen: anyOf value is not a possible value of filter 'single'"),
            finding(
                "excluded",
                "excludeOf: defaults overlap with filter 'included' — a value cannot default to included AND excluded",
            ),
        ) + listOf("single", "multi", "toggle", "number", "included", "excluded").map { id ->
            finding(id, "request.param: $SENSITIVE_NAME")
        }

    private fun assertHeaderRejected(
        filter: FilterDefinition,
        message: String,
        dependencies: List<FilterDefinition> = emptyList(),
    ) {
        val safeFilter = filter.copy(request = filter.request.copy(param = "X-Safe"))
        val safeResult = validator.validate(document(source(dependencies + safeFilter)))
        assertTrue(safeResult.isValid)
        assertEquals(emptyList(), safeResult.errors)

        val result = validator.validate(document(source(dependencies + filter)))
        val expected =
            buildList {
                if (filter.request.param.isBlank()) add(finding(filter.id, "request.param: must not be blank"))
                add(finding(filter.id, "request.param: $message"))
            }
        assertFalse(result.isValid)
        // Otherwise-valid fixtures must fail ONLY this semantic gate, not a default/encoding rule.
        assertEquals(expected, result.errors)
    }

    private fun source(filters: List<FilterDefinition>): SourceConfig =
        SourceConfig(
            api = "HeaderFilterSource",
            language = "en",
            baseUrl = "https://filters.example",
            engine = "generic",
            endpoints =
                mapOf(
                    "home" to EndpointSpec(url = "{baseUrl}/latest", listSelector = "div.item"),
                    "search" to EndpointSpec(url = "{baseUrl}/search?q={queryEncoded}", listSelector = "div.item"),
                ),
            filters = filters,
        )

    private fun header(
        name: String,
        id: String = "custom",
        type: String = "text",
    ): FilterDefinition =
        FilterDefinition(id = id, label = id, type = type, request = FilterRequestSpec("header", name))

    private fun document(source: SourceConfig): SourceConfigDocument =
        SourceConfigDocument(schemaVersion = 1, sources = listOf(source))

    private fun finding(
        id: String,
        suffix: String,
    ): String = "source 'HeaderFilterSource': filters: filter '$id': $suffix"

    private companion object {
        const val INVALID_NAME = "header name must be a non-empty ASCII HTTP token without whitespace"
        const val FORBIDDEN_NAME = "forbidden header names are not allowed for filters"
        const val SENSITIVE_NAME = "sensitive header names are not supported for filters"
        const val DUPLICATE = "BACKEND21_DUPLICATE_OPTION_SENTINEL"
        const val SELECT_DEFAULT = "BACKEND21_SELECT_DEFAULT_SENTINEL"
        const val MULTI_DEFAULT = "BACKEND21_MULTI_DEFAULT_SENTINEL"
        const val TOGGLE_DEFAULT = "BACKEND21_TOGGLE_DEFAULT_SENTINEL"
        const val NUMBER_DEFAULT = "BACKEND21_NUMBER_DEFAULT_SENTINEL"
        const val CONDITION = "BACKEND21_CONDITION_SENTINEL"
        const val TRUE_WIRE = "BACKEND21_TRUE_WIRE_SENTINEL"
        const val FALSE_WIRE = "BACKEND21_FALSE_WIRE_SENTINEL"
        const val CSV_DELIMITER = "BACKEND21_CSV_DELIMITER_SENTINEL"
        const val OVERLAP = "BACKEND21_OVERLAP_SENTINEL"
        const val HEADER_NAME = "BACKEND21_HEADER_NAME_SENTINEL"
        val SENTINELS =
            listOf(
                DUPLICATE,
                SELECT_DEFAULT,
                MULTI_DEFAULT,
                TOGGLE_DEFAULT,
                NUMBER_DEFAULT,
                CONDITION,
                TRUE_WIRE,
                FALSE_WIRE,
                CSV_DELIMITER,
                OVERLAP,
                HEADER_NAME,
            )
    }
}
