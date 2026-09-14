package me.manga.kira.presentation.features.download.domain.clean

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.publishPageSnapshot
import me.manga.kira.platform.media.requireValid
import okio.FileSystem
import okio.IOException
import okio.Path

/** Scoped Ktor streaming: no saved/full-body response, and no published path before validation. */
internal suspend fun downloadValidatedPage(
    client: HttpClient,
    url: String,
    pageHeaders: Map<String, String>,
    system: FileSystem,
    directory: Path,
    pageIndex: Int,
    inspector: PageMediaInspector,
    policy: PageBytePolicy,
): Path {
    requireUncachedPageClient(client)
    require(pageIndex >= 0)
    currentCoroutineContext().ensureActive()
    system.createDirectories(directory)
    val temporary = pageTemporaryPath(directory, pageIndex)
    return client
        .prepareGet(url) {
            headers { pageHeaders.forEach { (name, value) -> append(name, value) } }
        }.execute { response ->
            if (!response.status.isSuccess()) throw IOException("Image download HTTP ${response.status.value}")
            // transferPageBody only deletes a file it successfully created; a name collision is not ours.
            transferPageBody(
                response.bodyAsChannel(),
                response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                system,
                temporary,
                policy,
            )
            try {
                currentCoroutineContext().ensureActive()
                val metadata = inspector.inspect(temporary).requireValid()
                currentCoroutineContext().ensureActive()
                publishPageSnapshot(system, temporary, pageIndex, metadata)
            } catch (failure: Throwable) {
                try {
                    system.delete(temporary, mustExist = false)
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }
}
