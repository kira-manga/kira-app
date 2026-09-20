package me.manga.kira.platform.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CredentialCleanupMarkerCodecTest {
    @Test
    fun canonicalThreeFieldBytesCoverEveryCleanupReason() {
        CredentialCleanupReason.entries.forEach { reason ->
            val unreadable = reason == CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED
            val marker = CredentialCleanupMarker.checked(1, if (unreadable) null else 4, reason).valid()
            val expected = markerJson(if (unreadable) "null" else "4", reason.name).encodeToByteArray()
            val encoded = CredentialCleanupMarkerCodec.encode(marker).codecValue().copyBytes()
            assertContentEquals(expected, encoded)
            assertTrue(marker.sameAs(CredentialCleanupMarkerCodec.decode(expected).codecValue()))
            assertTrue(marker.sameAs(CredentialCleanupMarkerCodec.decode(encoded).codecValue()))
        }
    }

    @Test
    fun positiveGenerationBoundariesHaveExactBytes() {
        listOf(1L, Long.MAX_VALUE).forEach { generation ->
            val marker =
                CredentialCleanupMarker
                    .checked(1, generation, CredentialCleanupReason.USER_RESET_CONFIRMED)
                    .valid()
            val expected = markerJson(generation.toString()).encodeToByteArray()
            assertContentEquals(expected, CredentialCleanupMarkerCodec.encode(marker).codecValue().copyBytes())
            assertTrue(marker.sameAs(CredentialCleanupMarkerCodec.decode(expected).codecValue()))
        }
    }

    @Test
    fun nullGenerationIsRequiredOnlyForUnreadableRecovery() {
        CredentialCleanupReason.entries.forEach { reason ->
            val invalidGeneration = if (reason == CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED) "4" else "null"
            assertCorrupt(markerJson(invalidGeneration, reason.name))
        }
        assertCorrupt(markerJson("\"null\"", "UNREADABLE_RESET_CONFIRMED"))
        assertCorrupt(markerJson("NULL", "UNREADABLE_RESET_CONFIRMED"))
    }

    @Test
    fun exactRawCeilingAndEmptyInputRemainContentFreeRefusals() {
        listOf(byteArrayOf(), byteArrayOf('{'.code.toByte()), ByteArray(512) { 0xff.toByte() }).forEach { bytes ->
            assertSame(InstallationCodecResult.Corrupt, CredentialCleanupMarkerCodec.decode(bytes))
        }
        assertSame(
            InstallationCodecResult.TooLarge,
            CredentialCleanupMarkerCodec.decode(ByteArray(513) { 0xff.toByte() }),
        )
        assertCorrupt(markerJson().dropLast(1))
        assertCorrupt(markerJson().padEnd(512))
        val paddedOversize = markerJson().padEnd(513).encodeToByteArray()
        assertSame(InstallationCodecResult.TooLarge, CredentialCleanupMarkerCodec.decode(paddedOversize))
    }

    @Test
    fun closedSchemaCannotAcquireCredentialFieldsOrNestedContent() {
        val original = markerJson()
        val deepArray = "[".repeat(100) + "0" + "]".repeat(100)
        listOf("installationId", "secret", "pendingDeletionKey", "dataScopeId").forEach { field ->
            assertCorrupt(original.dropLast(1) + ",\"$field\":\"private rejected content\"}")
        }
        listOf(
            original.replace("\"schemaVersion\":1,", ""),
            original.replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"schemaVersion\":1,"),
            original.replace("schemaVersion", "unknown"),
            original.replace("schemaVersion", "\\u0073chemaVersion"),
            original.replace("\"expectedGeneration\":4", "\"expectedGeneration\":[]"),
            original.replace("\"expectedGeneration\":4", "\"expectedGeneration\":{\"x\":4}"),
            original.replace("\"expectedGeneration\":4", "\"expectedGeneration\":$deepArray"),
            "[$original]",
        ).forEach(::assertCorrupt)
    }

    @Test
    fun schemaGenerationAndReasonRefuseCoercionOverflowOrAliases() {
        val original = markerJson()
        listOf("0", "-1", "2", "2147483648", "\"1\"", "null", "true", "1.0", "1e0", "01", "+1").forEach { token ->
            assertCorrupt(original.replace("\"schemaVersion\":1", "\"schemaVersion\":$token"))
        }
        listOf("0", "-1", "9223372036854775808", "\"4\"", "true", "4.0", "4e0", "04", "+4").forEach { token ->
            assertCorrupt(markerJson(token))
        }
        listOf("\"user_reset_confirmed\"", "\"UNKNOWN\"", "null", "4", "false").forEach { token ->
            assertCorrupt(original.replace("\"reason\":\"USER_RESET_CONFIRMED\"", "\"reason\":$token"))
        }
    }

    @Test
    fun strictUtf8OrderEscapesWhitespaceAndTrailingDataAreNotNormalized() {
        val original = markerJson()
        val malformed = original.encodeToByteArray()
        malformed[original.indexOf("USER_RESET_CONFIRMED")] = 0xff.toByte()
        assertSame(InstallationCodecResult.Corrupt, CredentialCleanupMarkerCodec.decode(malformed))
        listOf(
            "\ufeff$original",
            " $original",
            "$original\n",
            "$original{}",
            original.replace("\":1,", "\": 1,"),
            original.replace(
                "\"schemaVersion\":1,\"expectedGeneration\":4",
                "\"expectedGeneration\":4,\"schemaVersion\":1",
            ),
            original.replace("USER", "USÉR"),
            original.replace("USER", "\\u0055SER"),
            original.dropLast(1) + ",}",
        ).forEach(::assertCorrupt)
    }

    @Test
    fun encodedAndDecodedMarkerOwnershipDoesNotExposePayloads() {
        val marker = CredentialCleanupMarker.checked(1, 4, CredentialCleanupReason.USER_RESET_CONFIRMED).valid()
        val encodedResult = CredentialCleanupMarkerCodec.encode(marker)
        val encoded = encodedResult.codecValue()
        val callerBytes = encoded.copyBytes()
        val decodedResult = CredentialCleanupMarkerCodec.decode(callerBytes)
        callerBytes.fill(0)
        assertContentEquals(markerJson().encodeToByteArray(), encoded.copyBytes())
        val repeated = CredentialCleanupMarkerCodec.encode(marker).codecValue().copyBytes()
        assertContentEquals(markerJson().encodeToByteArray(), repeated)
        assertTrue(marker.sameAs(decodedResult.codecValue()))
        val badInput = markerJson().replace("USER_RESET_CONFIRMED", "private rejected content").encodeToByteArray()
        val rejected = CredentialCleanupMarkerCodec.decode(badInput)
        assertSame(InstallationCodecResult.Corrupt, rejected)
        listOf(encodedResult.toString(), encoded.toString(), decodedResult.toString(), rejected.toString())
            .forEach { text ->
                assertFalse(text.contains("USER_RESET_CONFIRMED"))
                assertFalse(text.contains("private rejected content"))
            }
    }

    private fun assertCorrupt(json: String) {
        assertSame(InstallationCodecResult.Corrupt, CredentialCleanupMarkerCodec.decode(json.encodeToByteArray()))
    }

    // Literal field order and null/number spelling, independent of both production codecs.
    private fun markerJson(
        generation: String = "4",
        reason: String = "USER_RESET_CONFIRMED",
    ): String = "{\"schemaVersion\":1,\"expectedGeneration\":$generation,\"reason\":\"$reason\"}"
}
