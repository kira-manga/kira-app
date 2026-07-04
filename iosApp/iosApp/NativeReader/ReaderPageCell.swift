import UIKit

/// One page in the continuous (webtoon) feed. Plain `UIImageView` so the OS owns rendering; reports its
/// decoded **aspect ratio** (h/w) so the collection layout can size the cell for the current zoom width.
/// Shows a spinner (or determinate progress ring) while loading and a Retry / Open-in-WebView slot on
/// error. (Paged modes use the separate zoomable `ReaderPagedCell`.)
final class ReaderPageCell: UICollectionViewCell {
    static let reuseID = "ReaderPageCell"

    let imageView = UIImageView()
    private let progressView = ReaderPageProgressView()
    private let errorView = ReaderPageErrorView()
    private var token: String?
    private var pageURL: String?
    private var pageHeaders: [String: String] = [:]
    private var targetWidthPx: CGFloat = 1170
    private var onAspect: ((CGFloat) -> Void)?

    /// Open the current chapter in the WebView (Cloudflare recovery) — set by the controller/host.
    var onOpenInWebView: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = .black
        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        progressView.translatesAutoresizingMaskIntoConstraints = false
        errorView.translatesAutoresizingMaskIntoConstraints = false
        errorView.isHidden = true
        errorView.onRetry = { [weak self] in self?.load() }
        errorView.onOpenInWebView = { [weak self] in self?.onOpenInWebView?() }
        contentView.addSubview(imageView)
        contentView.addSubview(progressView)
        contentView.addSubview(errorView)
        NSLayoutConstraint.activate([
            imageView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            imageView.topAnchor.constraint(equalTo: contentView.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
            progressView.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            progressView.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            errorView.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            errorView.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            errorView.leadingAnchor.constraint(greaterThanOrEqualTo: contentView.leadingAnchor, constant: 16),
            errorView.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -16),
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    /// - Parameters:
    ///   - widthPt: the current cell width in points (the zoomed strip width). The image decodes at
    ///     `widthPt * screenScale`, so zooming in re-decodes sharper (no blur) while 1× stays light.
    ///   - onAspect: reports the decoded aspect ratio (height / width) so the layout can size the cell.
    func configure(url: String, headers: [String: String], widthPt: CGFloat, onAspect: ((CGFloat) -> Void)?) {
        self.pageURL = url
        self.pageHeaders = headers
        self.targetWidthPx = widthPt * UIScreen.main.scale
        self.onAspect = onAspect
        load()
    }

    private func load() {
        guard let url = pageURL else { return }
        errorView.isHidden = true
        // No-flash reload: keep any existing image on screen while a higher-res (zoom) decode is fetched.
        if imageView.image == nil { progressView.startIndeterminate() } else { progressView.hide() }
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
                    if image.size.width > 0 { self.onAspect?(image.size.height / image.size.width) }
                } else if self.imageView.image == nil {
                    self.errorView.isHidden = false
                }
            }
        )
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        if let token = token { ReaderImageLoader.shared.cancel(token: token) }
        token = nil
        pageURL = nil
        onAspect = nil
        onOpenInWebView = nil
        imageView.image = nil
        progressView.hide()
        errorView.isHidden = true
    }
}
