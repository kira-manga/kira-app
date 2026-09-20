package me.manga.kira.di

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.CancellationException
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.ComplaintBackendEndpoint
import me.manga.kira.sources.runtime.GeneratedSourceRemoteConfig

/** Build-supplied evidence references, not proof that an operator/deployment was approved. */
internal data class ComplaintBackendLaunchBinding(
    val approvedRecordSha256: String,
    val deploymentSha256: String,
    val buildInputsSha256: String,
    val iosDefaultAccessGroup: String = "",
    val iosAccessGroupEvidenceSha256: String = "",
) {
    override fun toString(): String = "ComplaintBackendLaunchBinding(redacted)"
}

/** Immutable generated input snapshot. No preferences, remote discovery, or Boolean enable switch. */
internal data class ComplaintBackendLaunchInputs(
    val record: String,
    val sourceBackend: String,
    val binding: ComplaintBackendLaunchBinding,
) {
    override fun toString(): String = "ComplaintBackendLaunchInputs(redacted)"
}

internal enum class ComplaintBackendPlatform { ANDROID, IOS, DESKTOP }

/** Native host facts, never a complaint feature flag; ordinary Debug cannot be provisioned here. */
internal data class ComplaintBackendRuntime(
    val platform: ComplaintBackendPlatform,
    val applicationId: String,
    val isDebug: Boolean,
)

/** Only the checked selector creates this fixed endpoint/scope input for the existing assembler. */
internal class ComplaintBackendTarget private constructor(
    val sourceBackend: String,
    val dataScopeId: String,
    val iosDefaultAccessGroup: String,
) {
    override fun toString(): String = "ComplaintBackendTarget(redacted)"

    companion object {
        fun checked(inputs: ComplaintBackendLaunchInputs, runtime: ComplaintBackendRuntime): ComplaintBackendTarget? {
            val fields = readComplaintLaunchFields(inputs.record) ?: return null
            if (fields.getValue("platform") != runtime.platform.name || fields.getValue("contract") != "1") return null
            val base = fields.getValue("sourceBackend")
            val scope = fields.getValue("dataScopeId")
            if (base != inputs.sourceBackend || !launchScopeMatches(fields.getValue("mode"), scope)) return null
            if (ComplaintBackendEndpoint.checked(base, scope) == null || !launchBindingMatches(inputs, fields)) return null
            if (!launchNativeBindingMatches(inputs.binding, fields, runtime.platform)) return null
            return ComplaintBackendTarget(base, scope, fields.getValue("iosDefaultAccessGroup"))
        }
    }
}

/**
 * The one production/fixture selection boundary. Structure and exact input equality are software
 * checks, not external deployment, artifact, signing, native-drain, or cutover acceptance.
 * Disabled input and unsupported native hosts never invoke configuration-dependent allocation.
 */
@Suppress("TooGenericExceptionCaught")
internal fun <T> selectComplaintBackendCandidate(
    readInputs: () -> ComplaintBackendLaunchInputs = ::generatedComplaintBackendLaunchInputs,
    runtime: () -> ComplaintBackendRuntime = {
        ComplaintBackendRuntime(ComplaintBackendPlatform.DESKTOP, "", isDebug = true)
    },
    allocate: (ComplaintBackendTarget) -> AppResult<T>,
): AppResult<T> {
    val target = try {
        val inputs = readInputs()
        if (inputs.record.isEmpty() || inputs.record == COMPLAINT_LAUNCH_DISABLED) return backendGraphUnavailable()
        val host = runtime()
        if (host.isDebug || host.platform == ComplaintBackendPlatform.DESKTOP || host.applicationId != STORE_APP_ID) {
            return backendGraphUnavailable()
        }
        ComplaintBackendTarget.checked(inputs, host) ?: return backendGraphUnavailable()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return backendGraphUnavailable()
    }
    return allocate(target)
}

private fun generatedComplaintBackendLaunchInputs(): ComplaintBackendLaunchInputs =
    ComplaintBackendLaunchInputs(
        record = GeneratedSourceRemoteConfig.COMPLAINT_LAUNCH_RECORD,
        sourceBackend = GeneratedSourceRemoteConfig.BASE_URL,
        binding = ComplaintBackendLaunchBinding(
            approvedRecordSha256 = GeneratedSourceRemoteConfig.COMPLAINT_APPROVED_LAUNCH_SHA256,
            deploymentSha256 = GeneratedSourceRemoteConfig.COMPLAINT_DEPLOYMENT_SHA256,
            buildInputsSha256 = GeneratedSourceRemoteConfig.COMPLAINT_BUILD_INPUTS_SHA256,
            iosDefaultAccessGroup = GeneratedSourceRemoteConfig.COMPLAINT_IOS_DEFAULT_ACCESS_GROUP,
            iosAccessGroupEvidenceSha256 = GeneratedSourceRemoteConfig.COMPLAINT_IOS_ACCESS_GROUP_EVIDENCE_SHA256,
        ),
    )

/** Fixed ordered lines, no escaping or duplicate/unknown fields; SHA-256 binds these exact bytes. */
private fun readComplaintLaunchFields(record: String): Map<String, String>? {
    if (record.length !in 1..MAX_LAUNCH_CHARACTERS || record.any { it != '\n' && it !in ' '..'~' }) return null
    val lines = record.split('\n')
    if (lines.size != LAUNCH_FIELDS.size + 1 || lines.first() != COMPLAINT_LAUNCH_FORMAT) return null
    val fields = mutableMapOf<String, String>()
    LAUNCH_FIELDS.forEachIndexed { index, key ->
        val prefix = "$key="
        val line = lines[index + 1]
        if (!line.startsWith(prefix)) return null
        fields[key] = line.removePrefix(prefix)
    }
    return fields
}

private fun launchScopeMatches(mode: String, scope: String): Boolean = when (mode) {
    "LIVE" -> scope == LIVE_SCOPE
    "TEST" -> TEST_SCOPE.matches(scope)
    else -> false
}

private fun launchBindingMatches(inputs: ComplaintBackendLaunchInputs, fields: Map<String, String>): Boolean {
    val binding = inputs.binding
    if (!SHA256_HEX.matches(binding.approvedRecordSha256) || !SHA256_HEX.matches(binding.deploymentSha256) ||
        !SHA256_HEX.matches(binding.buildInputsSha256)
    ) return false
    if (fields.getValue("deploymentSha256") != binding.deploymentSha256 ||
        fields.getValue("buildInputsSha256") != binding.buildInputsSha256
    ) return false
    return complaintLaunchSha256(inputs.record) == binding.approvedRecordSha256
}

private fun launchNativeBindingMatches(
    binding: ComplaintBackendLaunchBinding,
    fields: Map<String, String>,
    platform: ComplaintBackendPlatform,
): Boolean {
    val group = fields.getValue("iosDefaultAccessGroup")
    val evidence = fields.getValue("iosAccessGroupEvidenceSha256")
    if (group != binding.iosDefaultAccessGroup || evidence != binding.iosAccessGroupEvidenceSha256) return false
    return when (platform) {
        ComplaintBackendPlatform.IOS ->
            ACCESS_GROUP.matches(group) && group.split('.').all { it.isNotEmpty() } && SHA256_HEX.matches(evidence)
        ComplaintBackendPlatform.ANDROID -> group.isEmpty() && evidence.isEmpty()
        ComplaintBackendPlatform.DESKTOP -> false
    }
}

internal fun complaintLaunchSha256(record: String): String =
    CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(record.encodeToByteArray()).joinToString("") {
        it.toUByte().toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0')
    }

internal const val COMPLAINT_LAUNCH_DISABLED = "Disabled"
private const val COMPLAINT_LAUNCH_FORMAT = "kira-complaint-launch-v1"
private const val STORE_APP_ID = "me.manga.kira"
private const val MAX_LAUNCH_CHARACTERS = 4_096
private const val LIVE_SCOPE = "00000000-0000-0000-0000-000000000000"
private const val HEX_RADIX = 16
private const val HEX_BYTE_WIDTH = 2
private val TEST_SCOPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val ACCESS_GROUP = Regex("[A-Za-z0-9][A-Za-z0-9.-]{1,254}")
private val LAUNCH_FIELDS = listOf(
    "platform", "sourceBackend", "contract", "mode", "dataScopeId", "deploymentSha256", "buildInputsSha256",
    "iosDefaultAccessGroup", "iosAccessGroupEvidenceSha256",
)
