package me.manga.kira.sources.engine

import kotlinx.serialization.json.Json
import me.manga.kira.source.contracts.model.SourceConfig as SharedSourceConfig
import me.manga.kira.source.engine.SourceDeclarationCapabilities
import me.manga.kira.sources.contracts.StrategyRegistry
import me.manga.kira.sources.contracts.model.EndpointSpec
import me.manga.kira.sources.contracts.model.FieldSpec
import me.manga.kira.sources.contracts.model.IconSpec
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import me.manga.kira.sources.testkit.DeclarationCapabilityFixtures
import me.manga.kira.sources.testkit.ExpectedDeclarationFinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceDeclarationBridgeTest {
    private val strategies = DefaultStrategyRegistry()
    private val declarations = SourceDeclarationBridge(strategies)
    private val validator = DefaultSourceConfigValidator(strategies)

    @Test
    fun separate_contract_defaults_and_optional_metadata_survive_the_execution_bridge() {
        val app = SourceConfig(api = "bridge-fixture", language = "en", baseUrl = "https://fixture.example")
        val shared = SharedSourceConfig(api = app.api, language = app.language, baseUrl = app.baseUrl)
        assertEquals(shared, app.toSharedConfig())

        val explicit =
            app.copy(
                minAppVersion = "1.2.3",
                icon = IconSpec(resourceKey = "fixture_icon", remoteUrl = "https://fixture.example/icon.png"),
                enabled = true,
                priority = 7,
                usesCapturedHeaders = false,
                previousHosts = listOf("previous.example"),
                previousImageHosts = listOf("old-images.example"),
                trustedHosts = listOf("trusted.example"),
            )
        assertEquals(explicit, explicit.toSharedConfig().toAppConfig())
    }

    @Test
    fun selected_shared_vectors_keep_models_structured_findings_and_shipping_validator_results() {
        val sharedChecker = SourceDeclarationCapabilities()
        for (id in SELECTED_FIXTURES) {
            val fixture = DeclarationCapabilityFixtures.cases.single { it.id == id }
            val app = fixture.source.toAppConfig()
            assertEquals(fixture.source, app.toSharedConfig(), id)
            val findings = declarations.validate(app)
            assertEquals(fixture.expectedFindings, findings.map { ExpectedDeclarationFinding(it.code, it.path) }, id)
            assertEquals(sharedChecker.validate(fixture.source), findings, id)

            val result = validator.validate(document(app))
            assertEquals(fixture.expectedFindings.isEmpty(), result.isValid, "$id: ${result.errors}")
            assertEquals(
                findings.map { "source '${app.api}': ${it.path}: [${it.code}] ${it.message}" },
                result.errors,
                id,
            )
        }
    }

    @Test
    fun ordinal_diagnostics_preserve_map_order_without_echoing_submitted_keys_or_values() {
        val app = indexedSource()
        val shared = app.toSharedConfig()
        assertEquals(listOf("z_first", PRIVATE_MARKER), shared.endpoints.getValue("home").formBody.keys.toList())
        assertEquals(listOf("z_first", PRIVATE_MARKER), shared.fields.getValue("item.title").vars.keys.toList())
        assertEquals(
            listOf(
                ExpectedDeclarationFinding("template.variable.unsupported", "endpoints[home].formBody[1].value"),
                ExpectedDeclarationFinding("field.variable.transform", "fields[item.title].vars[1]"),
            ),
            declarations.validate(app).map { ExpectedDeclarationFinding(it.code, it.path) },
        )
        val result = validator.validate(document(app))
        assertFalse(result.isValid)
        assertEquals(INDEXED_ERRORS, result.errors)
        assertTrue(result.errors.none { PRIVATE_MARKER in it })
    }

    @Test
    fun variable_pipes_honor_the_callers_strategy_registry_instead_of_shared_defaults() {
        val app = source(fields = mapOf("item.title" to pipedField()))
        assertEquals(emptyList(), validator.validate(document(app)).errors)
        val restricted =
            object : StrategyRegistry by strategies {
                override fun hasTransform(name: String): Boolean = name != "trim" && strategies.hasTransform(name)
            }
        val result = DefaultSourceConfigValidator(restricted).validate(document(app))
        assertFalse(result.isValid)
        assertEquals(
            listOf(ExpectedDeclarationFinding("field.variable.transform", "fields[item.title].vars[0]")),
            SourceDeclarationBridge(restricted).validate(app).map { ExpectedDeclarationFinding(it.code, it.path) },
        )
        assertTrue(result.errors.single().contains("[field.variable.transform]"))
    }

    @Test
    fun schema_and_collection_guards_run_before_declaration_strategy_queries() {
        val noDetailedQueries =
            object : StrategyRegistry by strategies {
                override fun hasTransform(name: String): Boolean =
                    error("Declaration checks must not run before guards")
            }
        val guarded = DefaultSourceConfigValidator(noDetailedQueries)
        val app = source(fields = mapOf("item.title" to pipedField()))
        val schema = guarded.validate(document(app).copy(schemaVersion = 99))
        assertEquals(listOf("schemaVersion 99 is unsupported (this build expects 1)"), schema.errors)

        val sourceCount = DefaultSourceConfigValidator.MAX_SOURCES + 1
        val oversized = guarded.validate(SourceConfigDocument(1, sources = List(sourceCount) { app }))
        assertEquals(listOf("document contains $sourceCount sources; maximum is 512"), oversized.errors)

        val headerCount = DefaultSourceConfigValidator.MAX_COLLECTION_ENTRIES + 1
        val complex = app.copy(headers = (0 until headerCount).associate { "X-Fixture-$it" to "value" })
        assertEquals(
            listOf("source 'bridge-fixture': headers contains $headerCount entries; maximum is 256"),
            guarded.validate(document(complex)).errors,
        )
    }

    private fun indexedSource(): SourceConfig {
        val field =
            FieldSpec(
                template = "{z_first}",
                vars = linkedMapOf("z_first" to "title|trim", PRIVATE_MARKER to "title|$PRIVATE_MARKER"),
            )
        val endpoint =
            EndpointSpec(
                url = "{baseUrl}/home",
                method = "post-form",
                format = "json",
                root = "items",
                formBody = linkedMapOf("z_first" to "{page}", PRIVATE_MARKER to "$PRIVATE_MARKER-{unknown}"),
            )
        return source(fields = mapOf("item.title" to field)).copy(endpoints = mapOf("home" to endpoint))
    }

    private fun source(fields: Map<String, FieldSpec> = emptyMap()): SourceConfig =
        SourceConfig(
            api = "bridge-fixture",
            language = "en",
            baseUrl = "https://fixture.example",
            engine = "generic",
            endpoints = mapOf("home" to EndpointSpec("{baseUrl}/home", format = "json", root = "items")),
            fields = fields,
        )

    private fun pipedField(): FieldSpec = FieldSpec(template = "{id}", vars = mapOf("id" to "title|trim"))

    private fun document(source: SourceConfig): SourceConfigDocument = SourceConfigDocument(1, sources = listOf(source))

    // Reverse conversion only for fixture input/readback; production's forward bridge is under test.
    private fun SharedSourceConfig.toAppConfig(): SourceConfig =
        fixtureJson.decodeFromString(
            SourceConfig.serializer(),
            fixtureJson.encodeToString(SharedSourceConfig.serializer(), this),
        )

    private companion object {
        val fixtureJson =
            Json {
                encodeDefaults = true
                explicitNulls = false
            }
        const val PRIVATE_MARKER = "SYNTHETIC_DECLARATION_VALUE"
        val INDEXED_ERRORS =
            listOf(
                "source 'bridge-fixture': endpoints[home].formBody[1].value: [template.variable.unsupported] " +
                    "Template references a variable not supplied in this context.",
                "source 'bridge-fixture': fields[item.title].vars[1]: [field.variable.transform] " +
                    "A variable pipe step must name a compiled transform without pipe arguments.",
            )

        // Representative consumer/model routes, not a second copy of the full Engine grammar corpus.
        val SELECTED_FIXTURES =
            listOf(
                "all_seeded_request_names_including_empty",
                "placeholder_filters_share_active_request_namespace",
                "hidden_placeholder_is_not_a_runtime_binding_guarantee",
                "page_root_and_dir_without_root_dirs",
                "mixed_formats_respect_field_overrides",
                "script_json_list_scalar_details_and_pages",
                "counter_in_active_json_body",
                "unknown_url_variable",
                "unknown_form_variable_POST_FORM",
                "json_fallback_path",
                "malformed_css_field",
                "unsupported_var_pipe_id|unknown",
                "page_counter_does_not_update_page_offset",
                "script_json_inline_chapters_unsupported",
            )
    }
}
