package me.manga.kira.data.complaint.backend

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import me.manga.kira.data.complaint.backend.ComplaintReportTestFixtures as Frames

class ComplaintEditFingerprintTest {
    @Test
    fun ordinaryAndBodyOnlyFramesMatchIndependentBackend97dbLiterals() {
        val ordinary = mobileEditRequest(key = MOBILE_EDIT_KEY, scope = MOBILE_EDIT_SCOPE)
        val bodyOnly =
            mobileEditRequest(
                target = mobileEditTarget(shape = ComplaintEditShape.BODY_ONLY),
                subject = null,
                body = "Notice reply",
                key = MOBILE_EDIT_KEY,
                scope = MOBILE_EDIT_SCOPE,
            )
        assertContentEquals(Frames.hexBytes(ORDINARY_HEX), ComplaintEditFingerprint.frameBytes(ordinary))
        assertContentEquals(Frames.hexBytes(BODY_ONLY_HEX), ComplaintEditFingerprint.frameBytes(bodyOnly))
        assertEquals("cXjLO2OeE8ZUn_4TyoWns9JwhcdBoL-CcTAB4emRHOY", ComplaintEditFingerprint.of(ordinary).encoded)
        assertEquals("dN0dOWXt0nhHTb4LTOalYCg92MRxI8EHfiQJikM3ij4", ComplaintEditFingerprint.of(bodyOnly).encoded)
        assertEquals(1, ComplaintEditFingerprint.of(ordinary).version)
    }

    @Test
    fun maximumEmojiFrameAndLongPreconditionMatchIndependentDigestAndReturnCopies() {
        val request =
            mobileEditRequest(
                target = mobileEditTarget(version = Long.MAX_VALUE),
                subject = "🙂".repeat(200),
                body = "🙂".repeat(1000),
                key = MOBILE_EDIT_KEY,
                scope = MOBILE_EDIT_SCOPE,
            )
        val fingerprint = ComplaintEditFingerprint.of(request)
        assertEquals(5065, ComplaintEditFingerprint.frameBytes(request).size)
        assertEquals("WSE34gdD0_m4v2wA7W7aWbAv8jaxpT1ZIivnhU3Vzcc", fingerprint.encoded)
        fingerprint.bytes().fill(0)
        assertEquals(32, fingerprint.bytes().size)
        assertEquals("WSE34gdD0_m4v2wA7W7aWbAv8jaxpT1ZIivnhU3Vzcc", fingerprint.encoded)
        assertEquals("ComplaintEditFingerprint(redacted)", fingerprint.toString())
    }

    @Test
    fun keyIsSeparateWhileScopeTargetSubjectBodyAndPreconditionAreBound() {
        val original = mobileEditRequest()
        val frame = ComplaintEditFingerprint.frameBytes(original)
        assertContentEquals(frame, ComplaintEditFingerprint.frameBytes(mobileEditRequest(key = MUTATION_OTHER_KEY)))
        val changed =
            listOf(
                mobileEditRequest(scope = MOBILE_EDIT_SCOPE),
                mobileEditRequest(target = mobileEditTarget(id = MOBILE_REPLY_PARENT)),
                mobileEditRequest(target = mobileEditTarget(version = 8)),
                mobileEditRequest(subject = "Other"),
                mobileEditRequest(body = "Other"),
                mobileEditRequest(target = mobileEditTarget(shape = ComplaintEditShape.BODY_ONLY), subject = null),
            )
        val hashes = (listOf(original) + changed).map { ComplaintEditFingerprint.of(it).encoded }
        assertEquals(hashes.size, hashes.toSet().size)
        assertNotEquals(ComplaintReportFingerprint.of(mutationReport()).encoded, hashes.first())
        assertNotEquals(ComplaintReplyFingerprint.of(mobileReplyRequest()).encoded, hashes.first())
    }

    @Test
    fun fixedTrimCrLfAndOuterTagPaddingCanonicalizeWithoutNfc() {
        val padded =
            mobileEditRequest(
                target = mobileEditTarget(tag = " \t\"complaint-$MOBILE_EDIT_ID-v7\"\t "),
                subject = " \u3000Synthetic edit\u00a0 ",
                body = "\t Line 1\r\nLine 2 \n",
            )
        assertContentEquals(
            ComplaintEditFingerprint.frameBytes(mobileEditRequest()),
            ComplaintEditFingerprint.frameBytes(padded),
        )
        assertNotEquals(
            ComplaintEditFingerprint.of(mobileEditRequest(subject = "é")).encoded,
            ComplaintEditFingerprint.of(mobileEditRequest(subject = "e\u0301")).encoded,
        )
    }
}

// Exact independently constructed backend97db contract literals, not expectations computed by the mobile producer.
private const val ORDINARY_HEX =
    "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e74000000010000000550415443480000001f2f" +
        "6170692f76312f636f6d706c61696e74732f7b69647d2f636f6e74656e740000000a4f574e45525f45444954000000243232323232323232" +
        "2d323232322d343232322d383232322d323232323232323232323232000000010000002431323365343536372d653839622d353264332d61" +
        "3435362d3432363631343137343030300000000e53796e74686574696320656469740000000d4c696e6520310a4c696e6520320000003322" +
        "636f6d706c61696e742d31323365343536372d653839622d353264332d613435362d3432363631343137343030302d763722"
private const val BODY_ONLY_HEX =
    "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e74000000010000000550415443480000001f2f" +
        "6170692f76312f636f6d706c61696e74732f7b69647d2f636f6e74656e740000000a4f574e45525f45444954000000243232323232323232" +
        "2d323232322d343232322d383232322d323232323232323232323232000000010000002431323365343536372d653839622d353264332d61" +
        "3435362d343236363134313734303030ffffffff0000000c4e6f74696365207265706c790000003322636f6d706c61696e742d3132336534" +
        "3536372d653839622d353264332d613435362d3432363631343137343030302d763722"
