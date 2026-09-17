package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.test.assertNotNull
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

/** Existing real-task/driven-callback fixture; no task is resumed and no native-network proof is implied. */
internal fun withIosDeletionGuard(block: (IosSessionGuardFixture) -> Unit) {
    val fixture =
        IosSessionGuardFixture(
            IosComplaintDeletionPolicy(assertNotNull(iosComplaintDeletionTarget(Url(IOS_DELETION_URL)))),
        )
    try {
        block(fixture)
    } finally {
        fixture.close()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosDeletionTestRequest(
    size: Int = 1,
    url: String = IOS_DELETION_URL,
): NSMutableURLRequest =
    NSMutableURLRequest.requestWithURL(assertNotNull(NSURL.URLWithString(url))).apply {
        setHTTPMethod("POST")
        setHTTPBody(iosSessionTestData(size))
        deletionTestHeaders().forEach { (name, value) -> setValue(value, name) }
    }

internal const val IOS_DELETION_URL = "https://example.invalid" + Policy.PATH
