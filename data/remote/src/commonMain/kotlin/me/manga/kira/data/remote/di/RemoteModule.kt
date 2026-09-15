package me.manga.kira.data.remote.di

import io.ktor.client.HttpClient
import me.manga.kira.core.cache.HttpCacheClearer
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.data.remote.ktor.createHttpClient
import me.manga.kira.data.remote.ktor.responseCacheClearer
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * Shared metadata `HttpClient`, its live/disk clear port, and `ApiClient` bindings.
 *
 * The separately qualified `chapter-download-http` singleton deliberately omits HttpCache before
 * page streaming begins and is closed by this Koin owner. The existing shared client lifetime is
 * unchanged. All platform factories supply the same cache policy and core clear-port contract.
 */
fun remoteModule(): Module =
    module {
        single { createHttpClient() }
        single<HttpCacheClearer> { get<HttpClient>().responseCacheClearer() }
        single<HttpClient>(named("chapter-download-http")) {
            createHttpClient(cacheResponses = false)
        } onClose { it?.close() }
        single { ApiClient(get()) }
    }
