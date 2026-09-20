package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import me.manga.kira.platform.storage.InstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.InstallationMaterialGenerationResult
import kotlin.test.assertNotNull
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordinator as CredentialCoordinator
import me.manga.kira.platform.storage.InstallationCredentialRecord as CredentialRecord

/** Reuses the existing real coordinator/storage-fault fixtures. MockEngine is not native TLS/storage proof. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class InstallationEnrollmentFixture(
    scope: TestScope,
    val storage: InstallationCoordinatorFixture = InstallationCoordinatorFixture(),
    expectedDataScopeId: String? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        if (request.method == HttpMethod.Get) {
            respond(bootstrapResponse(), HttpStatusCode.OK, sessionHeaders())
        } else {
            respond(
                sessionResponse(assertNotNull(storage.credentials.payloadRecord)),
                HttpStatusCode.Created,
                enrollmentHeaders(),
            )
        }
    },
) {
    val requests = mutableListOf<HttpRequestData>()
    val generator = EnrollmentMaterialGenerator()
    val engine =
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
                addHandler { request ->
                    requests += request
                    handler(request)
                }
            },
        )
    val http = InstallationEnrollmentHttp(
        assertNotNull(ComplaintBackendEndpoint.checked(SESSION_BASE_URL, expectedDataScopeId)), engine,
    )

    suspend fun enroll(coordinator: CredentialCoordinator = storage.coordinator) = coordinator.enroll(http, generator)

    fun close() {
        http.close()
        engine.close()
    }
}

/** Fixed synthetic material; production OS entropy implementations are reused unchanged. */
internal class EnrollmentMaterialGenerator : InstallationCredentialMaterialGenerator {
    val scopes = mutableListOf<String>()
    var result: InstallationMaterialGenerationResult? = null

    override fun generate(dataScopeId: String): InstallationMaterialGenerationResult {
        scopes += dataScopeId
        return result ?: InstallationMaterialGenerationResult.Generated(Fixtures.material(scope = dataScopeId))
    }
}

internal fun bootstrapResponse(scope: String = Fixtures.SCOPE) = """{"dataScopeId":"$scope","contractVersion":1}"""

internal fun enrollmentHeaders(
    status: HttpStatusCode = HttpStatusCode.Created,
    change: HeadersBuilder.() -> Unit = {},
): Headers =
    sessionHeaders(status) {
        if (status == HttpStatusCode.Created) {
            set(HttpHeaders.ContentType, "application/json")
            append(HttpHeaders.Location, "/api/v1/installations/me")
        }
        change()
    }

internal fun enrollmentStorage(record: CredentialRecord = Fixtures.record()) = InstallationCoordinatorFixture(record)
