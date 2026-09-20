package me.manga.kira.platform.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/** iOS OS-backed randomness; no bytes are consumed unless SecRandomCopyBytes reports success. */
@OptIn(ExperimentalForeignApi::class)
class IosInstallationCredentialMaterialGenerator : InstallationCredentialMaterialGenerator {
    /** Pins only during the native call; the shared path owns and discards the temporary buffer. */
    override fun generate(dataScopeId: String): InstallationMaterialGenerationResult =
        generateInstallationMaterial(dataScopeId, InstallationPlatform.IOS) { bytes ->
            bytes.usePinned { pinned ->
                SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), pinned.addressOf(0)) == errSecSuccess
            }
        }
}
