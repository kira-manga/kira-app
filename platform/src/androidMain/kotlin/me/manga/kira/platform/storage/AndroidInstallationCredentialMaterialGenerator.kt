package me.manga.kira.platform.storage

import java.security.SecureRandom

/** Android OS-backed randomness, with no supplied seed, device identifier or fallback source. */
class AndroidInstallationCredentialMaterialGenerator : InstallationCredentialMaterialGenerator {
    /** Entropy construction and acquisition occur only after the shared explicit-scope check. */
    override fun generate(dataScopeId: String): InstallationMaterialGenerationResult =
        generateInstallationMaterial(dataScopeId, InstallationPlatform.ANDROID) { bytes ->
            SecureRandom().nextBytes(bytes)
            true
        }
}
