package me.manga.kira.platform.firebase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import me.manga.kira.platform.analytics.AnalyticsClient
import me.manga.kira.platform.crash.CrashReporter
import me.manga.kira.platform.push.PushTokenProvider
import me.manga.kira.platform.remote.RemoteDocStore
import me.manga.kira.platform.remote.RemoteQuery

/** Typed failure for a Firebase-backed operation that this installation intentionally cannot perform. */
class FirebaseServicesUnavailableException : IllegalStateException("Firebase-backed services are disabled in this build")

/** Disabled telemetry has no SDK instance, persistence or network side effects. */
object DisabledAnalyticsClient : AnalyticsClient {
    override fun logEvent(name: String, params: Map<String, Any?>) = Unit
    override fun setUserProperty(key: String, value: String?) = Unit
    override fun setUserId(id: String?) = Unit
}

/** Keep normal platform logging/crash handling; never construct a production crash-reporting SDK. */
object DisabledCrashReporter : CrashReporter {
    override fun recordException(throwable: Throwable, context: Map<String, String>) = Unit
    override fun log(message: String) = Unit
    override fun setUserId(id: String?) = Unit
    override fun setCustomKey(key: String, value: String) = Unit
}

/** An unprovisioned installation has no remote token; local notifications remain independent. */
object DisabledPushTokenProvider : PushTokenProvider {
    override suspend fun getToken(): String? = null
    override suspend fun deleteToken() = Unit
    override fun observeTokens(): Flow<String?> = flowOf(null)
}

/** Unavailable remote data is a failure, not an empty read or a successful discarded write. */
object DisabledRemoteDocStore : RemoteDocStore {
    override suspend fun getDoc(path: String): Nothing = throw FirebaseServicesUnavailableException()
    override suspend fun setDoc(path: String, data: Map<String, Any?>): Nothing = throw FirebaseServicesUnavailableException()
    override suspend fun deleteDoc(path: String): Nothing = throw FirebaseServicesUnavailableException()
    override fun observeDoc(path: String): Flow<Map<String, Any?>?> = flow { throw FirebaseServicesUnavailableException() }
    override suspend fun query(collectionPath: String, where: List<RemoteQuery>, limit: Int?): Nothing =
        throw FirebaseServicesUnavailableException()
}
