package me.manga.kira.data.remote.complaint

import io.ktor.client.engine.darwin.KtorNSURLSessionDelegate
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.darwin.NSObject

/** Composition with Ktor's final delegate, never inheritance or access to its internal handler map. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal class IosComplaintSessionDelegate(
    private val guard: IosComplaintSessionReceiveGuard,
    private val ktor: KtorNSURLSessionDelegate,
) : NSObject(),
    NSURLSessionDataDelegateProtocol {
    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        val allowed = guard.admit(session, dataTask, didReceiveResponse)
        completionHandler(if (allowed) NSURLSessionResponseAllow else NSURLSessionResponseCancel)
    }

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData,
    ) {
        guard.receive(session, dataTask, didReceiveData)
    }

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        guard.complete(session, task, didCompleteWithError)
    }

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        ktor.URLSession(session, task, willPerformHTTPRedirection, newRequest, completionHandler)
    }

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
    ) {
        ktor.URLSession(session, task, didReceiveChallenge, completionHandler)
    }

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        didBecomeInvalidWithError: NSError?,
    ) {
        guard.close(session)
    }
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal class IosComplaintSessionKtorCallbacks(
    private val ktor: KtorNSURLSessionDelegate,
) : IosComplaintSessionCallbacks {
    override fun received(
        session: NSURLSession,
        task: NSURLSessionDataTask,
        data: NSData,
    ) {
        ktor.URLSession(session, task, data)
    }

    override fun completed(
        session: NSURLSession,
        task: NSURLSessionTask,
        error: NSError?,
    ) {
        ktor.URLSession(session, task, error)
    }
}
