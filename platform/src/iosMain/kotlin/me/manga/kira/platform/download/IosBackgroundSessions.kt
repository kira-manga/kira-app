package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSBundle
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDelegateProtocol
import platform.Foundation.NSURLSessionTask
import kotlin.coroutines.resume

/** Serial, same-identifier session generations; a successor cannot overlap unfinished invalidation. */
@OptIn(ExperimentalForeignApi::class)
internal class IosBackgroundSessions(
    private val initialLifetime: IosNativeSessionLifetime,
    private val prepareStaging: () -> Unit,
    private val delegate: (IosNativeSessionLifetime) -> NSURLSessionDelegateProtocol,
) {
    private val mutex = Mutex()
    private var initial: Generation? = null
    private var current: Generation? = null
    private var attachmentFailure: Throwable? = null

    /** Once per process, independent of source/artifact readiness; an idle completed session stays closed. */
    suspend fun ensureReady(operation: DownloadOperationExclusion.Operation) {
        val (generation, access) = mutex.withLock {
            initial?.let { return@withLock it to null }
            createGeneration(operation).let { it.generation to it.access }
        }
        try {
            generation.lifetime.awaitCensus()
        } finally {
            access?.release()
        }
    }

    suspend fun <T> withSession(
        operation: DownloadOperationExclusion.Operation,
        block: (NSURLSession, IosNativeSessionLifetime) -> T,
    ): T {
        while (true) {
            when (val acquired = mutex.withLock { acquire(operation) }) {
                is Acquisition.Draining -> acquired.lifetime.awaitInvalidation()
                is Acquisition.Using -> try {
                    acquired.generation.lifetime.awaitCensus()
                    return block(acquired.generation.session, acquired.generation.lifetime)
                } finally {
                    acquired.access.release()
                }
            }
        }
    }

    /** Snapshot for cancellation/progress, never a quiescence check; does not await invalidation or ACK. */
    suspend fun tasks(operation: DownloadOperationExclusion.Operation): List<NSURLSessionTask> {
        ensureReady(operation)
        val acquired = mutex.withLock {
            val generation = checkNotNull(current)
            generation.lifetime.tryAccess()?.let { Acquisition.Using(generation, it) }
        } ?: return emptyList() // A closing/retired snapshot is informational, never a native/receiver drain receipt.
        return snapshot(acquired)
    }

    private suspend fun snapshot(acquired: Acquisition.Using): List<NSURLSessionTask> =
        suspendCancellableCoroutine { continuation ->
            try {
                acquired.generation.session.getAllTasksWithCompletionHandler { result ->
                    try {
                        val tasks = (result ?: emptyList<Any?>()).filterIsInstance<NSURLSessionTask>()
                        acquired.generation.lifetime.observeTasks(tasks)
                        continuation.resume(tasks)
                    } finally {
                        // A cancelled caller is not the native completion of this session access.
                        acquired.access.release()
                    }
                }
            } catch (failure: Throwable) {
                acquired.access.release()
                throw failure
            }
        }

    private fun acquire(operation: DownloadOperationExclusion.Operation): Acquisition {
        attachmentFailure?.let { throw it }
        val generation = current
        if (generation == null || generation.lifetime.isSettled()) return createGeneration(operation)
        val access = generation.lifetime.tryAccess() ?: return Acquisition.Draining(generation.lifetime)
        return Acquisition.Using(generation, access)
    }

    private fun createGeneration(operation: DownloadOperationExclusion.Operation): Acquisition.Using {
        attachmentFailure?.let { throw it }
        val lifetime = if (initial == null) initialLifetime else IosNativeSessionLifetime(operation.retain())
        val access = checkNotNull(lifetime.tryAccess())
        try {
            prepareStaging()
            val session = createSession(lifetime)
            lifetime.bindSession(session)
            val generation = Generation(session, lifetime)
            current = generation
            if (initial == null) initial = generation
            startCensus(generation)
            return Acquisition.Using(generation, access)
        } catch (failure: Throwable) {
            // A partially attached native session has unknown ownership. Never retry over it or release its root.
            attachmentFailure = failure
            access.release()
            throw failure
        }
    }

    private fun createSession(lifetime: IosNativeSessionLifetime): NSURLSession {
        val configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(SESSION_ID).apply {
            sessionSendsLaunchEvents = true
            discretionary = false
            HTTPMaximumConnectionsPerHost = MAX_CONNECTIONS_PER_HOST
            // Keep Apple's resource timeout; offline queued downloads must not acquire a new wall-clock budget.
        }
        BgDownloadLog.log("session.created", "maxPerHost" to MAX_CONNECTIONS_PER_HOST)
        return NSURLSession.sessionWithConfiguration(configuration, delegate = delegate(lifetime), delegateQueue = null)
    }

    private fun startCensus(generation: Generation) {
        generation.session.getAllTasksWithCompletionHandler { result ->
            val tasks = (result ?: emptyList<Any?>()).filterIsInstance<NSURLSessionTask>()
            generation.lifetime.finishCensus(tasks)
            // Register before requesting cancellation. Invalid/tokenless tasks still need real terminal callbacks.
            tasks.filter { IosTransferIdentity.decode(it.taskDescription) == null }.forEach { it.cancel() }
        }
    }

    private class Generation(val session: NSURLSession, val lifetime: IosNativeSessionLifetime)

    private sealed interface Acquisition {
        class Using(val generation: Generation, val access: IosNativeSessionLifetime.Access) : Acquisition
        class Draining(val lifetime: IosNativeSessionLifetime) : Acquisition
    }

    private companion object {
        val SESSION_ID = "${NSBundle.mainBundle.bundleIdentifier ?: "me.manga.kira.debug"}.download.transfers"
        const val MAX_CONNECTIONS_PER_HOST: Long = 4
    }
}
