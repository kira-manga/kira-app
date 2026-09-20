package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setHTTPShouldHandleCookies

/**
 * One response slot matches the selected installation/history coordinator's serialized contract.
 * Every nonempty NSData is counted before forwarding into Ktor's unlimited channel.
 * The lock covers forwarding and close; fixed taskDescription terminal markers prevent late
 * callbacks resurrecting removed accounting without retaining an unbounded tombstone map.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal class IosComplaintSessionReceiveGuard(
    private val policy: IosComplaintInstallationPolicy,
    private val callbacks: IosComplaintSessionCallbacks,
) {
    constructor(
        target: ComplaintSessionTarget,
        callbacks: IosComplaintSessionCallbacks,
    ) : this(IosComplaintInstallationPolicy.Session(target), callbacks)

    private val lock = NSRecursiveLock()
    private var closed = false
    private var receiving: Receiving? = null

    internal val activeTaskCount: Int get() = locked { if (receiving == null) 0 else 1 }

    fun prepare(request: NSMutableURLRequest) =
        locked {
            check(!closed && policy.acceptsRequest(request)) { "Session request refused" }
            request.setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            request.setHTTPShouldHandleCookies(false)
        }

    fun admit(
        session: NSURLSession,
        task: NSURLSessionDataTask,
        response: NSURLResponse,
    ): Boolean =
        locked {
            val native = response as? NSHTTPURLResponse
            val budget = native?.let(policy::receiveBudget)
            val allowed =
                !closed &&
                    task.taskDescription != TERMINAL &&
                    receiving == null &&
                    budget != null &&
                    native?.let { policy.acceptsResponse(task, it) } == true
            if (allowed && budget != null) {
                receiving = Receiving(task, budget)
            } else {
                reject(session, task)
            }
            allowed
        }

    fun receive(
        session: NSURLSession,
        task: NSURLSessionDataTask,
        data: NSData,
    ) = locked {
        val budget = receiving?.takeIf { it.task.taskIdentifier == task.taskIdentifier }?.budget
        if (!closed && task.taskDescription != TERMINAL && budget?.accept(data.length) == true) {
            // Zero-byte callback objects must not fill an otherwise byte-bounded queue.
            if (data.length != 0uL) callbacks.received(session, task, data)
        } else {
            reject(session, task)
        }
    }

    fun complete(
        session: NSURLSession,
        task: NSURLSessionTask,
        error: NSError?,
    ) = locked {
        if (task.taskDescription == TERMINAL) return@locked
        val budget = receiving?.takeIf { it.task.taskIdentifier == task.taskIdentifier }?.budget
        val failure = error ?: if (closed || budget?.isComplete() != true) policyError() else null
        if (budget != null) receiving = null
        task.taskDescription = TERMINAL
        callbacks.completed(session, task, failure)
    }

    fun close(session: NSURLSession) =
        locked {
            closed = true
            receiving?.task?.let { reject(session, it) }
        }

    private fun reject(
        session: NSURLSession,
        task: NSURLSessionTask,
    ) {
        if (task.taskDescription == TERMINAL) return
        if (receiving?.task?.taskIdentifier == task.taskIdentifier) receiving = null
        task.taskDescription = TERMINAL
        try {
            // Remove Ktor's handler with a sticky error NOW, not a later possibly-nil completion.
            callbacks.completed(session, task, policyError())
        } finally {
            task.cancel()
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private class Receiving(
        val task: NSURLSessionTask,
        val budget: ComplaintReceiveBudget,
    )

    private companion object {
        const val TERMINAL = "kira-session-terminal"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun policyError(): NSError = NSError(domain = "me.manga.kira.complaint.session", code = 1, userInfo = null)

@OptIn(ExperimentalForeignApi::class)
internal interface IosComplaintSessionCallbacks {
    fun received(
        session: NSURLSession,
        task: NSURLSessionDataTask,
        data: NSData,
    )

    fun completed(
        session: NSURLSession,
        task: NSURLSessionTask,
        error: NSError?,
    )
}
