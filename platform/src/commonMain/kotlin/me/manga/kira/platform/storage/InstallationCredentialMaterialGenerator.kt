package me.manga.kira.platform.storage

import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64

/** Synchronous OS-random material only: no bootstrap, record creation, persistence or enrollment. */
interface InstallationCredentialMaterialGenerator {
    /** Requires an explicit scope; structural validity is not proof of trusted bootstrap. */
    fun generate(dataScopeId: String): InstallationMaterialGenerationResult
}

/** Provider failures carry neither partial material nor the provider exception. */
sealed interface InstallationMaterialGenerationResult {
    class Generated(
        val material: InstallationCredentialMaterial,
    ) : InstallationMaterialGenerationResult {
        override fun toString(): String = "InstallationMaterialGenerationResult.Generated(redacted)"
    }

    data object InvalidScope : InstallationMaterialGenerationResult

    data object EntropyFailure : InstallationMaterialGenerationResult

    data object Unsupported : InstallationMaterialGenerationResult
}

// The internal seam exercises the same full-fill/status/failure path used by both OS adapters.
// Providers may throw different exception types; map them without retaining messages or causes.
@Suppress("TooGenericExceptionCaught")
internal fun generateInstallationMaterial(
    dataScopeId: String,
    platform: InstallationPlatform,
    fill: (ByteArray) -> Boolean,
): InstallationMaterialGenerationResult {
    if (!canonicalInstallationScope(dataScopeId)) return InstallationMaterialGenerationResult.InvalidScope
    val bytes = ByteArray(RANDOM_MATERIAL_BYTES)
    return try {
        if (fill(bytes)) {
            assembleInstallationMaterial(dataScopeId, platform, bytes)
        } else {
            InstallationMaterialGenerationResult.EntropyFailure
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InstallationMaterialGenerationResult.EntropyFailure
    } finally {
        // Best effort for this temporary buffer only; immutable strings/native copies are not zeroized.
        bytes.fill(0)
    }
}

internal fun assembleInstallationMaterial(
    dataScopeId: String,
    platform: InstallationPlatform,
    bytes: ByteArray,
): InstallationMaterialGenerationResult {
    if (!canonicalInstallationScope(dataScopeId)) return InstallationMaterialGenerationResult.InvalidScope
    return if (bytes.size != RANDOM_MATERIAL_BYTES) {
        InstallationMaterialGenerationResult.EntropyFailure
    } else {
        val checked =
            InstallationCredentialMaterial.checked(
                installationUuid(bytes),
                unpaddedBase64.encode(bytes, startIndex = UUID_BYTES, endIndex = RANDOM_MATERIAL_BYTES),
                platform.name,
                dataScopeId,
            )
        when (checked) {
            is InstallationValueResult.Valid -> InstallationMaterialGenerationResult.Generated(checked.value)
            is InstallationValueResult.Invalid -> InstallationMaterialGenerationResult.EntropyFailure
        }
    }
}

private fun installationUuid(bytes: ByteArray): String =
    buildString(UUID_TEXT_LENGTH) {
        for (index in 0 until UUID_BYTES) {
            when (index) {
                FIRST_HYPHEN, VERSION_INDEX, VARIANT_INDEX, LAST_HYPHEN -> append('-')
            }
            val octet = bytes[index].toInt() and OCTET_MASK
            val value =
                when (index) {
                    VERSION_INDEX -> (octet and NIBBLE_MASK) or VERSION_FOUR_BITS
                    VARIANT_INDEX -> (octet and VARIANT_PAYLOAD_MASK) or VARIANT_BITS
                    else -> octet
                }
            append(HEX[value ushr NIBBLE_BITS])
            append(HEX[value and NIBBLE_MASK])
        }
    }

private const val UUID_BYTES = 16
private const val SECRET_BYTES = 32
private const val RANDOM_MATERIAL_BYTES = UUID_BYTES + SECRET_BYTES
private const val UUID_TEXT_LENGTH = 36
private const val FIRST_HYPHEN = 4
private const val VERSION_INDEX = 6
private const val VARIANT_INDEX = 8
private const val LAST_HYPHEN = 10
private const val OCTET_MASK = 0xff
private const val NIBBLE_MASK = 0x0f
private const val NIBBLE_BITS = 4
private const val VERSION_FOUR_BITS = 0x40
private const val VARIANT_PAYLOAD_MASK = 0x3f
private const val VARIANT_BITS = 0x80
private const val HEX = "0123456789abcdef"
private val unpaddedBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
