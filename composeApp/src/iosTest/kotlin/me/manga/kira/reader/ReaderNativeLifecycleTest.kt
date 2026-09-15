package me.manga.kira.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Production attachment/gate tests; no UIKit host and no claim about OS callback delivery. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderNativeLifecycleTest {
    private fun lifecycle(scope: CoroutineScope, calls: MutableList<String>): ReaderNativeLifecycle =
        ReaderNativeLifecycle(
            scope = scope,
            onResumed = { calls += "begin" },
            onPaused = { calls += "end" },
        )

    @Test
    fun appearBackgroundReturnDisappear_bracketsTwoForegroundSpans() = runTest {
        val calls = mutableListOf<String>()
        val owner = lifecycle(backgroundScope, calls)
        val renderer = owner.attach()
        renderer.onSceneActiveChanged(true)
        renderer.onVisibilityChanged(true)
        renderer.onSceneActiveChanged(false) // willDeactivate
        renderer.onSceneActiveChanged(false) // didEnterBackground, without VC disappearance
        renderer.onSceneActiveChanged(true)
        renderer.onVisibilityChanged(false)
        owner.close()
        assertEquals(listOf("begin", "end", "begin", "end"), calls)
    }

    @Test
    fun inactiveAppearance_waitsForSceneActivation() = runTest {
        val calls = mutableListOf<String>()
        val owner = lifecycle(backgroundScope, calls)
        val renderer = owner.attach()
        renderer.onVisibilityChanged(true)
        renderer.onSceneActiveChanged(false)
        assertEquals(emptyList(), calls)
        renderer.onSceneActiveChanged(true)
        assertEquals(listOf("begin"), calls)
        owner.close()
    }

    @Test
    fun hiddenReader_doesNotRestartWhenSceneReactivates() = runTest {
        val calls = mutableListOf<String>()
        val owner = lifecycle(backgroundScope, calls)
        val renderer = owner.attach()
        renderer.onSceneActiveChanged(true)
        assertEquals(emptyList(), calls)
        renderer.onVisibilityChanged(true)
        renderer.onVisibilityChanged(false)
        renderer.onSceneActiveChanged(false)
        renderer.onSceneActiveChanged(true)
        assertEquals(listOf("begin", "end"), calls)
        renderer.onVisibilityChanged(true)
        owner.close()
        assertEquals(listOf("begin", "end", "begin", "end"), calls)
    }

    @Test
    fun duplicateConfirmedVisibilityAndActivity_doNotResetOrEndTwice() = runTest {
        val calls = mutableListOf<String>()
        val owner = lifecycle(backgroundScope, calls)
        val renderer = owner.attach()
        renderer.onSceneActiveChanged(true)
        renderer.onVisibilityChanged(true)
        // Will-appear/disappear are deliberately not inputs; a cancelled exit may repeat didAppear.
        renderer.onVisibilityChanged(true)
        renderer.onSceneActiveChanged(true)
        assertEquals(listOf("begin"), calls)
        renderer.onVisibilityChanged(false)
        renderer.onVisibilityChanged(false)
        owner.close()
        assertEquals(listOf("begin", "end"), calls)
    }

    @Test
    fun repeatedAppearanceWhileInactive_doesNotOverrideInactiveEdge() = runTest {
        val calls = mutableListOf<String>()
        val owner = lifecycle(backgroundScope, calls)
        val renderer = owner.attach()
        renderer.onSceneActiveChanged(true)
        renderer.onVisibilityChanged(true)
        renderer.onSceneActiveChanged(false)
        renderer.onVisibilityChanged(true)
        assertEquals(listOf("begin", "end"), calls)
        owner.close()
    }

    @Test
    fun backgroundThenDisposal_endsBeforeDisposalAndOnlyOnce() = runTest {
        val calls = mutableListOf<String>()
        val owner = lifecycle(backgroundScope, calls)
        val renderer = owner.attach()
        renderer.onSceneActiveChanged(true)
        renderer.onVisibilityChanged(true)
        renderer.onSceneActiveChanged(false)
        assertEquals(listOf("begin", "end"), calls, "the foreground span is ended before suspension/disposal")
        owner.close()
        owner.close()
        renderer.detach()
        assertEquals(listOf("begin", "end"), calls)
        assertFalse(renderer.scope.isActive)
    }

    @Test
    fun activeDisposal_dispatchesPauseBeforeCancellingCollectors() = runTest {
        lateinit var renderer: ReaderNativeAttachment
        var collectorsActiveAtPause = false
        val owner =
            ReaderNativeLifecycle(
                scope = backgroundScope,
                onResumed = {},
                onPaused = { collectorsActiveAtPause = renderer.scope.isActive },
            )
        renderer = owner.attach()
        renderer.onSceneActiveChanged(true)
        renderer.onVisibilityChanged(true)
        owner.close()
        assertTrue(collectorsActiveAtPause)
        assertFalse(renderer.scope.isActive)
        assertTrue(backgroundScope.isActive, "closing a renderer must not cancel its destination's parent scope")
    }

    @Test
    fun replacingRenderer_revokesOldEventsAndOnlyCancelsOldCollectors() = runTest {
        val calls = mutableListOf<String>()
        val owner = lifecycle(backgroundScope, calls)
        val old = owner.attach()
        val oldCollector = old.scope.launch { awaitCancellation() }
        old.onSceneActiveChanged(true)
        old.onVisibilityChanged(true)
        runCurrent()

        val replacement = owner.attach()
        val replacementCollector = replacement.scope.launch { awaitCancellation() }
        replacement.onSceneActiveChanged(true)
        replacement.onVisibilityChanged(true)
        runCurrent()
        assertTrue(oldCollector.isCancelled)
        assertTrue(replacementCollector.isActive)
        assertEquals(listOf("begin", "end", "begin"), calls)

        old.onVisibilityChanged(false)
        old.onSceneActiveChanged(false)
        old.detach() // delayed deinit must not close the replacement's collectors or foreground span
        old.onVisibilityChanged(true)
        old.onSceneActiveChanged(true)
        runCurrent()
        assertTrue(replacementCollector.isActive)
        assertEquals(listOf("begin", "end", "begin"), calls)

        owner.close()
        runCurrent()
        assertTrue(replacementCollector.isCancelled)
        assertEquals(listOf("begin", "end", "begin", "end"), calls)
    }

    @Test
    fun rendererDetachAndDestinationClose_leaveSessionRestartable() = runTest {
        val calls = mutableListOf<String>()
        val owner = lifecycle(backgroundScope, calls)
        val old = owner.attach()
        old.onSceneActiveChanged(true)
        old.onVisibilityChanged(true)
        old.detach() // temporary native unmount for WebView

        val returned = owner.attach()
        returned.onSceneActiveChanged(true)
        returned.onVisibilityChanged(true)
        old.detach()
        assertTrue(returned.scope.isActive)
        owner.close()

        val restarted = owner.attach() // close is restartable, only individual leases are terminal
        restarted.onSceneActiveChanged(true)
        restarted.onVisibilityChanged(true)
        returned.detach()
        assertTrue(restarted.scope.isActive)
        owner.close()
        assertEquals(List(3) { listOf("begin", "end") }.flatten(), calls)
    }

    @Test
    fun disposalBeforeAppearance_cannotBeReopenedByLateCallbacks() = runTest {
        val calls = mutableListOf<String>()
        val owner = lifecycle(backgroundScope, calls)
        val renderer = owner.attach()
        owner.close()
        renderer.onSceneActiveChanged(true)
        renderer.onVisibilityChanged(true)
        renderer.detach()
        assertEquals(emptyList(), calls)
        assertFalse(renderer.scope.isActive)
    }
}
