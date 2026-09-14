package me.manga.kira.data.remote.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.data.remote.ktor.cache.ManagedHttpCache

internal suspend fun <T> TestScope.withHttpCacheClient(
    owner: ManagedHttpCache?,
    handler: MockRequestHandler,
    block: suspend (HttpClient) -> T,
): T {
    val client = HttpClient(MockEngine) {
        engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        }
        installManagedHttpCache(owner)
    }.attachResponseCache(owner)
    try {
        return withTimeout(5_000) { block(client) }
    } finally {
        client.close()
        withContext(NonCancellable) {
            withTimeout(5_000) {
                client.coroutineContext.job.cancelAndJoin()
                client.engine.coroutineContext.job.cancelAndJoin()
            }
        }
    }
}
