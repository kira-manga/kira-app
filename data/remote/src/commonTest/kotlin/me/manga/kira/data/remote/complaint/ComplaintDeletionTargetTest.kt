package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

class ComplaintDeletionTargetTest {
    @Test
    fun exactOriginPrefixAndDeleteAllRouteAreTheOnlyNativeTarget() {
        val url = BASE + Policy.PATH
        val target = assertNotNull(ComplaintDeletionTarget.checked(Url(url)))
        assertTrue(target.matches(url))
        listOf(
            "$url?",
            "$url?key=synthetic",
            "$url/",
            "$url/other",
            "$url#fragment",
            url.replace("https://", "http://"),
            url.replace(":9443", ""),
            url.replace("example.invalid", "elsewhere.invalid"),
            url.replace("base_1/v2/", ""),
            BASE + "/api/v1/installations/session",
            BASE + "/api/v1/installations/bootstrap",
            BASE + "/api/v1/installations",
            BASE + "/api/v1/complaints/operations/status",
        ).forEach { assertFalse(target.matches(it), it) }
        assertEquals("ComplaintDeletionTarget(redacted)", target.toString())
    }

    @Test
    fun configurationRejectsAliasesCredentialsAndOtherRoutesBeforeNativeNormalization() {
        listOf(
            "https://example.invalid/bad/../api/v1/installations/delete-all",
            "https://example.invalid/%2e/api/v1/installations/delete-all",
            "https://example.invalid//api/v1/installations/delete-all",
            "https://user@example.invalid/api/v1/installations/delete-all",
            BASE + Policy.PATH + "?",
            BASE + Policy.PATH + "/",
            BASE + "/api/v1/installations/session",
            BASE + "/api/v1/complaints",
        ).forEach { assertNull(ComplaintDeletionTarget.checked(Url(it)), it) }
    }

    private companion object {
        const val BASE = "https://example.invalid:9443/base_1/v2"
    }
}
