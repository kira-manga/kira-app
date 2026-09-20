package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.darwin.NSObject

/**
 * `NSURLSessionDownloadDelegate` for [IosBackgroundTransport]. A plain `NSObject` subclass (the
 * ObjC-interop requirement; mirrors the in-repo `WebViewDelegate : NSObject(), WKNavigationDelegateProtocol`
 * pattern). Each generation carries its own lifetime into the singleton transport. The session
 * releases its delegate after actual invalidation; a late old callback cannot borrow a successor.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosBackgroundSessionDelegate(
    private val transport: IosBackgroundTransport,
    private val lifetime: IosNativeSessionLifetime,
) : NSObject(),
    NSURLSessionDownloadDelegateProtocol {
    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) {
        transport.handleFinishedDownload(downloadTask, didFinishDownloadingToURL, lifetime = lifetime)
    }

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        transport.handleWroteData(downloadTask, didWriteData, totalBytesWritten, totalBytesExpectedToWrite, lifetime)
    }

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        transport.handleCompleted(task, didCompleteWithError, lifetime)
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        transport.handleFinishedEvents()
    }

    @ObjCSignatureOverride
    override fun URLSession(session: NSURLSession, didBecomeInvalidWithError: NSError?) {
        lifetime.didBecomeInvalid(didBecomeInvalidWithError)
    }
}
