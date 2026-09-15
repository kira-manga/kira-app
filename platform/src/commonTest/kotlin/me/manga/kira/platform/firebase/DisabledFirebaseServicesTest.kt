package me.manga.kira.platform.firebase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.remote.RemoteDocStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DisabledFirebaseServicesTest {
    @Test
    fun remoteReadsWritesAndObservationsAreExplicitFailures() = runTest {
        val store: RemoteDocStore = DisabledRemoteDocStore
        assertFailsWith<FirebaseServicesUnavailableException> { store.getDoc("test/document") }
        assertFailsWith<FirebaseServicesUnavailableException> { store.setDoc("test/document", emptyMap()) }
        assertFailsWith<FirebaseServicesUnavailableException> { store.deleteDoc("test/document") }
        assertFailsWith<FirebaseServicesUnavailableException> { store.query("test") }
        assertFailsWith<FirebaseServicesUnavailableException> { store.observeDoc("test/document").first() }
    }

    @Test
    fun disabledPushHasNoRemoteTokenAndTelemetryRequiresNoSdk() = runTest {
        assertNull(DisabledPushTokenProvider.getToken())
        assertNull(DisabledPushTokenProvider.observeTokens().first())
        DisabledPushTokenProvider.deleteToken()
        DisabledAnalyticsClient.logEvent("debug_event")
        DisabledCrashReporter.recordException(IllegalStateException("local only"))
    }
}
