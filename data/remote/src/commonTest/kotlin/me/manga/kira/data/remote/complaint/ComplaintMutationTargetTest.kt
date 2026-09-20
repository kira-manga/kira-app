package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

class ComplaintMutationTargetTest {
    @Test
    fun exactOriginAndDeploymentPrefixDeriveOnlyTheTwoQuerylessRoutes() {
        val create = BASE + Policy.CREATE_PATH
        val status = BASE + Policy.STATUS_PATH
        val target = assertNotNull(ComplaintMutationTarget.checked(Url(create)))
        assertEquals(ComplaintMutationRoute.CREATE, target.route(create))
        assertEquals(ComplaintMutationRoute.STATUS, target.route(status))
        listOf(
            "$create?limit=50",
            "$create?",
            "$status?operation=OWNER_CREATE",
            "$create/other",
            "$status/",
            BASE + "/api/v1/installations/session",
            create.replace("base_1/v2/", ""),
            status.replace(":9443", ""),
            status.replace("example.invalid", "elsewhere.invalid"),
            status.replace("https://", "http://"),
            "$status#fragment",
        ).forEach { assertNull(target.route(it), it) }
        assertTrue(target.sameRoute(create, create))
        assertFalse(target.sameRoute(create, status))
        assertFalse(target.sameRoute(status, create))
        assertEquals("ComplaintMutationTarget(redacted)", target.toString())
    }

    @Test
    fun statusCannotBecomeTheConfiguredCreateTargetAndPathAliasesStayRejected() {
        listOf(
            BASE + Policy.STATUS_PATH,
            BASE + Policy.CREATE_PATH + "?limit=50",
            BASE + Policy.CREATE_PATH + "/",
            "https://user@example.invalid/api/v1/complaints",
            "https://example.invalid/bad/../api/v1/complaints",
            "https://example.invalid/%2e/api/v1/complaints",
            "https://example.invalid//api/v1/complaints",
        ).forEach { assertNull(ComplaintMutationTarget.checked(Url(it)), it) }
    }

    private companion object {
        const val BASE = "https://example.invalid:9443/base_1/v2"
    }
}
