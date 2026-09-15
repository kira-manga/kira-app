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
import me.manga.kira.platform.media.PageImageMetadata
import me.manga.kira.platform.media.publishPageSnapshot
import me.manga.kira.platform.media.requireValid
import okio.FileSystem
import okio.IOException
import okio.Path

/** HTTP request and destination for one page. */
internal data class PageDownloadRequest(
    val url: String,
    val headers: Map<String, String>,
    val directory: Path,
    val pageIndex: Int,
)

/** Scoped Ktor streaming: no saved/full-body response, and no published path before validation. */
internal suspend fun downloadValidatedPage(
    client: HttpClient,
    request: PageDownloadRequest,
    system: FileSystem,
    inspector: PageMediaInspector,
    policy: PageBytePolicy,
    publish: suspend (Path, PageImageMetadata) -> Path = { temporary, metadata ->
        publishPageSnapshot(system, temporary, request.pageIndex, metadata)
    },
): Path {
    requireUncachedPageClient(client)
    require(request.pageIndex >= 0)
    currentCoroutineContext().ensureActive()
    system.createDirectories(request.directory)
    val temporary = pageTemporaryPath(request.directory, request.pageIndex)
    return client
        .prepareGet(request.url) {
            headers { request.headers.forEach { (name, value) -> append(name, value) } }
        }.execute { response ->
            if (!response.status.isSuccess()) throw PageDownloadHttpException(response.status.value)
            // transferPageBody only deletes a file it successfully created; a name collision is not ours.
            transferPageBody(
                response.bodyAsChannel(),
                response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                system,
                temporary,
                policy,
            )
            // Capture cancellation too: the owned temporary must be removed before propagating it.
            runCatching {
                currentCoroutineContext().ensureActive()
                val metadata = inspector.inspect(temporary).requireValid()
                currentCoroutineContext().ensureActive()
                publish(temporary, metadata)
            }.onFailure { failure ->
                runCatching { system.delete(temporary, mustExist = false) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }.getOrThrow()
        }
}

/** Keeps the existing IOException/message contract while retaining status for challenge recovery. */
internal class PageDownloadHttpException(
    override val httpStatusCode: Int,
) : IOException("Image download HTTP $httpStatusCode"), DownloadHttpStatusFailure
