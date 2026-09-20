package me.manga.kira.data.complaint.backend

internal class ComplaintReportClientId private constructor(
    val value: String,
) {
    val canonical: String get() = value

    override fun toString(): String = "ComplaintReportClientId(redacted)"

    companion object {
        fun checked(value: String): ComplaintReportClientId? =
            if (reportV4(value)) {
                ComplaintReportClientId(value)
            } else {
                null
            }
    }
}

internal class ComplaintReportKey private constructor(
    val value: String,
) {
    val canonical: String get() = value

    override fun toString(): String = "ComplaintReportKey(redacted)"

    companion object {
        fun checked(value: String): ComplaintReportKey? = if (reportV4(value)) ComplaintReportKey(value) else null
    }
}

/** Syntax only: a test scope must separately match authenticated installation/session state. */
internal class ComplaintReportScope private constructor(
    val value: String,
) {
    override fun toString(): String = "ComplaintReportScope(redacted)"

    companion object {
        fun checked(value: String): ComplaintReportScope? =
            if (value == LIVE_SCOPE || reportV4(value)) ComplaintReportScope(value) else null
    }
}

/** Canonical comparison values only; neither authentication, allocation nor receipt authority. */
internal class ComplaintReportIdentity private constructor(
    val clientId: ComplaintReportClientId,
    val key: ComplaintReportKey,
    val dataScope: ComplaintReportScope,
) {
    val dataScopeId: String get() = dataScope.value

    override fun toString(): String = "ComplaintReportIdentity(redacted)"

    companion object {
        fun checked(
            clientId: String,
            key: String,
            dataScopeId: String,
        ): ComplaintReportIdentity? {
            val checkedId = ComplaintReportClientId.checked(clientId)
            val checkedKey = ComplaintReportKey.checked(key)
            val scope = ComplaintReportScope.checked(dataScopeId)
            return if (checkedId == null || checkedKey == null || scope == null) {
                null
            } else {
                ComplaintReportIdentity(checkedId, checkedKey, scope)
            }
        }
    }
}

private fun reportV4(value: String): Boolean = value.length == UUID_LENGTH && CANONICAL_V4.matches(value)

private const val UUID_LENGTH = 36
private const val LIVE_SCOPE = "00000000-0000-0000-0000-000000000000"
private val CANONICAL_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
