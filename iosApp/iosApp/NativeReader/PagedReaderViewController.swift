import UIKit

/// Paged reader for DEFAULT / VERTICAL (vertical paging) and LEFT_TO_RIGHT / RIGHT_TO_LEFT (horizontal
/// paging). One page per swipe, `ContentScale.Fit` (whole page in the viewport). RTL is a visual flip
/// (`transform = scaleX:-1` on the collection, counter-flipped per cell) so the page **index space stays
/// canonical** — matching the Compose `HorizontalPager(reverseLayout=true)` semantics. Chapter advance is
/// driven by the chrome's prev/next buttons, plus auto-advance when the user swipes forward PAST the last
/// page and the shared reader state has a next chapter (see [canGoNext] / [onAdvanceNextChapter]).
final class PagedReaderViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout, ReaderChildController {
    enum Axis { case horizontal, vertical }

    private let axis: Axis
    private let rtl: Bool
    private let onPageChanged: (Int) -> Void
    var onOpenInWebView: (() -> Void)?

    private var pages: [ReaderPageItem] = []
    private var lastReportedPage = -1
    private var pendingResume: Int?

    /// Set from the host snapshot: whether the shared reader state has a next chapter to advance to.
    var canGoNext: Bool = false
    /// Invoked when the user swipes forward past the last page while [canGoNext]; wired to OnNextChapter.
    var onAdvanceNextChapter: (() -> Void)?
    /// One-shot guard so a forward-overscroll past the end fires the advance once (reset when new content arrives).
    private var advanceTriggered = false

    /// Double-tap-to-zoom recognizer (exposed so the host's single-tap-to-toggle-chrome can require it to
    /// fail — otherwise a double-tap would also toggle the chrome on its first tap).
    let zoomDoubleTap = UITapGestureRecognizer()

    private let layout = UICollectionViewFlowLayout()
    private lazy var collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)

    init(axis: Axis, rtl: Bool, onPageChanged: @escaping (Int) -> Void) {
        self.axis = axis
        self.rtl = rtl
        self.onPageChanged = onPageChanged
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        layout.scrollDirection = (axis == .horizontal) ? .horizontal : .vertical
        layout.minimumLineSpacing = 0
        layout.minimumInteritemSpacing = 0
        collectionView.backgroundColor = .black
        collectionView.isPagingEnabled = true
        collectionView.showsHorizontalScrollIndicator = false
        collectionView.showsVerticalScrollIndicator = false
        collectionView.contentInsetAdjustmentBehavior = .never
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.register(ReaderPagedCell.self, forCellWithReuseIdentifier: ReaderPagedCell.reuseID)
        collectionView.frame = view.bounds
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        if axis == .horizontal && rtl {
            collectionView.transform = CGAffineTransform(scaleX: -1, y: 1)
        }
        view.addSubview(collectionView)

        zoomDoubleTap.numberOfTapsRequired = 2
        zoomDoubleTap.addTarget(self, action: #selector(handleDoubleTap(_:)))
        collectionView.addGestureRecognizer(zoomDoubleTap)
    }

    @objc private func handleDoubleTap(_ g: UITapGestureRecognizer) {
        let loc = g.location(in: collectionView)
        guard let ip = collectionView.indexPathForItem(at: loc),
              let cell = collectionView.cellForItem(at: ip) as? ReaderPagedCell else { return }
        cell.toggleZoom(at: g.location(in: cell))
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        layout.invalidateLayout()
    }

    // Paged modes render the flat page list; the interleaved feed (boundary panels) is continuous-only.
    func setContent(pages newPages: [ReaderPageItem], rows: [ReaderFeedRowItem]) {
        // Full-item equality (ReaderPageItem includes HEADERS): a no-op resend is still dropped,
        // but a post-Cloudflare-solve re-fetch — SAME urls, FRESH cookies — must rebind (audit P1:
        // the old url-only guard kept errored cells 403-ing on stale cookies until reopen).
        guard newPages != pages else { return }
        let urlsChanged = newPages.map({ $0.url }) != pages.map({ $0.url })
        pages = newPages
        if urlsChanged {
            advanceTriggered = false // new chapter/content → re-arm the swipe-past-last-page advance
            collectionView.reloadData()
            if let resume = pendingResume { pendingResume = nil; scrollToPage(resume, animated: false) }
        } else {
            // Header-only refresh: rebind the visible cells (off-screen cells bind fresh headers
            // on dequeue); keep position and the advance latch — the content didn't change.
            collectionView.reloadItems(at: collectionView.indexPathsForVisibleItems)
        }
    }

    func setResume(_ index: Int) {
        if pages.isEmpty { pendingResume = index } else { scrollToPage(index, animated: false) }
    }

    func scrollToPage(_ index: Int, animated: Bool) {
        guard index >= 0, index < pages.count else { return }
        // Sync the current-page marker so a programmatic resume/seek doesn't get re-reported as a change.
        lastReportedPage = index
        let pos: UICollectionView.ScrollPosition = (axis == .horizontal)
            ? (rtl ? .right : .left)
            : .top
        collectionView.scrollToItem(at: IndexPath(item: index, section: 0), at: pos, animated: animated)
    }

    func currentImage() -> UIImage? {
        // Mid-transition two cells are visible and `visibleCells.first` may be the OUTGOING page —
        // share would then capture the neighbor. Pick the cell nearest the viewport center instead.
        let center = CGPoint(
            x: collectionView.contentOffset.x + collectionView.bounds.width / 2,
            y: collectionView.contentOffset.y + collectionView.bounds.height / 2
        )
        let nearest = collectionView.visibleCells.min { a, b in
            hypot(a.center.x - center.x, a.center.y - center.y) <
                hypot(b.center.x - center.x, b.center.y - center.y)
        }
        return (nearest as? ReaderPagedCell)?.imageView.image
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { pages.count }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: ReaderPagedCell.reuseID, for: indexPath) as! ReaderPagedCell
        cell.contentView.transform = (axis == .horizontal && rtl) ? CGAffineTransform(scaleX: -1, y: 1) : .identity
        let page = pages[indexPath.item]
        cell.onOpenInWebView = onOpenInWebView
        cell.configure(url: page.url, headers: page.headers, widthPt: collectionView.bounds.width)
        return cell
    }

    // Reset a page's zoom once it scrolls off-screen, so returning to it starts at fit (matches Compose's
    // per-page zoom state).
    func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        (cell as? ReaderPagedCell)?.resetZoom()
    }

    func collectionView(_ collectionView: UICollectionView, layout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        CGSize(width: collectionView.bounds.width, height: collectionView.bounds.height)
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { reportPage() }
    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) { reportPage() }

    /// Auto-advance: when the user lifts after dragging forward PAST the trailing edge (the canonical last
    /// page is always at `maxOffset` — the RTL `scaleX:-1` is purely visual, so this is correct for LTR,
    /// RTL, and vertical paging alike), and the shared state has a next chapter, advance to it. One-shot
    /// per content set; only fires on a deliberate over-drag (≥30% of the viewport), never on a
    /// programmatic resume/seek (those don't end user dragging) or a normal page-to-page swipe.
    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        // A drag that ends EXACTLY at a page boundary settles with decelerate=false, so neither
        // didEndDecelerating nor didEndScrollingAnimation fires — report the page here or the
        // progress stays one page stale until the next scroll event.
        if !decelerate { reportPage() }
        guard canGoNext, !advanceTriggered, !pages.isEmpty else { return }
        let offset: CGFloat
        let maxOffset: CGFloat
        let viewport: CGFloat
        if axis == .horizontal {
            offset = scrollView.contentOffset.x
            maxOffset = scrollView.contentSize.width - scrollView.bounds.width
            viewport = scrollView.bounds.width
        } else {
            offset = scrollView.contentOffset.y
            maxOffset = scrollView.contentSize.height - scrollView.bounds.height
            viewport = scrollView.bounds.height
        }
        if viewport > 0, offset - maxOffset > viewport * 0.3 {
            advanceTriggered = true
            onAdvanceNextChapter?()
        }
    }

    private func reportPage() {
        guard !pages.isEmpty else { return }
        // contentOffset → canonical page index (the RTL transform is purely visual).
        let raw: Int = (axis == .horizontal)
            ? Int((collectionView.contentOffset.x / max(collectionView.bounds.width, 1)).rounded())
            : Int((collectionView.contentOffset.y / max(collectionView.bounds.height, 1)).rounded())
        let clamped = max(0, min(raw, pages.count - 1))
        if clamped != lastReportedPage {
            lastReportedPage = clamped
            onPageChanged(clamped)
        }
    }
}
