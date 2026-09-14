package me.manga.kira.presentation.common.componants.images

import coil3.network.NetworkFetcher
import coil3.network.ktor3.asNetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/** Keep the existing Ktor engine/timeouts; only tagged Reader requests count response bytes. */
actual fun platformNetworkFetcherFactory(): NetworkFetcher.Factory? {
    val client = HttpClient {
        // Parity with HttpClientFactory.desktop.kt: 30s connect / 60s socket, and NO
        // requestTimeoutMillis — leaving it unset overrides CIO's 15s whole-request default so a
        // slow-but-progressing tall manga page is not aborted mid-stream.
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }
    return NetworkFetcher.Factory(networkClient = { PageProgressNetworkClient(client.asNetworkClient()) })
}
