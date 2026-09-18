package me.manga.kira.data.complaint.backend

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import me.manga.kira.data.complaint.backend.ComplaintReportTestFixtures as Fixtures

class ComplaintReplyFingerprintTest {
    @Test
    fun exactBackendCandidateReplyVectorsBindRouteOperationAndOrderedParentClient() {
        val goldens =
            listOf(
                Golden(
                    replyProtocolRequest(),
                    BASIC_HEX,
                    249,
                    "6ac63649647daed223d03db6698d0f16fc871caf2ee276556f04a843bf99bc9e",
                    "asY2SWR9rtIj0D22aY0PFvyHHK8u4nZVbwSoQ7-ZvJ4",
                ),
                Golden(
                    replyProtocolRequest(
                        identity = Fixtures.identity(scope = Fixtures.TEST_SCOPE),
                        body = "A\n😀é",
                        metadata = Fixtures.metadata("1.0", "", "M", "D"),
                    ),
                    UNICODE_HEX,
                    261,
                    "38ef127b0a034c551d39ec18bacdb68bae3f0b0b81abe4997420649118c9256e",
                    "OO8SewoDTFUdOewYus22i64_CwuBq-SZdCBkkRjJJW4",
                ),
            )
        for (golden in goldens) {
            val frame = ComplaintReplyFingerprint.frameBytes(golden.request)
            val fingerprint = ComplaintReplyFingerprint.of(golden.request)
            assertEquals(golden.size, frame.size)
            assertContentEquals(Fixtures.hexBytes(golden.frameHex), frame)
            assertContentEquals(Fixtures.hexBytes(golden.digestHex), fingerprint.bytes())
            assertEquals(golden.encoded, fingerprint.encoded)
            assertEquals(1, fingerprint.version)
        }
    }

    @Test
    fun orderedTargetsScopeAndSemanticFieldsChangeTheDigestButTheSeparateKeyDoesNot() {
        val original = replyProtocolRequest()
        val changed =
            listOf(
                replyProtocolRequest(parentId = Fixtures.OTHER_KEY),
                replyProtocolRequest(identity = Fixtures.identity(id = Fixtures.OTHER_KEY)),
                replyProtocolRequest(identity = Fixtures.identity(scope = Fixtures.TEST_SCOPE)),
                replyProtocolRequest(identity = Fixtures.identity(id = Fixtures.OTHER_ID), parentId = Fixtures.ID),
                replyProtocolRequest(body = "b"),
                replyProtocolRequest(metadata = Fixtures.metadata(appVersion = "")),
                replyProtocolRequest(metadata = Fixtures.metadata(osVersion = "1")),
                replyProtocolRequest(metadata = Fixtures.metadata(manufacturer = "M")),
                replyProtocolRequest(metadata = Fixtures.metadata(deviceModel = "D")),
            )
        val fingerprints = (listOf(original) + changed).map { ComplaintReplyFingerprint.of(it).encoded }
        assertEquals(fingerprints.size, fingerprints.toSet().size)
        val otherKey = replyProtocolRequest(identity = Fixtures.identity(key = Fixtures.OTHER_KEY))
        assertNotEquals(original.identity.key.value, otherKey.identity.key.value)
        assertContentEquals(ComplaintReplyFingerprint.frameBytes(original), ComplaintReplyFingerprint.frameBytes(otherKey))
        assertNotEquals(
            ComplaintReplyFingerprint.of(replyProtocolRequest(body = "é")).encoded,
            ComplaintReplyFingerprint.of(replyProtocolRequest(body = "e\u0301")).encoded,
        )
        assertNotEquals(
            ComplaintReportFingerprint.of(Fixtures.request()).encoded,
            ComplaintReplyFingerprint.of(replyProtocolRequest(body = "abcde")).encoded,
        )
    }

    @Test
    fun maximalReplyFrameMatchesTheIndependentDigestAndReturnedBytesCannotChangeIt() {
        val request =
            replyProtocolRequest(
                body = "😀".repeat(500),
                metadata = Fixtures.metadata("😀".repeat(64), "😀".repeat(128), "😀".repeat(128), "😀".repeat(128)),
            )
        val frame = ComplaintReplyFingerprint.frameBytes(request)
        val fingerprint = ComplaintReplyFingerprint.of(request)
        assertEquals(4_040, frame.size)
        assertEquals("n-RybksF209zKRBJYickTQZ5_kQIB8S4ojaZqReao88", fingerprint.encoded)
        val returned = fingerprint.bytes()
        assertEquals(32, returned.size)
        assertEquals(43, fingerprint.encoded.length)
        returned.fill(0)
        frame.fill(0)
        assertEquals("n-RybksF209zKRBJYickTQZ5_kQIB8S4ojaZqReao88", fingerprint.encoded)
        assertEquals(fingerprint.encoded, ComplaintReplyFingerprint.of(request).encoded)
        assertNotEquals(returned.toList(), fingerprint.bytes().toList())
    }

    private class Golden(
        val request: ComplaintReplyRequest,
        val frameHex: String,
        val size: Int,
        val digestHex: String,
        val encoded: String,
    )

    private companion object {
        // Frozen backend focused-reply-tests-source-20260918-01 literals, not computed by the app's writer.
        // These candidate vectors do not claim accepted runtime or enabled backend/app parity.
        const val BASIC_HEX =
            "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e740000000100000004504f53540000001f2f6170692f76" +
                "312f636f6d706c61696e74732f7b69647d2f7265706c6965730000000b4f574e45525f5245504c590000002430303030303030302d303030302d3030" +
                "30302d303030302d303030303030303030303030000000020000002434343434343434342d343434342d343434342d383434342d3434343434343434" +
                "343434340000002431313131313131312d313131312d343131312d383131312d3131313131313131313131310000000161ffffffff00000000000000" +
                "0000000000ffffffff"
        const val UNICODE_HEX =
            "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e740000000100000004504f53540000001f2f6170692f76" +
                "312f636f6d706c61696e74732f7b69647d2f7265706c6965730000000b4f574e45525f5245504c590000002433333333333333332d333333332d3433" +
                "33332d383333332d333333333333333333333333000000020000002434343434343434342d343434342d343434342d383434342d3434343434343434" +
                "343434340000002431313131313131312d313131312d343131312d383131312d31313131313131313131313100000008410af09f9880c3a900000003" +
                "312e3000000000000000014d0000000144ffffffff"
    }
}
