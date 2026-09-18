package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.test.assertNotNull
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Existing real-task/driven-callback fixture; no task is resumed and no HTTP claim is made. */
internal fun withIosMutationGuard(block: (IosSessionGuardFixture) -> Unit) {
    val fixture =
        IosSessionGuardFixture(
            IosComplaintMutationPolicy(assertNotNull(iosComplaintMutationTarget(Url(IOS_MUTATION_CREATE_URL)))),
        )
    try {
        block(fixture)
    } finally {
        fixture.close()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosMutationTestRequest(
    route: ComplaintMutationRoute = ComplaintMutationRoute.CREATE,
    size: Int = 1,
    url: String = iosMutationTestUrl(route),
): NSMutableURLRequest =
    NSMutableURLRequest.requestWithURL(assertNotNull(NSURL.URLWithString(url))).apply {
        setHTTPMethod("POST")
        setHTTPBody(iosSessionTestData(size))
        mutationTestHeaders(route).forEach { (name, value) -> setValue(value, name) }
    }

internal fun iosMutationTestUrl(route: ComplaintMutationRoute): String =
    when (route) {
        ComplaintMutationRoute.CREATE -> IOS_MUTATION_CREATE_URL
        ComplaintMutationRoute.REPLY -> "$IOS_MUTATION_CREATE_URL/$MUTATION_TEST_PARENT${Policy.REPLIES_SUFFIX}"
        ComplaintMutationRoute.STATUS -> IOS_MUTATION_STATUS_URL
    }

internal const val IOS_MUTATION_CREATE_URL = "https://example.invalid" + Policy.CREATE_PATH
internal const val IOS_MUTATION_STATUS_URL = "https://example.invalid" + Policy.STATUS_PATH
