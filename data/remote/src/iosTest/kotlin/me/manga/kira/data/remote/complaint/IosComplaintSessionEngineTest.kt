package me.manga.kira.data.remote.complaint

import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.coroutines.Job
import platform.Foundation.HTTPShouldHandleCookies
import platform.Foundation.NSURLAuthenticationMethodDefault
import platform.Foundation.NSURLAuthenticationMethodHTTPBasic
import platform.Foundation.NSURLAuthenticationMethodHTTPDigest
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintSessionEngineTest {
    @Test
    fun invalidTargetsDoNotReturnAnEngineOwner() {
        listOf(
            "http://example.invalid/api/v1/installations/session",
            "$IOS_SESSION_TEST_URL?extra=1",
            "$IOS_SESSION_TEST_URL#extra",
            "$IOS_SESSION_TEST_URL/extra",
        ).forEach { value ->
            val owner = createIosComplaintSessionEngineOwner(Url(value))
            try {
                assertNull(owner)
            } finally {
                owner?.close()
            }
        }
    }

    @Test
    fun ephemeralConfigurationHasNoNativeCacheCookieOrCredentialStore() {
        val configuration = iosComplaintSessionConfiguration()
        assertNull(configuration.identifier)
        assertNull(configuration.URLCache)
        assertNull(configuration.HTTPCookieStorage)
        assertNull(configuration.URLCredentialStorage)
        assertFalse(configuration.HTTPShouldSetCookies)
        assertEquals(NSURLRequestReloadIgnoringLocalCacheData, configuration.requestCachePolicy)
        assertEquals(1L, configuration.HTTPMaximumConnectionsPerHost)
    }

    @Test
    fun nativeRequestChecksRefuseTargetMethodEncodingAndCredentialChanges() =
        withIosSessionGuard { fixture ->
            val valid = iosSessionTestRequest()
            fixture.guard.prepare(valid)
            assertFalse(valid.HTTPShouldHandleCookies)
            assertEquals(NSURLRequestReloadIgnoringLocalCacheData, valid.cachePolicy)
            assertFails { fixture.guard.prepare(iosSessionTestRequest("$IOS_SESSION_TEST_URL?extra=1")) }
            assertFails { fixture.guard.prepare(iosSessionTestRequest().apply { setHTTPMethod("GET") }) }
            assertFails {
                fixture.guard.prepare(iosSessionTestRequest().apply { setValue("gzip", "Accept-Encoding") })
            }
            listOf("Cookie", "Authorization", "Proxy-Authorization").forEach { header ->
                assertFails {
                    fixture.guard.prepare(iosSessionTestRequest().apply { setValue("synthetic-only", header) })
                }
            }
        }

    @Test
    fun trustUsesPlatformHandlingWhileHttpAuthenticationDeclinesCredentials() {
        assertEquals(
            NSURLSessionAuthChallengePerformDefaultHandling,
            iosComplaintSessionChallengeDisposition(NSURLAuthenticationMethodServerTrust),
        )
        listOf(
            NSURLAuthenticationMethodHTTPBasic,
            NSURLAuthenticationMethodHTTPDigest,
            NSURLAuthenticationMethodDefault,
        ).forEach { method ->
            assertEquals(NSURLSessionAuthChallengeUseCredential, iosComplaintSessionChallengeDisposition(method))
        }
    }

    @Test
    fun borrowingClientCloseLeavesRealDarwinEngineAliveUntilOwnerCloses() =
        withIosSessionEngineOwner { first ->
            withIosSessionEngineOwner { independent ->
                val client = HttpClient(first.engine)
                try {
                    client.close()
                    assertTrue(assertNotNull(first.engine.coroutineContext[Job]).isActive)
                    first.close()
                    first.close()
                    assertFalse(assertNotNull(first.engine.coroutineContext[Job]).isActive)
                    assertTrue(assertNotNull(independent.engine.coroutineContext[Job]).isActive)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun redirectionCallbackIsForwardedToKtorsTerminalRedirectPolicy() =
        withIosSessionGuard { fixture ->
            val next = iosSessionTestRequest("https://elsewhere.invalid/api/v1/installations/session")
            var followed: NSURLRequest? = next
            fixture.delegate.URLSession(
                fixture.session,
                fixture.task(),
                fixture.response(),
                next,
            ) { followed = it }
            assertNull(followed)
        }
}
