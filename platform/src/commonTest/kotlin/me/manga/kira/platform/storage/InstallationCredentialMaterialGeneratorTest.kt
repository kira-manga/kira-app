package me.manga.kira.platform.storage

import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class InstallationCredentialMaterialGeneratorTest {
    @Test
    fun hostileHighBitVectorReplacesUuidBitsAndPreservesAllSecretBytes() {
        val vector = entropyVector()
        var calls = 0
        val material =
            generateInstallationMaterial(InstallationValueFixtures.OTHER_ID, InstallationPlatform.IOS) { bytes ->
                calls++
                assertEquals(48, bytes.size)
                vector.copyInto(bytes)
                true
            }.generated()
        assertEquals(1, calls)
        assertEquals("00112233-4455-4f77-bf99-aabbccddeeff", material.installationId)
        assertEquals("-___AwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", material.secret)
        val decodedSecret = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(material.secret)
        assertContentEquals(secretVector(), decodedSecret)
        assertEquals(InstallationValueFixtures.OTHER_ID, material.dataScopeId)
        assertEquals(InstallationPlatform.IOS, material.platform)
    }

    @Test
    fun zeroValuedEntropyIsLegitimateForBothExplicitScopesAndPlatforms() {
        listOf(InstallationValueFixtures.LIVE_SCOPE, InstallationValueFixtures.OTHER_ID).forEach { scope ->
            InstallationPlatform.entries.forEach { platform ->
                val material =
                    generateInstallationMaterial(scope, platform) { bytes ->
                        bytes.fill(0)
                        true
                    }.generated()
                assertEquals("00000000-0000-4000-8000-000000000000", material.installationId)
                assertEquals("A".repeat(43), material.secret)
                assertEquals(scope, material.dataScopeId)
                assertEquals(platform, material.platform)
            }
        }
    }

    @Test
    fun invalidExplicitScopeNeverCallsEntropy() {
        var calls = 0
        val invalid = listOf("", "live", "private invalid scope", InstallationValueFixtures.OTHER_ID.uppercase())
        (invalid + InstallationValueFixtures.ID.replace("4111", "5111")).forEach { scope ->
            val result =
                generateInstallationMaterial(scope, InstallationPlatform.ANDROID) {
                    calls++
                    error("Entropy must not be reached")
                }
            assertSame(InstallationMaterialGenerationResult.InvalidScope, result)
            assertSame(
                InstallationMaterialGenerationResult.InvalidScope,
                assembleInstallationMaterial(scope, InstallationPlatform.ANDROID, entropyVector()),
            )
        }
        assertEquals(0, calls)
    }

    @Test
    fun refusedStatusDiscardsEvenPartiallyFilledMaterial() {
        listOf(false, true).forEach { partial ->
            val buffers = mutableListOf<ByteArray>()
            val result =
                generateInstallationMaterial(InstallationValueFixtures.LIVE_SCOPE, InstallationPlatform.IOS) { bytes ->
                    buffers += bytes
                    if (partial) bytes[0] = 1
                    false
                }
            assertSame(InstallationMaterialGenerationResult.EntropyFailure, result)
            assertContentEquals(ByteArray(48), buffers.single())
        }
    }

    @Test
    fun providerExceptionsDiscardEvenPartiallyFilledMaterial() {
        listOf(false, true).forEach { partial ->
            val buffers = mutableListOf<ByteArray>()
            val result =
                generateInstallationMaterial(
                    InstallationValueFixtures.LIVE_SCOPE,
                    InstallationPlatform.ANDROID,
                ) { bytes ->
                    buffers += bytes
                    if (partial) bytes[0] = 1
                    error("private provider detail")
                }
            assertSame(InstallationMaterialGenerationResult.EntropyFailure, result)
            assertContentEquals(ByteArray(48), buffers.single())
            assertFalse(result.toString().contains("private provider detail"))
        }
    }

    @Test
    fun assemblyRequiresExactLengthAndDoesNotMutateOrRetainInput() {
        val source = entropyVector()
        val original = source.copyOf()
        val material =
            assembleInstallationMaterial(InstallationValueFixtures.LIVE_SCOPE, InstallationPlatform.ANDROID, source)
                .generated()
        assertContentEquals(original, source)
        source.fill(0)
        assertEquals("00112233-4455-4f77-bf99-aabbccddeeff", material.installationId)
        assertEquals("-___AwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", material.secret)
        listOf(0, 16, 47, 49).forEach { size ->
            val result =
                assembleInstallationMaterial(
                    InstallationValueFixtures.LIVE_SCOPE,
                    InstallationPlatform.IOS,
                    ByteArray(size),
                )
            assertSame(InstallationMaterialGenerationResult.EntropyFailure, result)
        }
    }

    @Test
    fun consecutiveCallsOwnIndependentBuffersAndImmutableMaterial() {
        val source = entropyVector()
        val buffers = mutableListOf<ByteArray>()
        val materials =
            List(2) { index ->
                generateInstallationMaterial(
                    InstallationValueFixtures.LIVE_SCOPE,
                    InstallationPlatform.ANDROID,
                ) { bytes ->
                    buffers += bytes
                    source.copyInto(bytes)
                    if (index == 1) bytes[16] = 0
                    true
                }.generated()
            }
        assertNotSame(buffers[0], buffers[1])
        buffers.forEach { bytes ->
            assertContentEquals(ByteArray(48), bytes)
            bytes.fill(42)
        }
        source.fill(42)
        assertEquals("-___AwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", materials[0].secret)
        assertEquals("AP__AwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", materials[1].secret)
    }

    @Test
    fun cancellationEscapesUnchangedAndDiscardsTemporaryBytes() {
        val cancelled = CancellationException("synthetic cancellation")
        val buffers = mutableListOf<ByteArray>()
        val thrown =
            assertFailsWith<CancellationException> {
                generateInstallationMaterial(InstallationValueFixtures.LIVE_SCOPE, InstallationPlatform.IOS) { bytes ->
                    buffers += bytes
                    bytes.fill(1)
                    throw cancelled
                }
            }
        assertSame(cancelled, thrown)
        assertContentEquals(ByteArray(48), buffers.single())
    }

    @Test
    fun realGenerationAndFailureDiagnosticsRemainRedacted() {
        val generated =
            generateInstallationMaterial(InstallationValueFixtures.OTHER_ID, InstallationPlatform.ANDROID) { bytes ->
                entropyVector().copyInto(bytes)
                true
            }
        val material = generated.generated()
        val rejected =
            generateInstallationMaterial("private invalid scope", InstallationPlatform.ANDROID) { error("Unreachable") }
        val failure =
            generateInstallationMaterial(InstallationValueFixtures.OTHER_ID, InstallationPlatform.ANDROID) {
                throw IllegalStateException("private provider detail")
            }
        val forbidden =
            listOf(
                material.installationId,
                material.secret,
                material.dataScopeId,
                "private invalid scope",
                "private provider detail",
            )
        listOf(generated.toString(), material.toString(), rejected.toString(), failure.toString()).forEach { text ->
            forbidden.forEach { privateText -> assertFalse(text.contains(privateText)) }
        }
    }

    // Octets6/8 are ff: merely OR-ing version/variant bits cannot pass the fixed expected UUID.
    private fun entropyVector(): ByteArray =
        byteArrayOf(
            0x00,
            0x11,
            0x22,
            0x33,
            0x44,
            0x55,
            0xff.toByte(),
            0x77,
            0xff.toByte(),
            0x99.toByte(),
            0xaa.toByte(),
            0xbb.toByte(),
            0xcc.toByte(),
            0xdd.toByte(),
            0xee.toByte(),
            0xff.toByte(),
        ) + secretVector()

    // fb ff ff yields -___; the remaining ascending bytes make truncation/reordering observable.
    private fun secretVector(): ByteArray =
        ByteArray(32) { it.toByte() }.also { bytes ->
            bytes[0] = 0xfb.toByte()
            bytes[1] = 0xff.toByte()
            bytes[2] = 0xff.toByte()
        }

    private fun InstallationMaterialGenerationResult.generated(): InstallationCredentialMaterial =
        assertIs<InstallationMaterialGenerationResult.Generated>(this).material
}
