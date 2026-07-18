package me.manga.kira.navigation.routes

import me.manga.kira.ui.diagnostics.CrashDiagnosticsScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class CrashDiagnosticsScreenRouteTest {
    @Test
    fun five_scenarios_create_five_distinct_fatal_types() {
        val failures = CrashDiagnosticsScenario.entries.map(::crashForScenario)

        assertEquals(5, failures.size)
        assertEquals(5, failures.map { it::class }.toSet().size)
        assertEquals(5, failures.mapNotNull(Throwable::message).toSet().size)
        failures.forEach { failure ->
            assertTrue(failure.message.orEmpty().startsWith("Kira internal crash diagnostic:"))
        }
    }

    @Test
    fun trigger_throws_the_selected_failure_instead_of_recording_a_non_fatal() {
        CrashDiagnosticsScenario.entries.forEach { scenario ->
            val thrown = assertFails { triggerCrashDiagnostic(scenario) }
            assertEquals(crashForScenario(scenario)::class, thrown::class)
            assertEquals(crashForScenario(scenario).message, thrown.message)
        }
    }
}
