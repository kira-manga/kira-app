package me.manga.kira.data.remote.complaint

/** Mirrors the accepted session reader's bounded compact-token spelling, without parsing claims. */
internal object ComplaintHistoryRequestHeaders {
    private const val MAX_AUTHORIZATION_CHARACTERS = 4 * 1_024
    private val BEARER = Regex("Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

    fun accepts(
        authorization: List<String>,
        encoding: List<String>,
    ): Boolean =
        authorization.size == 1 &&
            authorization.single().length <= MAX_AUTHORIZATION_CHARACTERS &&
            BEARER.matches(authorization.single()) &&
            encoding == listOf("identity")
}
