package me.manga.kira.presentation.mvi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the [MviViewModel.submit] exception safety net (audit finding
 * `mvi-submit-no-exception-safety-net`): a throw out of [MviViewModel.handle] must NOT escape
 * `viewModelScope` and crash the process — it routes to [MviViewModel.onUnhandledError] — while
 * the VM stays usable, and `CancellationException` is still propagated.
 */
class MviViewModelSafetyNetTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private data class S(val n: Int = 0) : MviState

    private sealed interface I : MviIntent {
        data object Boom : I
        data object Cancel : I
        data object Ok : I
    }

    private sealed interface E : MviEffect

    private class Vm : MviViewModel<S, I, E>(S()) {
        var caught: Throwable? = null
            private set

        override suspend fun handle(intent: I) {
            when (intent) {
                I.Boom -> throw IllegalStateException("boom")
                I.Cancel -> throw CancellationException("cancel")
                I.Ok -> updateState { it.copy(n = it.n + 1) }
            }
        }

        override fun onUnhandledError(throwable: Throwable, intent: I?) {
            caught = throwable
        }

        // Exposes the protected safety-net launcher (same catch structure as submit) so a test can
        // observe whether a CancellationException out of the block actually propagates (the returned
        // Job ends cancelled) rather than being silently swallowed.
        fun launchThrowing(block: suspend CoroutineScope.() -> Unit): Job = launchSafely(block)
    }

    @Test
    fun throwingHandler_doesNotCrash_routesToHook_andVmStaysUsable() = runTest {
        val vm = Vm()

        // A reducer that throws must not propagate out of submit / crash the app.
        vm.submit(I.Boom)
        assertTrue(vm.caught is IllegalStateException, "unhandled error routed to onUnhandledError")
        assertEquals(0, vm.state.value.n, "failed intent did not corrupt state")

        // The VM keeps working after a swallowed reducer error.
        vm.submit(I.Ok)
        assertEquals(1, vm.state.value.n, "VM still processes intents after a swallowed error")
    }

    @Test
    fun cancellationException_doesNotRouteToHook_andVmStaysUsable() = runTest {
        val vm = Vm()
        // A CancellationException out of handle() must NOT be routed to onUnhandledError like a real
        // error, and the VM must remain usable. (Propagation itself is pinned separately, below —
        // these two assertions alone would also hold if submit() silently swallowed the CE.)
        vm.submit(I.Cancel)
        assertNull(vm.caught, "CancellationException must not be routed to onUnhandledError")
        // VM remains usable.
        vm.submit(I.Ok)
        assertEquals(1, vm.state.value.n)
    }

    @Test
    fun cancellationException_propagates_jobEndsCancelled() = runTest {
        val vm = Vm()
        // A CancellationException out of the safety-net launcher (same catch structure as submit())
        // must PROPAGATE so cooperative cancellation / VM-clear works — i.e. the launched Job ends
        // cancelled rather than completing normally. If the `catch (CancellationException) { throw e }`
        // rethrow were regressed to a silent swallow, the block would complete normally and this
        // assertion would fail.
        val job = vm.launchThrowing { throw CancellationException("cancel") }
        assertTrue(job.isCancelled, "CancellationException must propagate (Job ends cancelled, not completed)")
        assertNull(vm.caught, "CancellationException must not be routed to onUnhandledError")
    }
}
