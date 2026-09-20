package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.test.assertNotNull

/** Reuses the accepted real-task/driven-callback fixture, not a real Darwin HTTP/queue measurement. */
internal fun withIosHistoryGuard(block: (IosSessionGuardFixture) -> Unit) {
    val fixture =
        IosSessionGuardFixture(
            IosComplaintInstallationPolicy.History(assertNotNull(iosComplaintHistoryTarget(Url(IOS_HISTORY_TEST_URL)))),
        )
    try {
        block(fixture)
    } finally {
        fixture.close()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosHistoryTestRequest(url: String = IOS_HISTORY_FIRST_PAGE): NSMutableURLRequest =
    NSMutableURLRequest.requestWithURL(assertNotNull(NSURL.URLWithString(url))).apply {
        setHTTPMethod("GET")
        setValue("identity", "Accept-Encoding")
        setValue(IOS_HISTORY_TEST_AUTHORIZATION, "Authorization")
    }

@OptIn(ExperimentalForeignApi::class)
internal fun IosSessionGuardFixture.admitHistory(
    task: NSURLSessionDataTask,
    headers: Map<String, String> = emptyMap(),
    url: String = IOS_HISTORY_FIRST_PAGE,
    status: Int = 200,
): Boolean = admit(task, headers, url, status)

internal const val IOS_HISTORY_TEST_URL = "https://example.invalid/api/v1/complaints"
internal const val IOS_HISTORY_FIRST_PAGE = "$IOS_HISTORY_TEST_URL?limit=50"
internal const val IOS_HISTORY_TEST_AUTHORIZATION = "Bearer synthetic.header.signature"
