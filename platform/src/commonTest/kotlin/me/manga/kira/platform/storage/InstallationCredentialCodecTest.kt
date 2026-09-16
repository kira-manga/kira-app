package me.manga.kira.platform.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class InstallationCredentialCodecTest {
    @Test
    fun canonicalBytesAndReadBackCoverEveryState() {
        InstallationCredentialState.entries.forEach { state ->
            val key = if (state == InstallationCredentialState.DELETION_PENDING) InstallationValueFixtures.KEY else null
            val record = InstallationValueFixtures.record(version = 7, generation = 8, state = state, key = key).valid()
            val keyToken = key?.let { "\"$it\"" } ?: "null"
            val expected =
                canonical
                    .replace("\"state\":\"ACTIVE\"", "\"state\":\"${state.name}\"")
                    .replace("\"pendingDeletionKey\":null", "\"pendingDeletionKey\":$keyToken")
                    .encodeToByteArray()
            val encoded = InstallationCredentialCodec.encode(record).codecValue().copyBytes()
            assertContentEquals(expected, encoded)
            assertTrue(record.sameAs(InstallationCredentialCodec.decode(expected).codecValue()))
            assertTrue(record.sameAs(InstallationCredentialCodec.decode(encoded).codecValue()))
        }
    }

    @Test
    fun iosTestScopeAndPositiveCounterBoundariesHaveExactBytes() {
        val alternateSecret = "-_".repeat(21) + "8"
        val material =
            InstallationValueFixtures
                .material(
                    id = InstallationValueFixtures.KEY,
                    secret = alternateSecret,
                    platform = "IOS",
                    scope = InstallationValueFixtures.OTHER_ID,
                ).valid()
        listOf(1L, Long.MAX_VALUE).forEach { counter ->
            val record =
                InstallationCredentialRecord
                    .checked(1, material, counter, counter, InstallationCredentialState.ACTIVE, null)
                    .valid()
            val expected =
                canonical
                    .replace("\"credentialVersion\":7", "\"credentialVersion\":$counter")
                    .replace("\"localGeneration\":8", "\"localGeneration\":$counter")
                    .replace(InstallationValueFixtures.ID, InstallationValueFixtures.KEY)
                    .replace(InstallationValueFixtures.secret, alternateSecret)
                    .replace("ANDROID", "IOS")
                    .replace(InstallationValueFixtures.LIVE_SCOPE, InstallationValueFixtures.OTHER_ID)
                    .encodeToByteArray()
            assertContentEquals(expected, InstallationCredentialCodec.encode(record).codecValue().copyBytes())
            assertTrue(record.sameAs(InstallationCredentialCodec.decode(expected).codecValue()))
        }
    }

    @Test
    fun rawByteCeilingPrecedesDecodingAndEmptyNeverMeansMissing() {
        listOf(byteArrayOf(), byteArrayOf('{'.code.toByte()), ByteArray(2048) { 0xff.toByte() }).forEach { bytes ->
            assertSame(InstallationCodecResult.Corrupt, InstallationCredentialCodec.decode(bytes))
        }
        assertSame(
            InstallationCodecResult.TooLarge,
            InstallationCredentialCodec.decode(ByteArray(2049) { 0xff.toByte() }),
        )
        assertCorrupt(canonical.dropLast(1))
        assertCorrupt(canonical.padEnd(2048, ' '))
        val paddedOversize = canonical.padEnd(2049).encodeToByteArray()
        assertSame(InstallationCodecResult.TooLarge, InstallationCredentialCodec.decode(paddedOversize))
    }

    @Test
    fun malformedUtf8NonAsciiAndBomCannotBeRepaired() {
        val encoded = canonical.encodeToByteArray()
        val secretOffset = canonical.indexOf(InstallationValueFixtures.secret)
        val malformed =
            listOf(
                byteArrayOf(0x80.toByte()),
                byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
                byteArrayOf(0xe2.toByte(), 0x82.toByte()),
            )
        malformed.forEach { invalid ->
            val bytes = encoded.copyOf()
            invalid.copyInto(bytes, secretOffset)
            assertSame(InstallationCodecResult.Corrupt, InstallationCredentialCodec.decode(bytes))
        }
        listOf("\ufeff$canonical", canonical.replace("ANDROID", "ANDRÖID"), canonical.replace("ANDROID", "\ufffd"))
            .forEach(::assertCorrupt)
    }

    @Test
    fun closedShapeRejectsMissingExtraDuplicateEscapedAndNestedFields() {
        val deepArray = "[".repeat(500) + "0" + "]".repeat(500)
        listOf(
            canonical.replace("\"schemaVersion\":1,", ""),
            canonical.replace(",\"pendingDeletionKey\":null", ""),
            canonical.dropLast(1) + ",\"extra\":0}",
            canonical.replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"schemaVersion\":1,"),
            canonical.replace("\"schemaVersion\"", "\"\\u0073chemaVersion\""),
            canonical.replace("\"pendingDeletionKey\":null", "\"pendingDeletionKey\":[]"),
            canonical.replace("\"pendingDeletionKey\":null", "\"pendingDeletionKey\":{\"x\":1}"),
            canonical.replace("\"pendingDeletionKey\":null", "\"pendingDeletionKey\":$deepArray"),
            "[$canonical]",
            canonical.replace("\"schemaVersion\":1,", "\"other\":1,"),
        ).forEach(::assertCorrupt)
    }

    @Test
    fun canonicalSpellingRejectsOrderWhitespaceEscapesAndTrailingData() {
        val firstFields = "\"schemaVersion\":1,\"installationId\":\"${InstallationValueFixtures.ID}\""
        val reversedFields = "\"installationId\":\"${InstallationValueFixtures.ID}\",\"schemaVersion\":1"
        listOf(
            " $canonical",
            "$canonical\n",
            "$canonical{}",
            canonical.replace("\":1,", "\": 1,"),
            canonical.replace(firstFields, reversedFields),
            canonical.replace("ANDROID", "\\u0041NDROID"),
            canonical.replace("\"pendingDeletionKey\":null}", "\"pendingDeletionKey\":null,}"),
        ).forEach(::assertCorrupt)
    }

    @Test
    fun schemasAndCountersRejectCoercionOverflowAndNonpositiveValues() {
        listOf("0", "-1", "2", "2147483648", "\"1\"", "null", "true", "1.0", "1e0", "01", "+1").forEach { token ->
            assertCorrupt(canonical.replace("\"schemaVersion\":1", "\"schemaVersion\":$token"))
        }
        val counterAliases = listOf("0", "-1", "\"7\"", "null", "true", "7.0", "7e0", "07", "+7")
        (counterAliases + listOf("-9223372036854775808", "9223372036854775808")).forEach { token ->
            assertCorrupt(canonical.replace("\"credentialVersion\":7", "\"credentialVersion\":$token"))
            assertCorrupt(canonical.replace("\"localGeneration\":8", "\"localGeneration\":$token"))
        }
    }

    @Test
    fun checkedIdentityAndSecretRejectNoncanonicalAliases() {
        assertBadStrings(
            "installationId",
            InstallationValueFixtures.ID,
            "",
            InstallationValueFixtures.OTHER_ID.uppercase(),
            InstallationValueFixtures.ID.replace("4111", "3111"),
            InstallationValueFixtures.ID.replace("8111", "c111"),
            InstallationValueFixtures.LIVE_SCOPE,
        )
        assertBadStrings(
            "secret",
            InstallationValueFixtures.secret,
            "",
            "A".repeat(42),
            "A".repeat(44),
            InstallationValueFixtures.secret + "=",
            "A".repeat(42) + "F",
            "A".repeat(41) + "+E",
            "A".repeat(41) + "/E",
        )
    }

    @Test
    fun scopeAndPlatformAreNeverInferredOrNormalized() {
        assertBadStrings(
            "dataScopeId",
            InstallationValueFixtures.LIVE_SCOPE,
            "",
            "live",
            InstallationValueFixtures.OTHER_ID.uppercase(),
            InstallationValueFixtures.ID.replace("4111", "5111"),
        )
        assertBadStrings("platform", "ANDROID", "", "android", "DESKTOP", "IOS ")
    }

    @Test
    fun requiredStringFieldsNeverCoerceNullNumbersOrBooleans() {
        val fields =
            listOf(
                "installationId" to InstallationValueFixtures.ID,
                "secret" to InstallationValueFixtures.secret,
                "platform" to "ANDROID",
                "dataScopeId" to InstallationValueFixtures.LIVE_SCOPE,
                "state" to "ACTIVE",
            )
        fields.forEach { (field, value) ->
            listOf("null", "7", "true").forEach { token ->
                assertCorrupt(canonical.replace("\"$field\":\"$value\"", "\"$field\":$token"))
            }
        }
    }

    @Test
    fun stateAndDeletionKeyMustMatchWithoutDefaults() {
        listOf("DELETION_PENDING", "active", "UNKNOWN", "").forEach { state ->
            assertCorrupt(canonical.replace("\"state\":\"ACTIVE\"", "\"state\":\"$state\""))
        }
        listOf("ACTIVE", "LOCAL_RESET_PENDING").forEach { state ->
            assertCorrupt(
                canonical
                    .replace("\"state\":\"ACTIVE\"", "\"state\":\"$state\"")
                    .replace(
                        "\"pendingDeletionKey\":null",
                        "\"pendingDeletionKey\":\"${InstallationValueFixtures.KEY}\"",
                    ),
            )
        }
        val deletion = canonical.replace("\"state\":\"ACTIVE\"", "\"state\":\"DELETION_PENDING\"")
        val keys =
            listOf("null", "\"null\"", "\"bad-key\"", "7", "true", "\"${InstallationValueFixtures.KEY.uppercase()}\"")
        keys.forEach { key ->
            assertCorrupt(deletion.replace("\"pendingDeletionKey\":null", "\"pendingDeletionKey\":$key"))
        }
    }

    @Test
    fun encodedAndDecodedValuesOwnTheirBytes() {
        val record = InstallationValueFixtures.record(version = 7, generation = 8).valid()
        val encodedResult = InstallationCredentialCodec.encode(record)
        val encoded = encodedResult.codecValue()
        val callerBytes = encoded.copyBytes()
        val decodedResult = InstallationCredentialCodec.decode(callerBytes)
        callerBytes.fill(0)
        assertContentEquals(canonical.encodeToByteArray(), encoded.copyBytes())
        val repeated = InstallationCredentialCodec.encode(record).codecValue().copyBytes()
        assertContentEquals(canonical.encodeToByteArray(), repeated)
        assertTrue(record.sameAs(decodedResult.codecValue()))
        val external = encoded.copyBytes()
        val owned = InstallationEncodedPayload(external)
        external.fill(0)
        assertContentEquals(canonical.encodeToByteArray(), owned.copyBytes())
    }

    @Test
    fun diagnosticsNeverExposeIdentitySecretKeyOrRejectedInput() {
        val record =
            InstallationValueFixtures
                .record()
                .valid()
                .beginDeletion(InstallationValueFixtures.KEY)
                .valid()
        val encodedResult = InstallationCredentialCodec.encode(record)
        val encoded = encodedResult.codecValue()
        val decodedResult = InstallationCredentialCodec.decode(encoded.copyBytes())
        val badInput = canonical.replace("ANDROID", "private rejected content").encodeToByteArray()
        val rejected = InstallationCredentialCodec.decode(badInput)
        assertSame(InstallationCodecResult.Corrupt, rejected)
        val forbidden =
            listOf(
                InstallationValueFixtures.ID,
                InstallationValueFixtures.secret,
                InstallationValueFixtures.KEY,
                "private rejected content",
            )
        listOf(encodedResult.toString(), encoded.toString(), decodedResult.toString(), rejected.toString())
            .forEach { text ->
                forbidden.forEach { privateText -> assertFalse(text.contains(privateText)) }
            }
    }

    private fun assertBadStrings(
        field: String,
        original: String,
        vararg rejected: String,
    ) {
        rejected.forEach { value ->
            assertCorrupt(canonical.replace("\"$field\":\"$original\"", "\"$field\":\"$value\""))
        }
    }

    private fun assertCorrupt(json: String) {
        assertSame(InstallationCodecResult.Corrupt, InstallationCredentialCodec.decode(json.encodeToByteArray()))
    }

    // Independent literal field order/null spelling; neither encoder nor a JSON serializer builds this fixture.
    private val canonical =
        "{\"schemaVersion\":1," +
            "\"installationId\":\"${InstallationValueFixtures.ID}\"," +
            "\"secret\":\"${InstallationValueFixtures.secret}\"," +
            "\"credentialVersion\":7,\"localGeneration\":8," +
            "\"platform\":\"ANDROID\",\"dataScopeId\":\"${InstallationValueFixtures.LIVE_SCOPE}\"," +
            "\"state\":\"ACTIVE\",\"pendingDeletionKey\":null}"
}

internal fun <T> InstallationCodecResult<T>.codecValue(): T =
    when (this) {
        is InstallationCodecResult.Value -> value
        else -> fail("Unexpected content-free codec refusal: $this")
    }
