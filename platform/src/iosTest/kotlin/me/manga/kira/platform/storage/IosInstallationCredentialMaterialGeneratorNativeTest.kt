package me.manga.kira.platform.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** One real OS-entropy call with synthetic scope; no persistence, network or randomness statistics. */
class IosInstallationCredentialMaterialGeneratorNativeTest {
    @Test
    fun osEntropyProducesCanonicalIosMaterialForExplicitSyntheticScope() {
        val generated =
            assertIs<InstallationMaterialGenerationResult.Generated>(
                IosInstallationCredentialMaterialGenerator().generate(SYNTHETIC_SCOPE),
                "OS entropy must produce a generated result",
            )
        val material = generated.material
        assertEquals(InstallationPlatform.IOS, material.platform)
        assertTrue(material.dataScopeId == SYNTHETIC_SCOPE, "Scope must remain explicit")
        assertTrue(UUID_V4.matches(material.installationId), "Installation ID must be canonical UUIDv4")
        assertEquals(ENCODED_SECRET_LENGTH, material.secret.length)
        assertTrue(CANONICAL_SECRET.matches(material.secret), "Secret must be canonical unpadded base64url")
    }

    private companion object {
        const val SYNTHETIC_SCOPE = "b3e2d1c0-6f54-4a32-8b10-2468ace01357"
        const val ENCODED_SECRET_LENGTH = 43
        val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        val CANONICAL_SECRET = Regex("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
    }
}
