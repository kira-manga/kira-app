package me.manga.kira.sources.runtime

import me.manga.kira.sources.contracts.HeaderStore
import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.SourceRequest
import me.manga.kira.sources.contracts.SourceResponse

/**
 * Shared in-memory I/O fakes for the pilot parity tests in this source set — the engine's own test
 * fakes aren't visible across modules, so the pilots reuse these instead of re-declaring a copy each.
 * [MapFakeHttp] returns 200 with the body keyed by request URL (404 otherwise); [NoopHeaderStore]
 * returns a fixed header map and ignores writes.
 */
class MapFakeHttp(private val responses: Map<String, String>) : HttpExecutor {
    override suspend fun execute(request: SourceRequest): SourceResponse =
        responses[request.url]?.let { SourceResponse(200, it) } ?: SourceResponse(404, "missing: ${request.url}")
}

class NoopHeaderStore(private val headers: Map<String, String> = emptyMap()) : HeaderStore {
    override suspend fun headersFor(api: String): Map<String, String> = headers
    override suspend fun save(api: String, headers: Map<String, String>) = Unit
}
