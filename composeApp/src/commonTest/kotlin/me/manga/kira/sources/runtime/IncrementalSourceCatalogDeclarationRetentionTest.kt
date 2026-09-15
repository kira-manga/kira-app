package me.manga.kira.sources.runtime

import kotlinx.coroutines.test.runTest
import me.manga.kira.sources.runtime.IncrementalSourceCatalogHeaderFilterRetentionTest.Fixture
import kotlin.test.Test

/** Reuses real manager/validator activation assertions; only remote/storage/signature ports are fake. */
class IncrementalSourceCatalogDeclarationRetentionTest {
    @Test
    fun unsupported_declaration_in_an_advancing_catalog_retains_the_whole_catalog_and_floor() =
        runTest {
            val fixture =
                Fixture(
                    candidateChange = { source ->
                        val home = source.endpoints.getValue("home")
                        source.copy(
                            endpoints =
                                source.endpoints + ("home" to home.copy(url = "{baseUrl}/{unknownRuntimeVar}")),
                        )
                    },
                    expectedError =
                        "source 'ChangingSource': endpoints[home].url: [template.variable.unsupported] " +
                            "Template references a variable not supplied in this context.",
                )
            fixture.acceptBaseline()
            fixture.rejectAdvancingCandidate()
        }
}
