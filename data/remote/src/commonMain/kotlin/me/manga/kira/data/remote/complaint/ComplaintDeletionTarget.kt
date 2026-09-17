package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

/** Reuses only enrollment's checked origin/base grammar, never its bootstrap/enrollment authority. */
internal class ComplaintDeletionTarget private constructor(
    private val base: ComplaintEnrollmentTarget,
) {
    fun matches(value: String): Boolean = enrollmentBase(value)?.let(base::matchesEnrollment) == true

    override fun toString(): String = "ComplaintDeletionTarget(redacted)"

    companion object {
        private const val MAX_URL_CHARACTERS = 2_048
        private const val DELETE_SUFFIX = "/delete-all"

        fun checked(url: Url): ComplaintDeletionTarget? =
            enrollmentBase(url.toString())
                ?.let { ComplaintEnrollmentTarget.checked(Url(it)) }
                ?.let(::ComplaintDeletionTarget)

        private fun enrollmentBase(value: String): String? =
            value
                .takeIf {
                    it.length <= MAX_URL_CHARACTERS &&
                        it.all { character -> character in '!'..'~' && character !in "\\@?#" } &&
                        it.endsWith(Policy.PATH)
                }?.removeSuffix(DELETE_SUFFIX)
    }
}
