package me.manga.kira.data.complaint.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.ComplaintReportTestFixtures as Fixtures

class ComplaintReplyRequestTest {
    @Test
    fun parentIsCanonicalAndDistinctButOnlyNewClientAndKeyMustBeV4() {
        val parent = "aaaaaaaa-aaaa-5aaa-8aaa-aaaaaaaaaaaa"
        val request = replyProtocolRequest(parentId = parent)
        assertEquals(parent, request.parentId)
        assertEquals(Fixtures.ID, request.identity.clientId.canonical)
        assertEquals("OWNER_REPLY", request.operation.name)
        val invalid =
            listOf(
                Fixtures.ID,
                "",
                parent.uppercase(),
                " $parent",
                "$parent ",
                parent.replace("-", ""),
                "a-a-5-8-a",
                "$parent/replies",
                "$parent?ignored=true",
            )
        for (value in invalid) {
            assertSame(
                ComplaintReplyRequestResult.InvalidParent,
                ComplaintReplyRequest.normalize(Fixtures.identity(), value, "a", Fixtures.metadata()),
            )
        }
        assertNull(ComplaintReportIdentity.checked(parent, Fixtures.KEY, Fixtures.LIVE))
        assertNull(ComplaintReportIdentity.checked(Fixtures.ID, parent, Fixtures.LIVE))
        val action =
            checkNotNull(
                PendingComplaintAction.checked(
                    PendingComplaintOperation.CREATE_REPLY,
                    request.identity.clientId.canonical,
                    request.parentId,
                    null,
                ),
            )
        assertEquals(listOf(parent, Fixtures.ID), action.orderedTargetIds())
        assertNull(action.canonicalPrecondition())
    }

    @Test
    fun replyUsesOneToFiveHundredScalarsAndFixedNormalizationWithoutRelaxingReportMinimum() {
        assertEquals("a", replyProtocolRequest(body = " \t\u00a0a\r\n ").body)
        assertEquals("A\n😀é", replyProtocolRequest(body = " \u00a0A\r\n😀é\t ").body)
        assertEquals("a".repeat(500), replyProtocolRequest(body = "a".repeat(500)).body)
        val maximum = "😀".repeat(500)
        assertEquals(maximum, replyProtocolRequest(body = maximum).body)
        assertEquals(2_000, replyProtocolRequest(body = maximum).body.encodeToByteArray().size)
        val invalid =
            listOf(
                "" to ComplaintReportRejection.REQUIRED,
                " \t\n\u00a0 " to ComplaintReportRejection.REQUIRED,
                "a".repeat(501) to ComplaintReportRejection.TOO_LONG,
                "$maximum😀" to ComplaintReportRejection.TOO_LONG,
                "a".repeat(16_385) to ComplaintReportRejection.TOO_LONG,
                "\ra" to ComplaintReportRejection.FORBIDDEN_CONTROL,
                "a\u0000" to ComplaintReportRejection.FORBIDDEN_CONTROL,
                "\ud800" to ComplaintReportRejection.MALFORMED_UNICODE,
                "a\udc00" to ComplaintReportRejection.MALFORMED_UNICODE,
            )
        for ((body, reason) in invalid) {
            val rejected =
                assertIs<ComplaintReplyRequestResult.Rejected>(
                    ComplaintReplyRequest.normalize(Fixtures.identity(), Fixtures.OTHER_ID, body, Fixtures.metadata()),
                )
            assertEquals(ComplaintReportField.BODY, rejected.field)
            assertEquals(reason, rejected.reason)
        }
        assertEquals(ComplaintReportRejection.TOO_SHORT, Fixtures.rejected(Fixtures.result(body = "a")).reason)
    }

    @Test
    fun replyMetadataKeepsNullDistinctFromEmptyAndAllDiagnosticRenderingsAreRedacted() {
        val absent = replyProtocolRequest()
        assertNull(absent.metadata.appVersion)
        val empty = replyProtocolRequest(metadata = Fixtures.metadata(" \t\r\n ", " ", "\t", "\n"))
        assertEquals(
            listOf("", "", "", ""),
            listOf(
                empty.metadata.appVersion,
                empty.metadata.osVersion,
                empty.metadata.manufacturer,
                empty.metadata.deviceModel,
            ),
        )
        val metadata = Fixtures.metadata("private-app", "private-os", "private-maker", "private-model")
        val result = ComplaintReplyRequest.normalize(Fixtures.identity(), Fixtures.OTHER_ID, "private-body", metadata)
        val request = assertIs<ComplaintReplyRequestResult.Accepted>(result).request
        val fingerprint = ComplaintReplyFingerprint.of(request)
        val rendering = listOf(metadata, result, request, request.metadata, request.identity, fingerprint).joinToString()
        for (forbidden in listOf("private-", Fixtures.ID, Fixtures.OTHER_ID, Fixtures.KEY, fingerprint.encoded)) {
            assertFalse(rendering.contains(forbidden))
        }
        assertTrue(rendering.contains("redacted"))
    }
}

/** Normalized comparison fixture only; it supplies no durable slot or authenticated parent authority. */
internal fun replyProtocolRequest(
    identity: ComplaintReportIdentity = Fixtures.identity(),
    parentId: String = Fixtures.OTHER_ID,
    body: String = "a",
    metadata: ComplaintReportMetadataInput = Fixtures.metadata(),
): ComplaintReplyRequest =
    assertIs<ComplaintReplyRequestResult.Accepted>(
        ComplaintReplyRequest.normalize(identity, parentId, body, metadata),
    ).request
