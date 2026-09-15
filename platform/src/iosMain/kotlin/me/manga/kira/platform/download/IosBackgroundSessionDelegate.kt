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
 * pattern). Forwards each callback to the owning transport, which is a Koin singleton living for the
 * whole app — so the transport↔session↔delegate retain cycle is intentional and harmless.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosBackgroundSessionDelegate(
    private val transport: IosBackgroundTransport,
) : NSObject(),
    NSURLSessionDownloadDelegateProtocol {
    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) {
        transport.handleFinishedDownload(downloadTask, didFinishDownloadingToURL)
    }

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        transport.handleWroteData(downloadTask, didWriteData, totalBytesWritten, totalBytesExpectedToWrite)
    }

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        transport.handleCompleted(task, didCompleteWithError)
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        transport.handleFinishedEvents()
    }
}
