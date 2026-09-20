package me.manga.kira.data.complaint.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class InstallationBootstrapResponseTest {
    @Test
    fun onlyClosedVersionOneAndCanonicalLiveOrTestScopeDecode() {
        for (scope in listOf(Fixtures.SCOPE, Fixtures.OTHER_ID)) {
            val inputs =
                listOf(
                    bootstrapResponse(scope),
                    """ { "contractVersion" : 1, "dataScopeId" : "$scope" } """,
                    """{"data\u0053copeId":"$scope","contractVersion":1}""",
                )
            for (input in inputs) {
                val result = InstallationBootstrapResponse.decode(input)
                val ready = assertIs<InstallationEnrollmentResult.Ready<InstallationBootstrapResponse>>(result)
                assertEquals(scope, ready.value.dataScopeId)
            }
        }
        val endpoint = assertNotNull(ComplaintBackendEndpoint.checked("$SESSION_BASE_URL/"))
        assertEquals("$SESSION_BASE_URL/api/v1/installations", endpoint.enrollmentUrl.toString())
        assertEquals("$SESSION_BASE_URL/api/v1/installations/bootstrap", endpoint.bootstrapUrl.toString())
        assertEquals("$SESSION_BASE_URL/api/v1/installations/session", endpoint.sessionUrl.toString())
    }

    @Test
    fun duplicateUnknownMalformedAndWrongTypedFieldsNeverProduceScope() {
        val valid = bootstrapResponse()
        val invalid =
            listOf(
                "",
                "{}",
                "[]",
                valid + "{}",
                valid.dropLast(1),
                valid.replace(",\"contractVersion\":1", ""),
                valid.replace("1}", "1,\"other\":true}"),
                valid.replace("1}", "1,\"dataScopeId\":\"${Fixtures.SCOPE}\"}"),
                valid.replace("1}", "1,\"contract\\u0056ersion\":1}"),
                valid.replace("1}", "1,}"),
                valid.replace("1}", "\"1\"}"),
                valid.replace("1}", "01}"),
                valid.replace("1}", "1.0}"),
                valid.replace("1}", "1e0}"),
                valid.replace("1}", "-1}"),
                valid.replace("1}", "2}"),
                valid.replace("1}", "true}"),
                valid.replace("1}", "{}" + "}"),
                valid.replace("\"${Fixtures.SCOPE}\"", "null"),
                valid.replace(Fixtures.SCOPE, "11111111-1111-3111-8111-111111111111"),
                bootstrapResponse("22222222-2222-4222-A222-222222222222"),
            )
        for (input in invalid) {
            val result = assertIs<InstallationEnrollmentResult.Failed>(InstallationBootstrapResponse.decode(input))
            assertEquals(ComplaintSessionFailure.RESPONSE, result.reason)
        }
    }
}
