package me.manga.kira.data.complaint.backend

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import me.manga.kira.data.complaint.backend.ComplaintReportTestFixtures as Frames

class ComplaintOwnerDeleteFingerprintTest {
    @Test
    fun ordinaryAndMinimumFramesMatchIndependentFrozenDeleteLiterals() {
        assertDeleteVector(
            42,
            235,
            ORDINARY_HEX,
            "3e065f48addf6a79cafb2bf0d9d9a12c742366b57d2276e24ca3b017ebc4e06b",
            "PgZfSK3fannK-yvw2dmhLHQjZrV9InbiTKOwF-vE4Gs",
        )
        assertDeleteVector(
            1,
            234,
            MINIMUM_HEX,
            "16488f15f06ce6cf99952ea5c50b6eba631bf9592effcf6e7d75917eb65c0c34",
            "FkiPFfBs5s-ZlS6lxQtuumMb-Vku_89ufXWRfrZcDDQ",
        )
    }

    @Test
    fun maximumPositiveLongNeedsNoSuccessorAndFitsTheIndependent252ByteFrame() {
        assertDeleteVector(
            Long.MAX_VALUE,
            252,
            MAXIMUM_HEX,
            "223a26bb8fa31e95c50548c4e6cc198edeb4916f7418f205465c24b4655bcd13",
            "Ijomu4-jHpXFBUjE5swZjt60kW90GPIFRlwktGVbzRM",
        )
    }

    @Test
    fun keyAndReadShapeAreExcludedWhileTargetTagScopeAndOperationRemainBound() {
        val original = mobileOwnerDeleteRequest()
        val frame = ComplaintOwnerDeleteFingerprint.frameBytes(original)
        val otherKey = mobileOwnerDeleteRequest(key = MUTATION_OTHER_KEY)
        assertContentEquals(frame, ComplaintOwnerDeleteFingerprint.frameBytes(otherKey))
        val notice = mobileOwnerDeleteRequest(mobileEditTarget(shape = ComplaintEditShape.BODY_ONLY))
        assertContentEquals(frame, ComplaintOwnerDeleteFingerprint.frameBytes(notice))
        val changed =
            listOf(
                mobileOwnerDeleteRequest(scope = MOBILE_EDIT_SCOPE),
                mobileOwnerDeleteRequest(mobileEditTarget(id = MOBILE_REPLY_PARENT)),
                mobileOwnerDeleteRequest(mobileEditTarget(version = 8)),
            )
        val digests = (listOf(original) + changed).map { ComplaintOwnerDeleteFingerprint.of(it).encoded }
        assertEquals(digests.size, digests.toSet().size)
        assertNotEquals(ComplaintEditFingerprint.of(mobileEditRequest()).encoded, digests.first())
        assertNotEquals(ComplaintReportFingerprint.of(mutationReport()).encoded, digests.first())
        assertNotEquals(ComplaintReplyFingerprint.of(mobileReplyRequest()).encoded, digests.first())
    }
}

private fun assertDeleteVector(version: Long, size: Int, hex: String, digest: String, encoded: String) {
    val deletion =
        mobileOwnerDeleteRequest(
            mobileEditTarget(id = "e5555555-5555-4555-8555-555555555555", version = version),
            key = "d4444444-4444-4444-8444-444444444444",
            scope = "b2222222-2222-4222-8222-222222222222",
        )
    val frame = ComplaintOwnerDeleteFingerprint.frameBytes(deletion)
    assertEquals(size, frame.size)
    assertContentEquals(Frames.hexBytes(hex), frame)
    val fingerprint = ComplaintOwnerDeleteFingerprint.of(deletion)
    assertContentEquals(Frames.hexBytes(digest), fingerprint.bytes())
    assertEquals(encoded, fingerprint.encoded)
    assertEquals(1, fingerprint.version)
    frame.fill(0)
    fingerprint.bytes().fill(0)
    assertContentEquals(Frames.hexBytes(hex), ComplaintOwnerDeleteFingerprint.frameBytes(deletion))
    assertContentEquals(Frames.hexBytes(digest), fingerprint.bytes())
    assertEquals("ComplaintOwnerDeleteFingerprint(redacted)", fingerprint.toString())
}

// Copied from the independently frozen owner-delete REQUEST-VECTORS.json, not computed by mobile code.
private const val ORDINARY_HEX =
    "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e74000000010000000644454c45544500000017" +
        "2f6170692f76312f636f6d706c61696e74732f7b69647d0000000c4f574e45525f44454c4554450000002462323232323232322d32323232" +
        "2d343232322d383232322d323232323232323232323232000000010000002465353535353535352d353535352d343535352d383535352d" +
        "3535353535353535353535350000003422636f6d706c61696e742d65353535353535352d353535352d343535352d383535352d35353535" +
        "35353535353535352d76343222"
private const val MINIMUM_HEX =
    "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e74000000010000000644454c45544500000017" +
        "2f6170692f76312f636f6d706c61696e74732f7b69647d0000000c4f574e45525f44454c4554450000002462323232323232322d32323232" +
        "2d343232322d383232322d323232323232323232323232000000010000002465353535353535352d353535352d343535352d383535352d" +
        "3535353535353535353535350000003322636f6d706c61696e742d65353535353535352d353535352d343535352d383535352d35353535" +
        "35353535353535352d763122"
private const val MAXIMUM_HEX =
    "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e74000000010000000644454c45544500000017" +
        "2f6170692f76312f636f6d706c61696e74732f7b69647d0000000c4f574e45525f44454c4554450000002462323232323232322d32323232" +
        "2d343232322d383232322d323232323232323232323232000000010000002465353535353535352d353535352d343535352d383535352d" +
        "3535353535353535353535350000004522636f6d706c61696e742d65353535353535352d353535352d343535352d383535352d35353535" +
        "35353535353535352d763932323333373230333638353437373538303722"
