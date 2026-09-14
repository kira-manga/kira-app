import UIKit

// Nonshipping leaf-harness bindings ONLY. Production ReaderStrings and ReaderHostViewController
// import ComposeApp; these labels/port declarations let real loader/cell/controller files run without
// loading Kotlin, Firebase or the shipping host. No transport, decode, layout or ownership is faked here.
enum ReaderStrings {
    static let failedToLoadImage = "Failed to load image"
    static let retry = "Retry"
    static let openInWebView = "Open in WebView"
    static let nextChapter = "Next chapter"
    static let lastChapter = "Last chapter"
    static func finished(_ chapter: String) -> String { "Finished \(chapter)" }
}

protocol ReaderChildController: UIViewController {
    var onOpenInWebView: (() -> Void)? { get set }
    func setContent(pages: [ReaderPageItem], rows: [ReaderFeedRowItem])
    func setResume(_ pageIndex: Int)
    func scrollToPage(_ pageIndex: Int, animated: Bool)
    func currentImage() -> UIImage?
}
