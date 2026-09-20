package me.manga.kira.di

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.onClose
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Actual shipping request/history aliases; mobile moderation never resolves a backend or legacy VM. */
internal enum class ComplaintBackendEntrypoint {
    SETTINGS, LANGUAGE, SOURCES, REPOSITORY_SETTINGS,
    COMPLAINT, COMPLAINT_REWORK, COMPLAINT_ADMIN, COMPLAINT_ADMIN_REWORK,
}

/**
 * One application-owned selection and isolated Koin application, created by the actual platform
 * module at startup. Route owners borrow it; only global-Koin teardown retires and closes it.
 * Closing cancels owned work but is not proof of synchronous native-child drain.
 */
@OptIn(ExperimentalAtomicApi::class)
class ComplaintBackendHostOwner private constructor(initial: AppResult<ComplaintBackendGraph>) {
    private val connection = connectComplaintBackend(initial)
    private val closed = AtomicBoolean(false)
    private val current = MutableStateFlow<AppResult<Koin>>(
        when (connection) {
            is AppResult.Success -> AppResult.Success(connection.value.application.koin)
            is AppResult.Failure -> connection
        },
    )
    internal val selection = current.asStateFlow()

    /** Uses the captured selection only; neither an old dispatch nor a retired owner can retarget. */
    internal fun candidate(
        entrypoint: ComplaintBackendEntrypoint,
        observed: AppResult<Koin> = current.value,
    ): AppResult<Koin> =
        if (closed.load() || observed !== current.value || entrypoint == ComplaintBackendEntrypoint.COMPLAINT_ADMIN ||
            entrypoint == ComplaintBackendEntrypoint.COMPLAINT_ADMIN_REWORK
        ) {
            backendGraphUnavailable()
        } else {
            observed
        }

    /** Retires opening access before isolated application teardown; repeats do no additional work. */
    fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        val owned = (connection as? AppResult.Success)?.value
        owned?.graph?.retireAccess()
        current.value = backendGraphUnavailable()
        if (owned != null) {
            check(closeComplaintBackendConnection(owned.graph, owned.application)) { "Complaint backend host close failed" }
        }
    }

    internal companion object {
        fun create(selected: AppResult<ComplaintBackendGraph>): ComplaintBackendHostOwner = ComplaintBackendHostOwner(selected)
    }
}

/** The single startup registration used by all three platform modules and the connected fixture. */
internal fun Module.complaintBackendHost(select: Scope.() -> AppResult<ComplaintBackendGraph>) {
    single(createdAtStart = true) { ComplaintBackendHostOwner.create(select()) } onClose { it?.close() }
}

private class ComplaintBackendConnection(val graph: ComplaintBackendGraph, val application: KoinApplication)

/** Retain the application before loading/eager creation, so partial registration cannot leak owners. */
@Suppress("TooGenericExceptionCaught")
private fun connectComplaintBackend(initial: AppResult<ComplaintBackendGraph>): AppResult<ComplaintBackendConnection> {
    if (initial is AppResult.Failure) return initial
    val graph = (initial as AppResult.Success).value
    var application: KoinApplication? = null
    return try {
        val app = KoinApplication.init().also { application = it }
        app.modules(graph.module())
        app.createEagerInstances()
        AppResult.Success(ComplaintBackendConnection(graph, app))
    } catch (cancelled: CancellationException) {
        closeComplaintBackendConnection(graph, application)
        throw cancelled
    } catch (_: Exception) {
        if (closeComplaintBackendConnection(graph, application)) backendGraphUnavailable()
        else AppResult.Failure(AppError.Unexpected("complaint_backend_cleanup_failed"))
    } catch (failure: Throwable) {
        closeComplaintBackendConnection(graph, application)
        throw failure
    }
}

/** The graph fallback covers incomplete eager registration and is once-only at the graph itself. */
private fun closeComplaintBackendConnection(graph: ComplaintBackendGraph, application: KoinApplication?): Boolean {
    graph.retireAccess()
    var success = true
    try {
        application?.close()
    } catch (_: Throwable) {
        success = false
    }
    try {
        graph.close()
    } catch (_: Throwable) {
        success = false
    }
    return success
}
