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
internal fun withIosMutationGuard(
    createUrl: String = IOS_MUTATION_CREATE_URL,
    block: (IosSessionGuardFixture) -> Unit,
) {
    val fixture =
        IosSessionGuardFixture(
            IosComplaintMutationPolicy(assertNotNull(iosComplaintMutationTarget(Url(createUrl)))),
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
    size: Int = mutationTestBodySize(route),
    url: String = iosMutationTestUrl(route),
): NSMutableURLRequest =
    NSMutableURLRequest.requestWithURL(assertNotNull(NSURL.URLWithString(url))).apply {
        setHTTPMethod(route.method)
        setHTTPBody(if (route == ComplaintMutationRoute.OWNER_DELETE && size == 0) null else iosSessionTestData(size))
        mutationTestHeaders(route).forEach { (name, value) -> setValue(value, name) }
    }

internal fun iosMutationTestUrl(route: ComplaintMutationRoute): String =
    when (route) {
        ComplaintMutationRoute.CREATE -> IOS_MUTATION_CREATE_URL
        ComplaintMutationRoute.REPLY -> "$IOS_MUTATION_CREATE_URL/$MUTATION_TEST_PARENT${Policy.REPLIES_SUFFIX}"
        ComplaintMutationRoute.EDIT -> "$IOS_MUTATION_CREATE_URL/$MUTATION_TEST_PARENT${Policy.CONTENT_SUFFIX}"
        ComplaintMutationRoute.OWNER_DELETE -> "$IOS_MUTATION_CREATE_URL/$MUTATION_TEST_PARENT"
        ComplaintMutationRoute.STATUS -> IOS_MUTATION_STATUS_URL
    }

internal const val IOS_MUTATION_CREATE_URL = "https://example.invalid" + Policy.CREATE_PATH
internal const val IOS_MUTATION_STATUS_URL = "https://example.invalid" + Policy.STATUS_PATH
