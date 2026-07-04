import UIKit

/// A single page in PAGED modes (LTR / RTL / vertical-paged), with **pinch + double-tap zoom** — parity
/// with the Compose reader's `zoomableWithScroll`. The image lives inside a per-page `UIScrollView`
/// (Photos.app containment): when zoomed in, the inner scroll view owns the pan; at 1× it has nothing to
/// scroll so the outer paging collection view owns the swipe. Continuous (webtoon) mode does NOT use this
/// cell — it has no zoom on purpose (a zoom recognizer on the continuous list was the original scroll bug).
final class ReaderPagedCell: UICollectionViewCell, UIScrollViewDelegate {
    static let reuseID = "ReaderPagedCell"

    let scrollView = UIScrollView()
    let imageView = UIImageView()
    private let progressView = ReaderPageProgressView()
    private let errorView = ReaderPageErrorView()
    private var token: String?
    private var pageURL: String?
    private var pageHeaders: [String: String] = [:]
    private var targetWidthPx: CGFloat = 2925

    /// Open the current chapter in the WebView (Cloudflare recovery) — set by the controller/host.
    var onOpenInWebView: (() -> Void)?

    /// Decode above screen width so a zoomed page stays crisp; kept equal to [maxZoom] so the page is
    /// sharp across the whole zoom range. Bounded by the loader's `maxPixelDimension`.
    private let zoomDecodeFactor: CGFloat = 2.5
    private let maxZoom: CGFloat = 2.5

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = .black

        scrollView.delegate = self
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = maxZoom
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.bouncesZoom = true
        scrollView.contentInsetAdjustmentBehavior = .never
        scrollView.frame = contentView.bounds
        scrollView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        contentView.addSubview(scrollView)

        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        imageView.frame = scrollView.bounds
        scrollView.addSubview(imageView)

        progressView.translatesAutoresizingMaskIntoConstraints = false
        errorView.translatesAutoresizingMaskIntoConstraints = false
        errorView.isHidden = true
        errorView.onRetry = { [weak self] in self?.load() }
        errorView.onOpenInWebView = { [weak self] in self?.onOpenInWebView?() }
        contentView.addSubview(progressView)
        contentView.addSubview(errorView)
        NSLayoutConstraint.activate([
            progressView.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            progressView.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            errorView.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            errorView.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            errorView.leadingAnchor.constraint(greaterThanOrEqualTo: contentView.leadingAnchor, constant: 16),
            errorView.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -16),
        ])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func configure(url: String, headers: [String: String], widthPt: CGFloat) {
        self.pageURL = url
        self.pageHeaders = headers
        self.targetWidthPx = widthPt * UIScreen.main.scale * zoomDecodeFactor
        scrollView.setZoomScale(1, animated: false)
        load()
    }

    /// Double-tap toggle: zoom OUT to fit if already zoomed, else zoom IN toward the tapped point. The
    /// point arrives in the cell's coordinate space and is converted into the scroll view (handles the RTL
    /// `scaleX:-1` flip on the cell's contentView correctly).
    func toggleZoom(at pointInCell: CGPoint) {
        if scrollView.zoomScale > scrollView.minimumZoomScale {
            scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
        } else {
            let p = scrollView.convert(pointInCell, from: self)
            let scale = scrollView.maximumZoomScale
            let size = CGSize(width: scrollView.bounds.width / scale, height: scrollView.bounds.height / scale)
            let rect = CGRect(x: p.x - size.width / 2, y: p.y - size.height / 2, width: size.width, height: size.height)
            scrollView.zoom(to: rect, animated: true)
        }
    }

    func resetZoom() {
        if scrollView.zoomScale != scrollView.minimumZoomScale {
            scrollView.setZoomScale(scrollView.minimumZoomScale, animated: false)
        }
    }

    private func load() {
        guard let url = pageURL else { return }
        errorView.isHidden = true
        progressView.startIndeterminate()
        token = ReaderImageLoader.shared.load(
            url: url, headers: pageHeaders, targetWidthPx: targetWidthPx,
            onProgress: { [weak self] fraction in
                guard let self = self, self.pageURL == url, self.imageView.image == nil else { return }
                self.progressView.setFraction(fraction)
            },
            completion: { [weak self] image in
                guard let self = self, self.pageURL == url else { return }
                self.progressView.hide()
                if let image = image {
                    ReaderPerfLog.log("assign", ReaderPerfLog.tail(url)) // expect MAIN, should be trivial
                    self.imageView.image = image
                    self.errorView.isHidden = true
                } else {
                    self.errorView.isHidden = false
                }
            }
        )
    }

    // MARK: - UIScrollViewDelegate (zoom)

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        let insetX = max(0, (scrollView.bounds.width - scrollView.contentSize.width) / 2)
        let insetY = max(0, (scrollView.bounds.height - scrollView.contentSize.height) / 2)
        scrollView.contentInset = UIEdgeInsets(top: insetY, left: insetX, bottom: insetY, right: insetX)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if scrollView.zoomScale == scrollView.minimumZoomScale {
            imageView.frame = scrollView.bounds
            scrollView.contentSize = scrollView.bounds.size
        }
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        if let token = token { ReaderImageLoader.shared.cancel(token: token) }
        token = nil
        pageURL = nil
        onOpenInWebView = nil
        scrollView.setZoomScale(1, animated: false)
        scrollView.contentInset = .zero
        imageView.image = nil
        imageView.frame = scrollView.bounds
        progressView.hide()
        errorView.isHidden = true
    }
}
