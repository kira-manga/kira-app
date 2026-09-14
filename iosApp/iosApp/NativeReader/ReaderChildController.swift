import UIKit

/// Common surface the host drives for either reader mode. `pages` is the flat page list (paged modes
/// render it); `rows` is the interleaved feed with inline chapter boundaries (continuous modes render it).
/// Indices in `setResume`/`scrollToPage` are PAGE indices (continuous controllers map to feed positions).
protocol ReaderChildController: UIViewController {
    /// Invoked from a page's error slot to open the current chapter in the WebView (Cloudflare recovery).
    var onOpenInWebView: (() -> Void)? { get set }
    func setContent(pages: [ReaderPageItem], rows: [ReaderFeedRowItem])
    func setResume(_ pageIndex: Int)
    func scrollToPage(_ pageIndex: Int, animated: Bool)
    func currentImage() -> UIImage?
}
