package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidComplaintHistoryEngineTest {
    @Test
    fun nativeFactoryRejectsWrongTargetsAndKeepsInstallationEntriesClosed() {
        listOf(
            "$ANDROID_HISTORY_TEST_URL?limit=50",
            "$ANDROID_HISTORY_TEST_URL/other",
            "http://example.invalid/api/v1/complaints",
            "https://example.invalid/bad/../api/v1/complaints",
            "https://example.invalid/%2e/api/v1/complaints",
            "https://example.invalid/api/v1/installations",
            "https://example.invalid/api/v1/installations/session",
        ).forEach { assertNoOwner(createAndroidComplaintHistoryEngineOwner(Url(it))) }
        assertNoOwner(createAndroidComplaintSessionEngineOwner(Url(ANDROID_HISTORY_TEST_URL)))
        assertNoOwner(createAndroidComplaintEnrollmentEngineOwner(Url(ANDROID_HISTORY_TEST_URL)))
        val path = "/base_1/v2/api/v1/complaints"
        val target = assertNotNull(androidComplaintHistoryTarget(Url("https://[0:0:0:0:0:0:0:1]:9443$path")))
        assertTrue(target.matches("https://[::1]:9443$path?limit=50&cursor=v1.a.b"))
        assertFalse(target.matches("https://[::1]:9443/api/v1/complaints?limit=50"))
        assertFalse(target.matches("https://[::2]:9443$path?limit=50"))
    }

    @Test
    fun historyPolicyInstallsOnlyItsClosedGuardsAndRetainsNativeIsolation() {
        val resources = AndroidComplaintSessionResources()
        try {
            val target = assertNotNull(androidComplaintHistoryTarget(Url(ANDROID_HISTORY_TEST_URL)))
            val native =
                OkHttpClient
                    .Builder()
                    .retryOnConnectionFailure(true)
                    .followRedirects(true)
                    .addInterceptor { error("Unexpected inherited interceptor") }
                    .addNetworkInterceptor { error("Unexpected inherited network interceptor") }
                    .complaintHistoryPolicy(target, resources)
                    .build()
            assertFalse(native.retryOnConnectionFailure)
            assertFalse(native.followRedirects)
            assertFalse(native.followSslRedirects)
            assertNull(native.cache)
            assertSame(CookieJar.NO_COOKIES, native.cookieJar)
            assertSame(Authenticator.NONE, native.authenticator)
            assertSame(Authenticator.NONE, native.proxyAuthenticator)
            assertSame(resources.dispatcher, native.dispatcher)
            assertSame(resources.pool, native.connectionPool)
            assertTrue(native.interceptors.single() is AndroidComplaintHistoryInterceptor)
            assertTrue(native.networkInterceptors.single() is AndroidComplaintHistoryFollowUpGuard)
        } finally {
            resources.close()
        }
    }

    private fun assertNoOwner(owner: ComplaintSessionEngineOwner?) {
        try {
            assertNull(owner)
        } finally {
            owner?.close()
        }
    }
}
