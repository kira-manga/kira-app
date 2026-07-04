import UIKit

/// One immutable reader page handed to the native UI (decoupled from the Kotlin DTO).
struct ReaderPageItem: Equatable {
    let url: String
    let headers: [String: String]
}

/// One row of the continuous feed: an image (with its absolute page index) or a chapter boundary panel.
/// Equatable so `setContent` can detect a same-URL/new-HEADERS resend (post-Cloudflare-solve
/// re-fetch) that the position-preserving key diff deliberately ignores.
enum ReaderFeedRowItem: Equatable {
    case image(ReaderPageItem, pageIndex: Int)
    /// `next == nil` ⇒ terminal "last chapter" panel.
    case boundary(finished: String, next: String?)
}

/// Continuous WEBTOON / CONTINUOUS_VERTICAL reader.
///
/// A vertical `UICollectionView` (single full-width column, variable heights) renders an interleaved
/// **feed** of image rows + inline chapter-boundary panels (parity with the Compose `buildReaderFeed`).
///
/// **Zoom ("magnify & keep reading").** The collection view is nested in an outer **horizontal**
/// `UIScrollView`. Zooming makes the strip genuinely **wider** (cells wider + proportionally taller), so:
///   - the collection view keeps scrolling **vertically** with its own native pan — no transform, so
///     finger-tracking stays 1:1 and virtualization is preserved (this is what avoids the well-known
///     "pinch-zoom causes random scrolling" webtoon bug), and
///   - the outer scroll view pans **horizontally** to reveal the wider page (orthogonal axes don't fight).
/// While a pinch is active BOTH scroll views are disabled, so the scale gesture never races the scroll.
/// Pages re-decode at the zoomed width on pinch-end for sharpness; 1× stays light (no scroll regression).
final class WebtoonReaderViewController: UIViewController,
                                         UICollectionViewDataSource,
                                         UICollectionViewDelegateFlowLayout,
                                         UICollectionViewDataSourcePrefetching,
                                         ReaderChildController {

    private let onPageChanged: (Int) -> Void
    private let onReachedEnd: () -> Void
    var onOpenInWebView: (() -> Void)?

    private var rows: [ReaderFeedRowItem] = []
    private var feedToPage: [Int] = []
    private var pageToFeed: [Int] = []
    /// Decoded aspect ratio (height / width) keyed by FEED position (image rows only). Cell height is
    /// `contentWidth * aspect`, so it rescales with zoom.
    private var aspects: [Int: CGFloat] = [:]
    /// Per-URL prefetch cancel tokens so cancelPrefetching cancels only the prefetch, never a coalesced
    /// visible cell (see prefetch / cancelPrefetching).
    private var prefetchTokens: [String: String] = [:]
    /// Bumped on each content change to abandon a stale background aspect-seed pass.
    private var aspectSeedGeneration = 0
    /// The saved page to restore to (set by setResume). The reload-time scroll uses placeholder heights;
    /// the aspect-seed pass re-applies this once cells are sized from real dimensions.
    private var restoreTargetPage: Int?
    /// True once the user has driven a scroll — suppresses the post-seed restore so we never yank back.
    private var didUserScroll = false
    private var lastReportedPage = -1
    private var reachedEndLatched = false
    private var pendingResume: Int?

    private let boundaryHeight: CGFloat = 220

    // Zoom
    private let hScroll = UIScrollView()
    let zoomDoubleTap = UITapGestureRecognizer()
    private let zoomPinch = UIPinchGestureRecognizer()
    private var zoomScale: CGFloat = 1
    private let maxZoom: CGFloat = 2.5
    private var lastBaseWidth: CGFloat = 0
    private var lastViewportH: CGFloat = 0
    // Pinch anchoring
    private var pinchStartZoom: CGFloat = 1
    private var pinchAnchorPos = 0
    private var pinchAnchorFraction: CGFloat = 0
    private var pinchFocalScreenX: CGFloat = 0
    private var pinchFocalContentX: CGFloat = 0

    private let layout: UICollectionViewFlowLayout = {
        let l = UICollectionViewFlowLayout()
        l.scrollDirection = .vertical
        l.minimumLineSpacing = 0
        l.minimumInteritemSpacing = 0
        return l
    }()
    private lazy var collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)

    init(onPageChanged: @escaping (Int) -> Void, onReachedEnd: @escaping () -> Void) {
        self.onPageChanged = onPageChanged
        self.onReachedEnd = onReachedEnd
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        hScroll.showsHorizontalScrollIndicator = false
        hScroll.showsVerticalScrollIndicator = false
        hScroll.contentInsetAdjustmentBehavior = .never
        hScroll.bounces = false
        hScroll.isScrollEnabled = false // enabled only when zoomed in
        hScroll.frame = view.bounds
        hScroll.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(hScroll)

        collectionView.backgroundColor = .black
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.prefetchDataSource = self
        collectionView.contentInsetAdjustmentBehavior = .never
        collectionView.register(ReaderPageCell.self, forCellWithReuseIdentifier: ReaderPageCell.reuseID)
        collectionView.register(ReaderBoundaryCell.self, forCellWithReuseIdentifier: ReaderBoundaryCell.reuseID)
        hScroll.addSubview(collectionView)

        zoomPinch.addTarget(self, action: #selector(handlePinch(_:)))
        hScroll.addGestureRecognizer(zoomPinch)
        zoomDoubleTap.numberOfTapsRequired = 2
        zoomDoubleTap.addTarget(self, action: #selector(handleDoubleTap(_:)))
        hScroll.addGestureRecognizer(zoomDoubleTap)

        applyLayoutSizes()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if abs(lastBaseWidth - view.bounds.width) > 0.5 || abs(lastViewportH - view.bounds.height) > 0.5 {
            applyLayoutSizes()
        }
    }

    private var baseWidth: CGFloat { max(view.bounds.width, 1) }
    private var viewportH: CGFloat { max(view.bounds.height, 1) }
    private var contentWidth: CGFloat { max(baseWidth * zoomScale, 1) }
    private var defaultAspect: CGFloat { viewportH / baseWidth }

    private func applyLayoutSizes() {
        lastBaseWidth = view.bounds.width
        lastViewportH = view.bounds.height
        hScroll.frame = view.bounds
        collectionView.frame = CGRect(x: 0, y: 0, width: contentWidth, height: viewportH)
        hScroll.contentSize = CGSize(width: contentWidth, height: viewportH)
        hScroll.isScrollEnabled = zoomScale > 1.001
        layout.invalidateLayout()
    }

    // MARK: - Data

    func setContent(pages: [ReaderPageItem], rows newRows: [ReaderFeedRowItem]) {
        let oldKeys = rows.map(Self.key)
        let newKeys = newRows.map(Self.key)
        let isAppend = !oldKeys.isEmpty &&
            newKeys.count > oldKeys.count &&
            Array(newKeys.prefix(oldKeys.count)) == oldKeys
        if isAppend {
            let firstNew = rows.count
            rows = newRows
            rebuildMaps()
            let added = (firstNew..<newRows.count).map { IndexPath(item: $0, section: 0) }
            collectionView.performBatchUpdates({ collectionView.insertItems(at: added) })
        } else if newKeys != oldKeys {
            rows = newRows
            aspects.removeAll()
            prefetchTokens.removeAll()
            didUserScroll = false
            rebuildMaps()
            collectionView.reloadData()
            if let resume = pendingResume { pendingResume = nil; restoreTargetPage = resume; scrollToPage(resume, animated: false) }
            // Size cells from real (local) dimensions so hundreds of small pages don't start at the
            // full-screen placeholder (which forces a shrink + reflow per page), then re-apply the saved
            // page once sized (the scroll above / setResume ran against placeholder heights). Reload only:
            // appended pages are below the viewport and size on decode (anchored), so no seed is needed.
            seedLocalAspects()
        } else if newRows != rows {
            // Audit P1: identical keys but different row CONTENT — the only field outside the key
            // is the per-page HEADERS map. This is the post-Cloudflare-solve re-fetch: the bridge
            // resends the SAME urls with FRESH cookies, which the key diff above deliberately
            // ignores (it exists to preserve scroll position). Swap the backing rows so every
            // future dequeue binds the fresh headers, and rebind the visible cells so an errored
            // page retries with them instead of 403-ing on the stale cookie forever. No layout
            // change (equal keys ⇒ equal rows/heights), so position is preserved.
            rows = newRows
            rebuildMaps()
            collectionView.reloadItems(at: collectionView.indexPathsForVisibleItems)
        }
        reachedEndLatched = false
    }

    private func rebuildMaps() {
        feedToPage = [Int](repeating: 0, count: rows.count)
        var pagePairs: [(page: Int, pos: Int)] = []
        var lastPage = 0
        for (pos, row) in rows.enumerated() {
            switch row {
            case .image(_, let pageIndex):
                feedToPage[pos] = pageIndex
                pagePairs.append((pageIndex, pos))
                lastPage = pageIndex
            case .boundary:
                feedToPage[pos] = lastPage
            }
        }
        let maxPage = pagePairs.map { $0.page }.max() ?? -1
        pageToFeed = [Int](repeating: 0, count: maxPage + 1)
        for pair in pagePairs { pageToFeed[pair.page] = pair.pos }
    }

    func setResume(_ pageIndex: Int) {
        restoreTargetPage = pageIndex
        if rows.isEmpty { pendingResume = pageIndex } else { scrollToPage(pageIndex, animated: false) }
    }

    func scrollToPage(_ pageIndex: Int, animated: Bool) {
        guard pageIndex >= 0, pageIndex < pageToFeed.count else { return }
        lastReportedPage = pageIndex
        collectionView.scrollToItem(at: IndexPath(item: pageToFeed[pageIndex], section: 0), at: .top, animated: animated)
    }

    func currentImage() -> UIImage? {
        let point = CGPoint(x: collectionView.bounds.midX, y: collectionView.contentOffset.y + viewportH / 2)
        if let ip = collectionView.indexPathForItem(at: point),
           let cell = collectionView.cellForItem(at: ip) as? ReaderPageCell {
            return cell.imageView.image
        }
        return (collectionView.visibleCells.compactMap { $0 as? ReaderPageCell }.first)?.imageView.image
    }

    // MARK: - UICollectionViewDataSource

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { rows.count }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        switch rows[indexPath.item] {
        case .image(let page, _):
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: ReaderPageCell.reuseID, for: indexPath) as! ReaderPageCell
            let pos = indexPath.item
            cell.onOpenInWebView = onOpenInWebView
            cell.configure(url: page.url, headers: page.headers, widthPt: contentWidth) { [weak self] aspect in
                self?.updateAspect(pos, aspect)
            }
            return cell
        case .boundary(let finished, let next):
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: ReaderBoundaryCell.reuseID, for: indexPath) as! ReaderBoundaryCell
            cell.configure(finished: finished, next: next)
            return cell
        }
    }

    // MARK: - Layout (variable per-row height, scaled by zoom)

    func collectionView(_ collectionView: UICollectionView, layout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        let cw = contentWidth
        switch rows[indexPath.item] {
        case .image: return CGSize(width: cw, height: cw * (aspects[indexPath.item] ?? defaultAspect))
        case .boundary: return CGSize(width: cw, height: boundaryHeight)
        }
    }

    private func updateAspect(_ pos: Int, _ aspect: CGFloat) {
        guard aspect > 0, abs((aspects[pos] ?? -1) - aspect) > 0.001 else { return }
        let oldAspect = aspects[pos] ?? defaultAspect   // what sizeForItemAt is currently using for pos
        aspects[pos] = aspect
        // Defer off cellForItemAt: the aspect can arrive SYNCHRONOUSLY from inside cellForItemAt when the
        // image is already cached (local/downloaded fast decode, prefetched pages, or the header-seed
        // pass), and invalidating the layout *during* cellForItemAt is dropped by UICollectionView — the
        // cell would keep its placeholder height until a re-scroll.
        //
        // On the hop we also ANCHOR the scroll: if the resizing cell is entirely ABOVE the viewport,
        // compensate contentOffset by the height delta so a page loading/shrinking above never moves what
        // the user is looking at — the upward-scroll "snap to a later page" bug that this chapter's ~360
        // small pages triggered constantly (each shrank from a full-screen placeholder to its real size).
        DispatchQueue.main.async { [weak self] in
            guard let self = self, pos < self.rows.count, self.aspects[pos] == aspect else { return }
            let ip = IndexPath(item: pos, section: 0)
            let ctx = UICollectionViewFlowLayoutInvalidationContext()
            ctx.invalidateItems(at: [ip])
            if let frame = self.layout.layoutAttributesForItem(at: ip)?.frame,
               frame.maxY <= self.collectionView.contentOffset.y {
                let cw = self.contentWidth
                ctx.contentOffsetAdjustment = CGPoint(x: 0, y: cw * aspect - cw * oldAspect)
            }
            self.layout.invalidateLayout(with: ctx)
        }
    }

    /// Read LOCAL image headers (cheap ImageIO read, no pixel decode) on a background pass, then apply the
    /// real aspects in ONE batch and re-apply the saved page. Chapter-(re)load only. This does two things
    /// the placeholder path can't:
    ///   1. hundreds of small pages size from real dimensions instead of the full-screen `defaultAspect`
    ///      placeholder (no per-page shrink storm), and
    ///   2. the saved-page restore lands on the correct offset — the reload-time scroll ran against
    ///      placeholder heights, so it pointed at the wrong page until this re-applies it against real sizes.
    /// It seeds SILENTLY (no per-item anchored invalidation) so the batch can't fight the restore; the one
    /// full invalidate + `scrollToPage` is authoritative. Remote pages are skipped (dimensions need the
    /// download; they size on decode, anchored by [updateAspect]). Generation-guarded, and the restore is
    /// skipped once the user has started scrolling (so we never yank them back).
    private func seedLocalAspects() {
        aspectSeedGeneration += 1
        let generation = aspectSeedGeneration
        let snapshot = rows
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var seeded: [Int: CGFloat] = [:]
            for (pos, row) in snapshot.enumerated() {
                if case .image(let item, _) = row, let aspect = ReaderImageLoader.shared.localAspect(item.url) {
                    seeded[pos] = aspect
                }
            }
            DispatchQueue.main.async {
                guard let self = self, self.aspectSeedGeneration == generation else { return }
                // Don't clobber an aspect a visible cell already reported from its decode.
                for (pos, asp) in seeded where pos < self.rows.count && self.aspects[pos] == nil {
                    self.aspects[pos] = asp
                }
                self.layout.invalidateLayout()
                if !self.didUserScroll, let resume = self.restoreTargetPage {
                    self.scrollToPage(resume, animated: false)
                }
            }
        }
    }

    // MARK: - Prefetch

    func collectionView(_ collectionView: UICollectionView, prefetchItemsAt indexPaths: [IndexPath]) {
        let target = contentWidth * UIScreen.main.scale
        for ip in indexPaths where ip.item < rows.count {
            if case .image(let page, _) = rows[ip.item] {
                // Keep the per-request token so cancelPrefetching cancels only THIS prefetch — never a
                // visible cell coalesced onto the same URL (dropping its callback left the cell black).
                let t = ReaderImageLoader.shared.load(url: page.url, headers: page.headers, targetWidthPx: target) { _ in }
                if !t.isEmpty { prefetchTokens[page.url] = t }
            }
        }
    }

    func collectionView(_ collectionView: UICollectionView, cancelPrefetchingForItemsAt indexPaths: [IndexPath]) {
        for ip in indexPaths where ip.item < rows.count {
            if case .image(let page, _) = rows[ip.item],
               let t = prefetchTokens.removeValue(forKey: page.url) {
                ReaderImageLoader.shared.cancel(token: t)
            }
        }
    }

    // MARK: - Zoom (pinch + double-tap)

    @objc private func handlePinch(_ g: UIPinchGestureRecognizer) {
        switch g.state {
        case .began:
            beginZoomGesture(focal: g.location(in: view))
        case .changed:
            applyZoom(min(max(pinchStartZoom * g.scale, 1), maxZoom))
        case .ended, .cancelled, .failed:
            endZoomGesture()
        default:
            break
        }
    }

    @objc private func handleDoubleTap(_ g: UITapGestureRecognizer) {
        beginZoomGesture(focal: g.location(in: view))
        applyZoom(zoomScale > 1.001 ? 1 : maxZoom)
        endZoomGesture()
    }

    private func beginZoomGesture(focal: CGPoint) {
        // Disable both scrollers for the duration of the gesture so the scale never races a scroll
        // (the root cause of the classic webtoon "random scrolling on pinch" bug).
        collectionView.isScrollEnabled = false
        hScroll.isScrollEnabled = false
        pinchStartZoom = zoomScale
        let anchor = verticalAnchor()
        pinchAnchorPos = anchor.pos
        pinchAnchorFraction = anchor.fraction
        pinchFocalScreenX = focal.x
        pinchFocalContentX = hScroll.contentOffset.x + focal.x
    }

    private func endZoomGesture() {
        collectionView.isScrollEnabled = true
        hScroll.isScrollEnabled = zoomScale > 1.001
        resharpenVisibleCells()
    }

    private func applyZoom(_ newZoom: CGFloat) {
        let startZoom = max(pinchStartZoom, 0.0001)
        zoomScale = newZoom
        applyLayoutSizes()
        collectionView.layoutIfNeeded()
        // Keep the vertical-center content fixed.
        if pinchAnchorPos < rows.count,
           let attr = layout.layoutAttributesForItem(at: IndexPath(item: pinchAnchorPos, section: 0)) {
            let targetCenterY = attr.frame.minY + pinchAnchorFraction * attr.frame.height
            let maxY = max(0, collectionView.contentSize.height - viewportH)
            collectionView.contentOffset.y = min(max(targetCenterY - viewportH / 2, 0), maxY)
        }
        // Keep the horizontal focal point fixed.
        let newContentX = pinchFocalContentX * (newZoom / startZoom)
        let maxX = max(0, contentWidth - baseWidth)
        hScroll.contentOffset.x = min(max(newContentX - pinchFocalScreenX, 0), maxX)
    }

    private func verticalAnchor() -> (pos: Int, fraction: CGFloat) {
        let centerY = collectionView.contentOffset.y + viewportH / 2
        if let ip = collectionView.indexPathForItem(at: CGPoint(x: contentWidth / 2, y: centerY)),
           let attr = layout.layoutAttributesForItem(at: ip) {
            let f = (centerY - attr.frame.minY) / max(attr.frame.height, 1)
            return (ip.item, max(0, min(1, f)))
        }
        return (0, 0)
    }

    private func resharpenVisibleCells() {
        let cw = contentWidth
        for cell in collectionView.visibleCells {
            guard let pc = cell as? ReaderPageCell, let ip = collectionView.indexPath(for: cell) else { continue }
            if case .image(let page, _) = rows[ip.item] {
                pc.configure(url: page.url, headers: page.headers, widthPt: cw) { [weak self] aspect in
                    self?.updateAspect(ip.item, aspect)
                }
            }
        }
    }

    // MARK: - Scroll → page tracking + reach-end (vertical collection view only)

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard scrollView === collectionView, !rows.isEmpty else { return }
        // Only report the visible page for USER-driven scrolls (drag/momentum) — programmatic scrolls,
        // the reloadData offset reset, reflow, and zoom all move the offset too. Mirrors Compose's guard.
        let userDriven = scrollView.isDragging || scrollView.isDecelerating
        if userDriven { didUserScroll = true }   // user took over → stop re-applying the saved-page restore
        let topY = scrollView.contentOffset.y + 1
        if userDriven,
           let ip = collectionView.indexPathForItem(at: CGPoint(x: contentWidth / 2, y: topY)),
           ip.item < feedToPage.count {
            let page = feedToPage[ip.item]
            if page != lastReportedPage {
                lastReportedPage = page
                onPageChanged(page)
            }
        }
        let distanceToBottom = scrollView.contentSize.height - (scrollView.contentOffset.y + scrollView.bounds.height)
        if distanceToBottom < viewportH * 0.5 {
            if !reachedEndLatched {
                reachedEndLatched = true
                onReachedEnd()
            }
        } else if distanceToBottom > viewportH {
            reachedEndLatched = false
        }
    }

    private static func key(_ r: ReaderFeedRowItem) -> String {
        switch r {
        case .image(let item, _): return "i:" + item.url
        case .boundary(let f, let n): return "b:\(f)>\(n ?? "·")"
        }
    }
}
