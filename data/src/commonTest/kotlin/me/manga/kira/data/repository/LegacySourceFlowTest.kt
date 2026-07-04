package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.states.State as LegacyState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression guard for the "failed to load" / "something went wrong" root cause.
 *
 * The legacy per-source flows (`sources_repositry/.../common/BaseManga.fetchDataWithHeaders`) emit
 * their terminal `State` from INSIDE a `try { … emit(Success) … } catch (e: Exception) { emit(Error) }`.
 * Collecting such a flow with a *cancelling* terminal operator (`.first { it !is Loading }`) makes the
 * abort throw out of `emit`, get swallowed by the legacy `catch`, and re-`emit` — which trips kotlinx's
 * exception-transparency guard and surfaces as a bogus failure on EVERY successful fetch.
 * [awaitTerminalState] consumes the whole flow (non-cancelling) instead, so the legacy `catch` is only
 * ever entered by a genuine exception.
 *
 * These tests model the legacy flow shape exactly (the existing `HomeSearchDataTest` fakes used a clean
 * single-`emit` flow, which is precisely why the bug shipped undetected).
 */
class LegacySourceFlowTest {

    /** Replicates `fetchDataWithHeaders`: Loading, then the terminal emitted from within a try/catch(Exception). */
    private fun legacyShapedFlow(terminal: LegacyState<List<String>>) = flow {
        emit(LegacyState.Loading)
        try {
            emit(terminal)
        } catch (e: Exception) {
            // The legacy code swallows the downstream abort here and re-emits — the transparency hazard.
            emit(LegacyState.Error(code = 0, message = e.message ?: "Unknown error occurred"))
        }
    }

    @Test
    fun awaitTerminalState_returnsSuccess_overLegacyEmitFromTryCatchShape() = runTest {
        val data = listOf("a", "b", "c")
        val result = legacyShapedFlow(LegacyState.Success(data)).awaitTerminalState()
        assertTrue(result is LegacyState.Success, "expected Success, got $result")
        assertEquals(data, (result as LegacyState.Success).data)
    }

    @Test
    fun awaitTerminalState_preservesError_overLegacyEmitFromTryCatchShape() = runTest {
        val result = legacyShapedFlow(LegacyState.Error(code = 503, message = "Service Unavailable")).awaitTerminalState()
        assertTrue(result is LegacyState.Error, "expected Error, got $result")
        assertEquals(503, (result as LegacyState.Error).code)
    }

    @Test
    fun awaitTerminalState_synthesizesError_whenFlowCompletesWithoutTerminal() = runTest {
        val result = flow<LegacyState<List<String>>> { emit(LegacyState.Loading) }.awaitTerminalState()
        assertTrue(result is LegacyState.Error, "a terminal-less flow must not be reported as success")
    }

    /**
     * Demonstrates the defect the helper fixes: the OLD cancelling `.first { it !is Loading }` over the
     * legacy emit-from-try/catch shape throws a Flow exception-transparency violation instead of
     * returning the `Success` — proving the regression is real and that [awaitTerminalState] is required.
     */
    @Test
    fun cancellingFirst_overLegacyShape_tripsTransparencyViolation() = runTest {
        try {
            legacyShapedFlow(LegacyState.Success(listOf("ok"))).first { it !is LegacyState.Loading }
            fail("expected the cancelling .first { } to trip Flow exception transparency over the legacy shape")
        } catch (e: IllegalStateException) {
            assertTrue(
                e.message?.contains("transparency", ignoreCase = true) == true,
                "expected a Flow-exception-transparency IllegalStateException, got: ${e.message}",
            )
        }
    }
}
