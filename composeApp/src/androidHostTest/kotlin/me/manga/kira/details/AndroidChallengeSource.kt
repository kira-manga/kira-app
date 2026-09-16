package me.manga.kira.details

import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.SourceRequest
import me.manga.kira.sources.contracts.SourceResponse
import me.manga.kira.sources.contracts.model.EndpointSpec
import me.manga.kira.sources.contracts.model.FieldSpec
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import me.manga.kira.sources.engine.GenericSourceClient
import me.manga.kira.sources.runtime.DataStoreHeaderStore
import me.manga.kira.sources.runtime.DefaultSourceRegistry
import me.manga.kira.sources.runtime.RegistryChapterPageProvider
import me.manga.kira.sources.runtime.StaticSourceUpdateManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/** Only the source response is controlled; production engine/header store/page mapping stay live. */
internal class AndroidChallengeSource(
    api: String,
    private val chapterUrl: String,
    headers: DataStoreHeaderStore,
) : HttpExecutor {
    val requests = CopyOnWriteArrayList<SourceRequest>()
    private val config = SourceConfig(
        api = api,
        language = "en",
        baseUrl = "https://example.test",
        engine = "generic",
        usesCapturedHeaders = true,
        endpoints = mapOf("pages" to EndpointSpec("{chapterUrl}", format = "json", root = "pages")),
        fields = mapOf("page.image" to FieldSpec(path = "url")),
    )
    val provider = RegistryChapterPageProvider(
        DefaultSourceRegistry(
            updateManager = StaticSourceUpdateManager(SourceConfigDocument(schemaVersion = 1, sources = listOf(config))),
            genericClientFactory = { GenericSourceClient(it, this, headers) },
        ),
    )

    override suspend fun execute(request: SourceRequest): SourceResponse {
        assertEquals(chapterUrl, request.url)
        requests += request
        return SourceResponse(200, """{"pages":[{"url":"https://example.test/page.png"}]}""")
    }
}

internal fun capturedHeaders(value: String): Map<String, String> =
    mapOf("Cookie" to "cf_clearance=$value", "User-Agent" to "fixture-$value")
