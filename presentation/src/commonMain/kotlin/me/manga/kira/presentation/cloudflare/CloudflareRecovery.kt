package me.manga.kira.presentation.cloudflare

import me.manga.kira.core.error.AppError
import kotlin.random.Random

/** One outstanding solver return per ViewModel; tickets never migrate between operations/owners. */
internal class CloudflareRecovery<Operation : Any> {
    private val instanceId = Random.nextLong()
    private var sequence = 0L
    private var pending: Request<Operation>? = null

    val requestId: String? get() = pending?.id
    val operation: Operation? get() = pending?.operation

    fun request(
        operation: Operation,
        acquireBudget: () -> Boolean,
    ): Boolean {
        if (pending != null || !acquireBudget()) return false
        pending = Request("$instanceId:${++sequence}", operation)
        return true
    }

    fun consume(requestId: String): Operation? {
        val request = pending?.takeIf { it.id == requestId } ?: return null
        pending = null
        return request.operation
    }

    fun invalidate(operation: Operation) {
        if (pending?.operation == operation) clear()
    }

    fun clear() {
        pending = null
    }

    private data class Request<Operation>(
        val id: String,
        val operation: Operation,
    )
}

/** A single current operation, not a URL history. Unrelated successes never call [recovered]. */
internal class CloudflareRecoveryBudget<Key : Any> {
    private var key: Key? = null
    private var attempts = 0

    fun acquire(operation: Key): Boolean {
        if (key != operation) {
            key = operation
            attempts = 0
        }
        if (attempts == MAX_CLOUDFLARE_ATTEMPTS) return false
        attempts++
        return true
    }

    fun recovered(operation: Key) {
        if (key == operation) clear()
    }

    fun clear() {
        key = null
        attempts = 0
    }
}

internal fun AppError.isCloudflareChallenge(): Boolean =
    this is AppError.Network.Http && statusCode in CHALLENGE_STATUSES

private const val MAX_CLOUDFLARE_ATTEMPTS = 2
private val CHALLENGE_STATUSES = setOf(403, 429, 503, 520, 521, 522, 523, 524)
