package me.manga.kira.presentation.common.componants.images

import coil3.network.NetworkFetcher
import coil3.network.ktor3.asNetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/** Keep the existing Ktor engine/timeouts; only tagged Reader requests count response bytes. */
actual fun platformNetworkFetcherFactory(): NetworkFetcher.Factory? {
    val client = HttpClient {
        // Parity with HttpClientFactory.ios.kt: 30s connect / 60s socket, and NO
        // requestTimeoutMillis — a slow-but-progressing tall manga page must not be aborted
        // mid-stream as long as each read stays under the socket timeout.
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }
    return NetworkFetcher.Factory(networkClient = { PageProgressNetworkClient(client.asNetworkClient()) })
}
