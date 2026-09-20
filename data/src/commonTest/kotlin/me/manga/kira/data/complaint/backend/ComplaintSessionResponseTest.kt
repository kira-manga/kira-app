package me.manga.kira.data.complaint.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import me.manga.kira.data.complaint.backend.ComplaintSessionFailure as Failure
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class ComplaintSessionResponseTest {
    @Test
    fun exactFieldsAllowWireOrderWhitespaceAndEscapesButKeepServerTimeAndRedaction() {
        val credential =
            Fixtures.record(generation = 7, version = 3, material = Fixtures.material(scope = Fixtures.OTHER_ID))
        val reordered =
            sessionResponse(credential)
                .removeSurrounding("{", "}")
                .split(',')
                .reversed()
                .joinToString(",\n  ", "{\n  ", "\n}")
        val escaped = reordered.replace("tokenType", "token\\u0054ype").replace("Bearer", "Be\\u0061rer")
        for (text in listOf(sessionResponse(credential), reordered, escaped)) {
            val result = assertIs<ComplaintSessionResult.Ready>(ComplaintSessionResponse.decode(text, credential))
            assertTrue(result.session.binding.matches(credential))
            assertEquals(Instant.parse(SESSION_ISSUED_AT), result.session.issuedAt)
            assertEquals(900L, result.session.expiresInSeconds)
            assertEquals("Bearer $SESSION_TOKEN", result.session.authorizationValue())
            for (value in listOf(result, result.session, result.session.binding)) {
                assertFalse(SESSION_TOKEN in value.toString())
                assertFalse(Fixtures.ID in value.toString())
                assertFalse(Fixtures.secret in value.toString())
            }
        }
    }

    /** One flat-protocol adversary group, not a general-purpose JSON parser test framework. */
    @Test
    fun duplicateUnknownNestedMissingAndWrongScalarsFailClosed() {
        val valid = sessionResponse()
        val bad = malformedSessionPayloads(valid)
        bad.forEach { assertSessionFailure(Failure.RESPONSE, ComplaintSessionResponse.decode(it, Fixtures.record())) }
    }

    @Test
    fun serverCannotReplaceDurableIdentityVersionOrScope() {
        val valid = sessionResponse()
        listOf(
            valid.replace(Fixtures.ID, Fixtures.OTHER_ID),
            valid.replace("\"credentialVersion\":1", "\"credentialVersion\":2"),
            valid.replace(Fixtures.SCOPE, Fixtures.OTHER_ID),
            valid.replace(Fixtures.ID, Fixtures.ID.replaceFirst('1', 'A')),
            valid.replace("\"credentialVersion\":1", "\"credentialVersion\":0"),
        ).forEach { assertSessionFailure(Failure.INVALIDATED, ComplaintSessionResponse.decode(it, Fixtures.record())) }
    }
}

private fun malformedSessionPayloads(valid: String): List<String> =
    listOf(
        valid.replaceFirst("{", "{\"installationId\":\"${Fixtures.ID}\","),
        valid.replaceFirst("{", "{\"installation\\u0049d\":\"${Fixtures.ID}\","),
        valid.replaceFirst("{", "{\"unknown\":1,"),
        valid.replaceFirst("{", "{\"localGeneration\":1,"),
        valid.replace("\"tokenType\":\"Bearer\",", ""),
        valid.replace("\"tokenType\":\"Bearer\"", "\"tokenType\":{}"),
        valid.replace("\"tokenType\":\"Bearer\"", "\"tokenType\":[]"),
        valid.replace("\"tokenType\":\"Bearer\"", "\"tokenType\":null"),
        valid.replace("\"tokenType\":\"Bearer\"", "\"tokenType\":true"),
        valid.replace("\"credentialVersion\":1", "\"credentialVersion\":\"1\""),
        valid.replace("\"credentialVersion\":1", "\"credentialVersion\":1.0"),
        valid.replace("\"credentialVersion\":1", "\"credentialVersion\":1e0"),
        valid.replace("\"credentialVersion\":1", "\"credentialVersion\":01"),
        valid.replace("\"credentialVersion\":1", "\"credentialVersion\":+1"),
        valid.replace("\"credentialVersion\":1", "\"credentialVersion\":-1"),
        valid.replace("\"credentialVersion\":1", "\"credentialVersion\":9223372036854775808"),
        valid.replace("\"expiresInSeconds\":900", "\"expiresInSeconds\":\"900\""),
        valid.replace("\"expiresInSeconds\":900", "\"expiresInSeconds\":899"),
        valid.replace("\"expiresInSeconds\":900", "\"expiresInSeconds\":901"),
        valid.replace("Bearer", "bearer"),
        valid.replace(SESSION_TOKEN, ""),
        valid.replace(SESSION_TOKEN, "part.part"),
        valid.replace(SESSION_TOKEN, "part.part."),
        valid.replace(SESSION_TOKEN, "part.part.bad token"),
        valid.replace(SESSION_TOKEN, "part.part.\\u00e9"),
        valid.replace(SESSION_TOKEN, "A".repeat(4_096) + ".part.part"),
        valid.replace(SESSION_ISSUED_AT, "2026-09-16T08:09:10+00:00"),
        valid.replace(SESSION_ISSUED_AT, "2026-09-16T08:09:10"),
        valid.replace(SESSION_ISSUED_AT, "2026-09-16T08:09:60Z"),
        valid.replace(SESSION_ISSUED_AT, "2026-02-30T08:09:10Z"),
        valid.replace(SESSION_ISSUED_AT, "2026-09-16T24:00:00Z"),
        "[$valid]",
        "$valid{}",
        valid.dropLast(1) + ",}",
        "\uFEFF$valid",
    )
