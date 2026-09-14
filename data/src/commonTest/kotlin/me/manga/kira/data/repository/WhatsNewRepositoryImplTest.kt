package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.domain.model.whatsnew.MediaType
import me.manga.kira.domain.model.whatsnew.WhatsNewFeature
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.platform.version.AppVersionProvider
import me.manga.kira.presentation.features.whatsnew.data.WhatsNewRemoteDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the real HTTP/decode/projection boundary without a live service or platform storage. */
class WhatsNewRepositoryImplTest {
    @Test
    fun successfulEmptyDocumentIsNotFailureAndDoesNotMarkSeen() =
        runTest {
            withFixture { fixture ->
                assertEquals(AppResult.Success(emptyList<WhatsNewFeature>()), fixture.repository.getFeatures())
                assertEquals(WHATS_NEW_URL, fixture.requestedUrl)
                fixture.assertUnseen()
            }
        }

    @Test
    fun httpFailuresPreserveStatusEvenWithAnEmptyFeaturesPayload() =
        runTest {
            for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.InternalServerError)) {
                for (validateStatus in listOf(false, true)) {
                    withFixture(Fixture(status = status, validateStatus = validateStatus)) { fixture ->
                        val failure = assertIs<AppResult.Failure>(fixture.repository.getFeatures())
                        val error = assertIs<AppError.Network.Http>(failure.error)
                        assertEquals(status.value, error.statusCode)
                        fixture.assertUnseen()
                    }
                }
            }
        }

    @Test
    fun emptyMalformedAndMissingRequiredFieldsAreSerializationFailures() =
        runTest {
            val bodies =
                listOf(
                    "",
                    " \n\t",
                    "not-json",
                    "{}",
                    """{"features":null}""",
                    """{"features":[{"description":{"en":"Body"},"mediaType":"IMAGE"}]}""",
                    """{"features":[{"title":{"en":"Title"},"mediaType":"IMAGE"}]}""",
                    """{"features":[{"title":{"en":"Title"},"description":{"en":"Body"}}]}""",
                )
            for (body in bodies) {
                withFixture(Fixture(body = body)) { fixture ->
                    val failure = assertIs<AppResult.Failure>(fixture.repository.getFeatures())
                    assertIs<AppError.Network.Serialization>(failure.error)
                    fixture.assertUnseen()
                }
            }
        }

    @Test
    fun transportTimeoutAndUnexpectedFailuresKeepTheirTypedCategoryAndCause() =
        runTest {
            val cases =
                listOf(
                    IOException("transport detail") to AppError.Network.NoConnectivity::class,
                    ConnectTimeoutException("connect detail") to AppError.Network.Timeout::class,
                    SocketTimeoutException("socket detail") to AppError.Network.Timeout::class,
                    HttpRequestTimeoutException(
                        "https://example.invalid/private",
                        10L,
                    ) to AppError.Network.Timeout::class,
                    IllegalStateException("private failure detail") to AppError.Unexpected::class,
                )
            for ((thrown, expected) in cases) {
                withFixture(Fixture(beforeResponse = { throw thrown })) { fixture ->
                    val error = assertIs<AppResult.Failure>(fixture.repository.getFeatures()).error
                    assertEquals(expected, error::class)
                    assertEquals(thrown.message, assertNotNull(error.cause).message)
                    if (error is AppError.Unexpected) {
                        assertEquals(thrown::class.simpleName.orEmpty(), error.message)
                    }
                    fixture.assertUnseen()
                }
            }
        }

    @Test
    fun cancellationIsRethrownInsteadOfBecomingAFailureResult() =
        runTest {
            withFixture(Fixture(beforeResponse = { throw CancellationException("cancel fetch") })) { fixture ->
                assertFailsWith<CancellationException> { fixture.repository.getFeatures() }
                fixture.assertUnseen()
            }
        }

    @Test
    fun cancellingSuspendedFetchDoesNotReturnContentOrConsumeSeenState() =
        runTest {
            val started = CompletableDeferred<Unit>()
            withFixture(
                Fixture(beforeResponse = {
                    started.complete(Unit)
                    awaitCancellation()
                }),
            ) { fixture ->
                var returned: AppResult<List<WhatsNewFeature>>? = null
                val fetch = launch { returned = fixture.repository.getFeatures() }
                try {
                    started.await()
                } finally {
                    fetch.cancelAndJoin()
                }
                assertNull(returned)
                fixture.assertUnseen()
            }
        }

    @Test
    fun localizationFallbackMediaAndImageUrlListsRemainIntact() =
        runTest {
            val languages = listOf("ar-EG" to "Arabic", "de-DE" to "English", "zz" to "English", "" to "English")
            for ((language, title) in languages) {
                withFixture(Fixture(body = FEATURES_RESPONSE)) { fixture ->
                    fixture.dataStore.setLanguage(language)
                    val features =
                        assertIs<AppResult.Success<List<WhatsNewFeature>>>(fixture.repository.getFeatures()).value
                    val first = features.first()
                    assertEquals(title, first.title)
                    assertEquals(if (language == "ar-EG") "Arabic body" else "English body", first.description)
                    assertEquals(MediaType.VIDEO, first.mediaType)
                    assertEquals("poster", first.imageResName)
                    assertTrue(first.imageResNameList.isEmpty())
                    assertEquals(listOf("https://example.com/1.jpg", "https://example.com/2.jpg"), first.imageUrlList)
                    assertEquals("https://example.com/cover.jpg", first.imageUrl)
                    assertEquals("https://example.com/video.mp4", first.videoUrl)
                    assertTrue(first.isNew)
                    assertEquals("1.0.5", first.version)
                    assertEquals(listOf("Fallback", "Premier", "Unknown Feature"), features.drop(1).map { it.title })
                    assertEquals(
                        listOf("Fallback body", "Description", "No description available"),
                        features.drop(1).map { it.description },
                    )
                    assertEquals(MediaType.IMAGE, features[1].mediaType)
                    fixture.assertUnseen()
                }
            }
        }

    @Test
    fun explicitMarkUsesExistingKeysAndTheVersionAtWriteTime() =
        runTest {
            withFixture { fixture ->
                fixture.version.versionName = "next-version"
                fixture.repository.markSeen()
                assertEquals("next-version", fixture.prefs.getString(SEEN_KEY))
                assertTrue(fixture.prefs.getLong(TIMESTAMP_KEY) > OLD_TIMESTAMP)
                assertNull(fixture.requestedUrl, "Marking seen must not fetch release notes")
            }
        }

    private suspend fun withFixture(
        fixture: Fixture = Fixture(),
        block: suspend (Fixture) -> Unit,
    ) {
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun Fixture.assertUnseen() {
        assertEquals(OLD_VERSION, prefs.getString(SEEN_KEY))
        assertEquals(OLD_TIMESTAMP, prefs.getLong(TIMESTAMP_KEY))
    }

    private class Fixture(
        body: String = EMPTY_RESPONSE,
        status: HttpStatusCode = HttpStatusCode.OK,
        validateStatus: Boolean = false,
        beforeResponse: suspend () -> Unit = {},
    ) {
        private val settings =
            MapSettings().apply {
                putString(SEEN_KEY, OLD_VERSION)
                putLong(TIMESTAMP_KEY, OLD_TIMESTAMP)
            }
        val prefs = SharedPrefsHelper(settings)
        val dataStore = DataStoreHelper(settings)
        val version = Version()
        var requestedUrl: String? = null
        private val engine =
            MockEngine { request ->
                requestedUrl = request.url.toString()
                beforeResponse()
                respond(body, status)
            }
        private val client = HttpClient(engine) { expectSuccess = validateStatus }
        val repository = WhatsNewRepositoryImpl(WhatsNewRemoteDataSource(client), prefs, version, dataStore)

        suspend fun close() =
            withContext(NonCancellable) {
                client.close()
                engine.close()
                client.coroutineContext.cancel()
                engine.coroutineContext.cancel()
                client.coroutineContext.job.join()
                engine.coroutineContext.job.join()
            }
    }

    private class Version : AppVersionProvider {
        override var versionName = "current-version"
        override val packageName = "me.manga.kira"
    }

    private companion object {
        const val WHATS_NEW_URL = "https://kiramanga.me/whatsnew/35/whatsnew.json"
        const val EMPTY_RESPONSE = """{"features":[]}"""
        const val SEEN_KEY = "whats_new_last_shown_version_name"
        const val TIMESTAMP_KEY = "whats_new_last_shown_timestamp"
        const val OLD_VERSION = "previous-version"
        const val OLD_TIMESTAMP = 42L
        val FEATURES_RESPONSE =
            """
            {"features":[
              {"title":{"en":"English","ar":"Arabic"},"description":{"en":"English body","ar":"Arabic body"},
               "mediaType":"vIdEo","imageRes":"poster","imageResList":["https://example.com/1.jpg","https://example.com/2.jpg"],
               "imageUrl":"https://example.com/cover.jpg","videoUrl":"https://example.com/video.mp4","isNew":true,"version":"1.0.5"},
              {"title":{"en":"Fallback"},"description":{"en":"Fallback body"},"mediaType":"future-type"},
              {"title":{"fr":"Premier"},"description":{"fr":"Description"},"mediaType":"IMAGE"},
              {"title":{},"description":{},"mediaType":"IMAGE"}
            ]}
            """.trimIndent()
    }
}
