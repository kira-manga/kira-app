package me.manga.kira.di

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.manga.kira.navigation.sourceaccess.SourceActivationRequestRouter
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the shipping bridge, not SwiftUI event delivery (which still needs device checks). */
@OptIn(ExperimentalCoroutinesApi::class)
class IosSourceActivationBridgeTest {
    @Test
    fun coldOpenRemainsPendingUntilNavigationAttaches() = runTest {
        withRouter { router ->
            assertTrue(onSourceActivationLink("kiramanga://activate"))
            assertTrue(router.pending.value)

            var requests = 0
            collectRequests(router) { requests++ }
            runCurrent()

            assertEquals(1, requests)
            assertFalse(router.pending.value)
        }
    }

    @Test
    fun warmOpenReachesAnAlreadyAttachedConsumerOnce() = runTest {
        withRouter { router ->
            var requests = 0
            collectRequests(router) { requests++ }
            runCurrent()
            assertEquals(0, requests)

            assertTrue(onSourceActivationLink("kiramanga://activate"))
            runCurrent()

            assertEquals(1, requests)
            assertFalse(router.pending.value)
        }
    }

    @Test
    fun universalLinkPathsUseTheSameValidatedRouter() {
        withRouter { router ->
            for (link in listOf("https://kiramanga.me/activate", "https://kiramanga.me/activate/")) {
                assertTrue(onSourceActivationLink(link))
                assertTrue(router.pending.value)
                router.consume()
                assertFalse(router.pending.value)
            }
        }
    }

    @Test
    fun linksWithoutActivationMarkerDoNotEnqueueOrDiscardAnExistingRequest() {
        withRouter { router ->
            val rejected = listOf("", "not a URL", "https://example.com/activate")
            rejected.forEach { assertFalse(onSourceActivationLink(it)) }
            assertFalse(router.pending.value)

            assertTrue(onSourceActivationLink("kiramanga://activate"))
            rejected.forEach { assertFalse(onSourceActivationLink(it)) }
            assertTrue(router.pending.value)
        }
    }

    @Test
    fun duplicateDeliveriesBeforeConsumptionCoalesce() = runTest {
        withRouter { router ->
            repeat(2) { assertTrue(onSourceActivationLink("kiramanga://activate")) }
            assertTrue(onSourceActivationLink("https://kiramanga.me/activate"))

            var requests = 0
            collectRequests(router) { requests++ }
            runCurrent()

            assertEquals(1, requests)
            assertFalse(router.pending.value)
        }
    }

    @Test
    fun consumedRequestDoesNotReplayAfterReattachmentButANewOpenStillWorks() = runTest {
        withRouter { router ->
            var requests = 0
            val firstConsumer = collectRequests(router) { requests++ }
            runCurrent()
            assertTrue(onSourceActivationLink("kiramanga://activate"))
            runCurrent()
            firstConsumer.cancel()

            val reconnectedConsumer = collectRequests(router) { requests++ }
            runCurrent()
            assertEquals(1, requests)
            reconnectedConsumer.cancel()
            collectRequests(router) { requests++ }
            runCurrent()
            assertEquals(1, requests)

            assertTrue(onSourceActivationLink("kiramanga://activate"))
            runCurrent()
            assertEquals(2, requests)
            assertFalse(router.pending.value)
        }
    }

    private fun TestScope.collectRequests(
        router: SourceActivationRequestRouter,
        onRequest: () -> Unit,
    ): Job = backgroundScope.launch {
        router.pending.collect { pending ->
            if (pending) {
                onRequest()
                router.consume()
            }
        }
    }

    private inline fun withRouter(block: (SourceActivationRequestRouter) -> Unit) {
        val router = SourceActivationRequestRouter()
        // Only the real router is registered: no Firebase, persistence, downloads or app bootstrap.
        startKoin { modules(module { single { router } }) }
        try {
            block(router)
        } finally {
            stopKoin()
        }
    }
}
