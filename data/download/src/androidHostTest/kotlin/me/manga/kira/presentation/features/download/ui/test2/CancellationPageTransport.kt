package me.manga.kira.presentation.features.download.ui.test2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/** Real service HTTP calls and files; only the second HTTP response is held for partial-stop cases. */
internal class CancellationPageTransport(
    private val seam: CancellationSeam,
    private val rows: DownloadWorkerCancellationRows,
) : AutoCloseable {
    val requests = AtomicInteger()
    val secondResponseEntered = CompletableDeferred<Unit>()
    val releaseSecondResponse = CompletableDeferred<Unit>()
    private val pages =
        List(seam.pageCount) { index ->
            DownloadPage("https://example.test/app75/$index.png", mapOf("X-Fixture-Page" to index.toString()))
        }
    val client =
        HttpClient(
            MockEngine { request ->
                val index = requests.getAndIncrement()
                assertEquals(pages[index].url, request.url.toString())
                assertEquals(index.toString(), request.headers["X-Fixture-Page"])
                if (seam.partial && index == 1) {
                    secondResponseEntered.complete(Unit)
                    releaseSecondResponse.await()
                }
                respond(PAGE_PNG, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
            },
        )
    val provider =
        object : ChapterPageProvider {
            override suspend fun pagesOrNull(
                api: String,
                mangaUrl: String,
                mangaLanguage: String,
                chapterUrl: String,
            ): List<DownloadPage> {
                assertEquals(FIXTURE_API, api)
                assertEquals(rows.manga.url, mangaUrl)
                assertEquals(rows.manga.language, mangaLanguage)
                assertEquals(rows.original.saved.url, chapterUrl)
                return pages
            }
        }

    override fun close() {
        releaseSecondResponse.complete(Unit)
        client.close()
    }
}
