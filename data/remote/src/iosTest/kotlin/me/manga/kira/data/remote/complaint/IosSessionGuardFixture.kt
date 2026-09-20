package me.manga.kira.data.remote.complaint

import io.ktor.client.engine.darwin.KtorNSURLSessionDelegate
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.create
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.test.assertNotNull
import platform.Foundation.NSURLSessionDataTask as SessionDataTask

/** Real Foundation tasks/data, driven callbacks and a recording sink; not a Darwin queue measurement. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal class IosSessionGuardFixture(
    policy: IosComplaintInstallationPolicy =
        IosComplaintInstallationPolicy.Session(assertNotNull(iosComplaintSessionTarget(Url(IOS_SESSION_TEST_URL)))),
) {
    val callbacks = RecordingIosSessionCallbacks()
    val guard = IosComplaintSessionReceiveGuard(policy, callbacks)
    val delegate = IosComplaintSessionDelegate(guard, KtorNSURLSessionDelegate())
    val session =
        NSURLSession.sessionWithConfiguration(
            iosComplaintSessionConfiguration(),
            delegate,
            delegateQueue = null,
        )

    fun task(request: NSURLRequest = iosSessionTestRequest()): SessionDataTask = session.dataTaskWithRequest(request)

    fun admit(
        task: SessionDataTask,
        headers: Map<String, String> = emptyMap(),
        url: String = IOS_SESSION_TEST_URL,
        status: Int = HttpStatusCode.OK.value,
    ): Boolean {
        var disposition: NSURLSessionResponseDisposition? = null
        delegate.URLSession(session, task, response(headers, url, status)) { disposition = it }
        return disposition == NSURLSessionResponseAllow
    }

    fun response(
        headers: Map<String, String> = emptyMap(),
        url: String = IOS_SESSION_TEST_URL,
        status: Int = HttpStatusCode.OK.value,
    ): NSHTTPURLResponse =
        NSHTTPURLResponse(
            assertNotNull(NSURL.URLWithString(url)),
            status.toLong(),
            "HTTP/1.1",
            headers.toMap<Any?, String>(),
        )

    fun receive(
        task: SessionDataTask,
        size: Int,
    ) {
        delegate.URLSession(session, task, iosSessionTestData(size))
    }

    fun complete(
        task: NSURLSessionTask,
        error: NSError? = null,
    ) {
        delegate.URLSession(session, task, error)
    }

    fun close() {
        try {
            guard.close(session)
        } finally {
            session.invalidateAndCancel()
        }
    }
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal class RecordingIosSessionCallbacks : IosComplaintSessionCallbacks {
    val forwarded = mutableListOf<NSData>()
    val errors = mutableListOf<NSError?>()
    val forwardedBytes: ULong get() = forwarded.sumOf { it.length }

    override fun received(
        session: NSURLSession,
        task: SessionDataTask,
        data: NSData,
    ) {
        forwarded += data
    }

    override fun completed(
        session: NSURLSession,
        task: NSURLSessionTask,
        error: NSError?,
    ) {
        errors += error
    }
}

internal fun withIosSessionGuard(block: (IosSessionGuardFixture) -> Unit) {
    val fixture = IosSessionGuardFixture()
    try {
        block(fixture)
    } finally {
        fixture.close()
    }
}

internal fun withIosSessionEngineOwner(block: (ComplaintSessionEngineOwner) -> Unit) {
    val owner = assertNotNull(createIosComplaintSessionEngineOwner(Url(IOS_SESSION_TEST_URL)))
    try {
        block(owner)
    } finally {
        owner.close()
    }
}

internal fun withIosEnrollmentGuard(block: (IosSessionGuardFixture) -> Unit) {
    val fixture =
        IosSessionGuardFixture(
            IosComplaintInstallationPolicy.Enrollment(
                assertNotNull(iosComplaintEnrollmentTarget(Url(IOS_ENROLLMENT_TEST_URL))),
            ),
        )
    try {
        block(fixture)
    } finally {
        fixture.close()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosEnrollmentTestRequest(bootstrap: Boolean = false): NSMutableURLRequest =
    iosSessionTestRequest(if (bootstrap) IOS_BOOTSTRAP_TEST_URL else IOS_ENROLLMENT_TEST_URL).apply {
        if (bootstrap) {
            setHTTPMethod("GET")
            setHTTPBody(null)
        }
    }

@OptIn(ExperimentalForeignApi::class)
internal fun iosSessionTestRequest(url: String = IOS_SESSION_TEST_URL): NSMutableURLRequest =
    NSMutableURLRequest.requestWithURL(assertNotNull(NSURL.URLWithString(url))).apply {
        setHTTPMethod("POST")
        setHTTPBody(iosSessionTestData(1))
        setValue("identity", "Accept-Encoding")
    }

@OptIn(ExperimentalForeignApi::class)
internal fun iosSessionTestData(size: Int): NSData =
    if (size == 0) {
        NSData.create(bytes = null, length = 0uL)
    } else {
        ByteArray(size).usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
    }

internal const val IOS_SESSION_TEST_URL = "https://example.invalid/api/v1/installations/session"
internal const val IOS_ENROLLMENT_TEST_URL = "https://example.invalid/api/v1/installations"
internal const val IOS_BOOTSTRAP_TEST_URL = "$IOS_ENROLLMENT_TEST_URL/bootstrap"
