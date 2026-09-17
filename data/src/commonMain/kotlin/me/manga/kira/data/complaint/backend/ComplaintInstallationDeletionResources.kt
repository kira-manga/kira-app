package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.HttpClientEngine

/**
 * Explicit dormant composition input: a separate fixed-route engine plus a public UUIDv4 supplier.
 * The composition root retains engine ownership; constructing this value performs no request or allocation of a key.
 */
class ComplaintInstallationDeletionResources(
    val engine: HttpClientEngine,
    val nextKey: () -> String,
)

internal fun createInstallationDeletion(
    endpoint: ComplaintBackendEndpoint,
    coordinator: InstallationCredentialCoordinator,
    sessions: InstallationSessionManager,
    resources: ComplaintInstallationDeletionResources?,
    close: MutableList<() -> Unit>,
): BackendInstallationDeletionRepository? {
    if (resources == null) return null
    val http = InstallationDeletionHttp(endpoint, resources.engine).also { close.add(0, it::close) }
    val works = InstallationDeletionWorks().also { close.add(0, it::close) }
    return BackendInstallationDeletionRepository(coordinator, sessions, http, InstallationDeletionInputs(resources.nextKey), works)
}
