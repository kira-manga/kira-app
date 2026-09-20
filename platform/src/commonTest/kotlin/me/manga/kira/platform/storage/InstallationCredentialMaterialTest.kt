package me.manga.kira.platform.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstallationCredentialMaterialTest {
    @Test
    fun canonicalVersionAndVariantAreRequiredForInstallationIds() {
        val invalidIds =
            listOf(
                "",
                InstallationValueFixtures.ID.uppercase().replace('1', 'A'),
                InstallationValueFixtures.ID.replace("4111", "3111"),
                InstallationValueFixtures.ID.replace("8111", "7111"),
                InstallationValueFixtures.ID.replace("8111", "c111"),
                InstallationValueFixtures.ID.replace("-", ""),
                " ${InstallationValueFixtures.ID}",
                InstallationValueFixtures.LIVE_SCOPE,
            )
        invalidIds.forEach { id ->
            assertIssue(InstallationValueIssue.INSTALLATION_ID, InstallationValueFixtures.material(id = id))
        }
        listOf('8', '9', 'a', 'b').forEach { variant ->
            InstallationValueFixtures
                .material(id = InstallationValueFixtures.ID.replace("8111", "${variant}111"))
                .valid()
        }
    }

    @Test
    fun scopeMustBeExplicitLiveSentinelOrCanonicalVersionFour() {
        assertEquals(InstallationValueFixtures.LIVE_SCOPE, InstallationValueFixtures.material().valid().dataScopeId)
        assertEquals(
            InstallationValueFixtures.OTHER_ID,
            InstallationValueFixtures.material(scope = InstallationValueFixtures.OTHER_ID).valid().dataScopeId,
        )
        listOf("", "live", "null", InstallationValueFixtures.OTHER_ID.uppercase()).forEach { scope ->
            assertIssue(InstallationValueIssue.SCOPE, InstallationValueFixtures.material(scope = scope))
        }
        assertIssue(
            InstallationValueIssue.SCOPE,
            InstallationValueFixtures.material(scope = InstallationValueFixtures.ID.replace("4111", "5111")),
        )
    }

    @Test
    fun onlyExactShippingPlatformNamesAreAccepted() {
        InstallationPlatform.entries.forEach { platform ->
            assertEquals(platform, InstallationValueFixtures.material(platform = platform.name).valid().platform)
        }
        listOf("", "DESKTOP", "UNKNOWN", "android", "IOS ").forEach { platform ->
            assertIssue(InstallationValueIssue.PLATFORM, InstallationValueFixtures.material(platform = platform))
        }
    }

    @Test
    fun secretLengthAlphabetAndPaddingAreCheckedWithoutDecodingAliases() {
        val invalidSecrets =
            listOf(
                "",
                "A".repeat(42),
                "A".repeat(44),
                "A".repeat(42) + "=",
                "A".repeat(41) + "+A",
                "A".repeat(41) + "/A",
                "A".repeat(41) + "\nA",
            )
        invalidSecrets.forEach { secret ->
            assertIssue(InstallationValueIssue.SECRET, InstallationValueFixtures.material(secret = secret))
        }
        InstallationValueFixtures.material(secret = "-_".repeat(21) + "8").valid()
    }

    @Test
    fun allSixtyFourLastSextetsEnforceCanonicalUnusedBits() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        alphabet.forEachIndexed { index, tail ->
            val result = InstallationValueFixtures.material(secret = "A".repeat(42) + tail)
            if (index % 4 == 0) {
                assertIs<InstallationValueResult.Valid<*>>(result)
            } else {
                assertIssue(InstallationValueIssue.SECRET, result)
            }
        }
    }

    @Test
    fun immutableStringsAndDiagnosticsDoNotExposeMaterialOrRejectedInputs() {
        var callerSecret = InstallationValueFixtures.secret
        val checked = InstallationValueFixtures.material(secret = callerSecret)
        val material = checked.valid()
        callerSecret = "B".repeat(42) + "A"
        assertEquals(InstallationValueFixtures.secret, material.secret)
        assertFalse(material.sameAs(InstallationValueFixtures.material(secret = callerSecret).valid()))
        val rejected = InstallationValueFixtures.material(secret = "private rejected content")
        listOf(material.toString(), checked.toString(), rejected.toString()).forEach { diagnostic ->
            assertFalse(diagnostic.contains(InstallationValueFixtures.secret))
            assertFalse(diagnostic.contains(InstallationValueFixtures.ID))
            assertFalse(diagnostic.contains("private rejected content"))
        }
        assertTrue(material.sameAs(InstallationValueFixtures.material().valid()))
    }

    private fun assertIssue(
        expected: InstallationValueIssue,
        result: InstallationValueResult<*>,
    ) {
        assertEquals(expected, assertIs<InstallationValueResult.Invalid>(result).issue)
    }
}
