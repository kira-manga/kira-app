package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.ui.diagnostics.CrashDiagnosticsScenario
import me.manga.kira.ui.diagnostics.CrashDiagnosticsScreen

/** Hosts the fatal-crash harness that is registered only in protected internal release builds. */
@Composable
fun CrashDiagnosticsScreenRoute(navController: NavController) {
    CrashDiagnosticsScreen(
        onBack = { navController.safePopBackStack() },
        onCrash = ::triggerCrashDiagnostic,
    )
}

internal fun triggerCrashDiagnostic(scenario: CrashDiagnosticsScenario): Nothing =
    throw crashForScenario(scenario)

internal fun crashForScenario(scenario: CrashDiagnosticsScenario): Throwable = when (scenario) {
    CrashDiagnosticsScenario.ILLEGAL_STATE ->
        IllegalStateException("Kira internal crash diagnostic: illegal-state")
    CrashDiagnosticsScenario.ILLEGAL_ARGUMENT ->
        IllegalArgumentException("Kira internal crash diagnostic: illegal-argument")
    CrashDiagnosticsScenario.OUT_OF_BOUNDS ->
        IndexOutOfBoundsException("Kira internal crash diagnostic: out-of-bounds")
    CrashDiagnosticsScenario.ARITHMETIC ->
        ArithmeticException("Kira internal crash diagnostic: arithmetic")
    CrashDiagnosticsScenario.CUSTOM_KIRA ->
        KiraInternalTestCrashException("Kira internal crash diagnostic: custom-kira")
}

private class KiraInternalTestCrashException(message: String) : RuntimeException(message)
