import UIKit
import ComposeApp

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

/// Root native reader VC (the object Swift's factory returns to Kotlin's `UIKitViewController`).
///
/// Owns the shared `ReaderNativeSession`: observes `IosReaderSnapshot`, renders the right child
/// (continuous vs paged), overlays UIKit chrome **bars** (top + bottom only — never the middle), and
/// forwards user actions back as intents. All list/append/resume/history/progress logic stays in the
/// shared `ReaderViewModel`; this VC is a renderer. The scroll view sits at z-index 0 and fills the
/// screen, so it always owns the pan; chrome bars float above it at the edges.
final class ReaderHostViewController: UIViewController {
    private let session: ReaderNativeSession
    private let chrome = ReaderChromeBars()
    private let loadingSpinner = UIActivityIndicatorView(style: .large)
    private let errorLabel = UILabel()
    private let retryButton = UIButton(type: .system)
    private lazy var errorStack = UIStackView(arrangedSubviews: [errorLabel, retryButton])
    private var child: ReaderChildController?
    private var currentMode = ""
    private var didResume = false
    private var lastFirstURL: String?
    /// Last applied feed signature — the page/feed arrays are re-mapped + re-sent only when this changes
    /// (append / chapter jump), never on a page-scroll snapshot. `.min` forces a re-send into a new child.
    private var lastFeedSignature: Int = .min
    /// Feed index of the active chapter's first page (from the latest snapshot) — maps a within-chapter
    /// scrubber position back to an absolute page index.
    private var activeChapterStart = 0
    /// Current `ReadingMode.name` from the latest snapshot — drives the picker's checkmark.
    private var currentReadingMode = ""
    /// Active chapter URL + source api from the latest snapshot — for the per-page "Open in WebView".
    private var activeChapterUrl = ""
    private var sourceApi = ""
    private var autoHide: DispatchWorkItem?

    /// `ReadingMode.name`s in picker order; labels come from `ReaderStrings` (localized).
    private let readingModeNames = ["DEFAULT", "RIGHT_TO_LEFT", "LEFT_TO_RIGHT", "VERTICAL", "WEBTOON", "CONTINUOUS_VERTICAL"]

    init(session: ReaderNativeSession) {
        self.session = session
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        // Install the chrome bars first; the scroll view is inserted beneath them (index 0) in ensureChild.
        chrome.install(in: view)
        installLoadingAndError()
        wireChrome()

        session.start(
            onSnapshot: { [weak self] snapshot in self?.apply(snapshot) },
            onShowNotInLibrary: { [weak self] in self?.toast(ReaderStrings.addToLibraryFirst) },
            onShowError: { [weak self] in self?.toast(ReaderStrings.couldntLoadChapter) }
        )
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        session.onScreenResumed()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        session.onScreenPaused()
    }

    deinit { session.close() }

    /// Full-screen loading + error overlay, shown while the chapter is still resolving its image URLs
    /// (no pages yet) — so the reader never looks like an empty/un-padded black screen (#4).
    private func installLoadingAndError() {
        loadingSpinner.color = .white
        loadingSpinner.hidesWhenStopped = true
        loadingSpinner.transform = CGAffineTransform(scaleX: 1.6, y: 1.6)
        loadingSpinner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(loadingSpinner)

        // Localized via the shared compose-resources bridge (ReaderStrings), matching every other
        // reader string — these two were the last hardcoded-English user-facing literals here.
        errorLabel.text = ReaderStrings.couldntLoadChapter
        errorLabel.textColor = .lightGray
        errorLabel.font = .systemFont(ofSize: 15)
        errorLabel.textAlignment = .center
        errorLabel.numberOfLines = 0
        retryButton.setTitle(ReaderStrings.retry, for: .normal)
        retryButton.tintColor = .white
        retryButton.addTarget(self, action: #selector(retryTapped), for: .touchUpInside)
        errorStack.axis = .vertical
        errorStack.spacing = 12
        errorStack.alignment = .center
        errorStack.isHidden = true
        errorStack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(errorStack)

        NSLayoutConstraint.activate([
            loadingSpinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            loadingSpinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            errorStack.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            errorStack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            errorStack.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            errorStack.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24),
        ])
    }

    @objc private func retryTapped() { session.onRetry() }

    // MARK: - Chrome wiring

    private func wireChrome() {
        chrome.onBack = { [weak self] in self?.session.onBackClick() }
        chrome.onToggleBookmark = { [weak self] in self?.session.onToggleBookmark() }
        chrome.onPrevChapter = { [weak self] in self?.session.onPrevChapter() }
        chrome.onNextChapter = { [weak self] in self?.session.onNextChapter() }
        chrome.onShare = { [weak self] in self?.shareCurrentPage() }
        chrome.onPickReadingMode = { [weak self] in self?.presentReadingModePicker() }
        chrome.onSeek = { [weak self] index in
            guard let self = self else { return }
            // The scrubber is scoped to the active chapter (0-based within-chapter); map it back to an
            // absolute feed index so seeking works across appended chapters in continuous mode.
            let feedIndex = self.activeChapterStart + index
            self.child?.scrollToPage(feedIndex, animated: false)
            self.session.onPageChanged(index: Int32(feedIndex))
        }
    }

    @objc private func handleTap() { session.onUiToggle() }

    // MARK: - Snapshot → UI

    private func apply(_ snapshot: IosReaderSnapshot) {
        ReaderPerfLog.mainTask("apply feed=\(snapshot.feedSignature) pages=\(snapshot.pages.count)") {
            applyInner(snapshot)
        }
    }

    private func applyInner(_ snapshot: IosReaderSnapshot) {
        ensureChild(forMode: snapshot.readingMode)

        // Re-map + re-send the page/feed arrays ONLY when the feed actually changed (append / chapter
        // jump) — never on a page-scroll snapshot. This is the hot path while scrolling (every
        // onPageChanged emits a snapshot); doing the O(n) bridge map + setContent here was the iOS-only
        // main-thread cost Android avoids via `remember`. `snapshot.pages.count/.isEmpty` stay O(1).
        let feedSig = Int(snapshot.feedSignature)
        if feedSig != lastFeedSignature {
            lastFeedSignature = feedSig
            let pages: [ReaderPageItem] = snapshot.pages.map { ReaderPageItem(url: $0.url, headers: $0.headers) }
            let rows: [ReaderFeedRowItem] = snapshot.feedRows.map { row in
                row.isBoundary
                    ? .boundary(finished: row.finishedLabel, next: row.nextLabel)
                    : .image(ReaderPageItem(url: row.url, headers: row.headers), pageIndex: Int(row.pageIndex))
            }
            child?.setContent(pages: pages, rows: rows)
            // Resume on first fill AND whenever the chapter changes (next/prev replaces the page set →
            // new first URL). Appends keep the same first URL, so they never re-trigger a jump.
            let firstURL = pages.first?.url
            if firstURL != lastFirstURL {
                lastFirstURL = firstURL
                didResume = false
            }
            if !didResume && !pages.isEmpty {
                didResume = true
                child?.setResume(Int(snapshot.currentPageIndex))
            }
        }

        activeChapterStart = Int(snapshot.activeChapterStartIndex)
        // Keep the paged child's auto-advance gate in sync with the shared state (no next chapter ⇒
        // swiping past the last page just bounces; the boundary/last-chapter panel is continuous-only).
        (child as? PagedReaderViewController)?.canGoNext = snapshot.canGoNext
        currentReadingMode = snapshot.readingMode
        activeChapterUrl = snapshot.activeChapterUrl
        sourceApi = snapshot.sourceApi
        chrome.update(
            title: snapshot.mangaTitle,
            chapter: snapshot.chapterLabel,
            // Per-chapter (segment-scoped) HUD, not flat-feed — so the indicator and scrubber reflect the
            // chapter in view and update immediately on chapter nav (driven by the VM snapshot, not scroll).
            page: Int(snapshot.activeChapterPageNumber),
            count: Int(snapshot.activeChapterPageCount),
            bookmarked: snapshot.isBookmarked,
            canPrev: snapshot.canGoPrev,
            canNext: snapshot.canGoNext
        )
        chrome.setVisible(snapshot.isUiVisible)

        // Full-screen loading / error state while the chapter has no pages yet (#4).
        let initialLoading = snapshot.isInitialLoading
        if initialLoading { loadingSpinner.startAnimating() } else { loadingSpinner.stopAnimating() }
        errorStack.isHidden = !(snapshot.hasError && snapshot.pages.isEmpty)

        if snapshot.isUiVisible { scheduleAutoHide() } else { autoHide?.cancel() }
    }

    private func ensureChild(forMode mode: String) {
        guard mode != currentMode || child == nil else { return }
        currentMode = mode
        child?.willMove(toParent: nil)
        child?.view.removeFromSuperview()
        child?.removeFromParent()

        let onPageChanged: (Int) -> Void = { [weak self] index in
            self?.session.onPageChanged(index: Int32(index))
        }
        let newChild: ReaderChildController
        switch mode {
        case "WEBTOON", "CONTINUOUS_VERTICAL":
            newChild = WebtoonReaderViewController(
                onPageChanged: onPageChanged,
                onReachedEnd: { [weak self] in self?.session.onAppendNextChapter() }
            )
        case "LEFT_TO_RIGHT":
            newChild = PagedReaderViewController(axis: .horizontal, rtl: false, onPageChanged: onPageChanged)
        case "RIGHT_TO_LEFT":
            newChild = PagedReaderViewController(axis: .horizontal, rtl: true, onPageChanged: onPageChanged)
        default: // VERTICAL, DEFAULT
            newChild = PagedReaderViewController(axis: .vertical, rtl: false, onPageChanged: onPageChanged)
        }

        newChild.onOpenInWebView = { [weak self] in
            guard let self = self, !self.activeChapterUrl.isEmpty else { return }
            self.session.onOpenInWebView(url: self.activeChapterUrl, api: self.sourceApi)
        }
        // Paged-only: swiping forward past the last page advances to the next chapter (OnNextChapter
        // replaces the page set — the continuous modes instead APPEND via onReachedEnd above).
        (newChild as? PagedReaderViewController)?.onAdvanceNextChapter = { [weak self] in
            self?.session.onNextChapter()
        }

        addChild(newChild)
        newChild.view.frame = view.bounds
        newChild.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // Beneath the chrome bars (index 0), full-screen — so the scroll view always owns the pan and the
        // middle is never covered by an overlay (the root cause of the prior dead-scroll / dead-tap bug).
        view.insertSubview(newChild.view, at: 0)
        newChild.didMove(toParent: self)
        lastFirstURL = nil // force a resume into the freshly-created child
        lastFeedSignature = .min // force re-sending the feed into the freshly-created child

        // Tap-to-toggle lives on the SCROLL view, never on a gesture wrapping it — so the pan is never
        // stolen. cancelsTouchesInView=false keeps scrolling intact.
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap))
        tap.cancelsTouchesInView = false
        // Let the double-tap-to-zoom win first — otherwise a double-tap would toggle the chrome on its
        // first tap before zooming. Both paged and continuous (webtoon) modes have a zoom double-tap.
        if let paged = newChild as? PagedReaderViewController {
            tap.require(toFail: paged.zoomDoubleTap)
        }
        if let webtoon = newChild as? WebtoonReaderViewController {
            tap.require(toFail: webtoon.zoomDoubleTap)
        }
        newChild.view.addGestureRecognizer(tap)

        child = newChild
        didResume = false
    }

    private func scheduleAutoHide() {
        autoHide?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self = self, self.chrome.visible else { return }
            self.session.onUiToggle()
        }
        autoHide = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }

    /// Reading-mode picker (parity with the Compose `ReadingModeDialog`). Selecting a mode dispatches
    /// `onReadingModeChanged`; the VM persists it, the observer re-emits, and the next snapshot swaps the
    /// child VC (continuous ↔ paged) via `ensureChild`, resuming to the same page.
    private func presentReadingModePicker() {
        let sheet = UIAlertController(title: ReaderStrings.readingMode, message: nil, preferredStyle: .actionSheet)
        for name in readingModeNames {
            let selected = name == currentReadingMode
            let label = ReaderStrings.readingModeLabel(name)
            sheet.addAction(UIAlertAction(title: selected ? "✓ \(label)" : label, style: .default) { [weak self] _ in
                self?.session.onReadingModeChanged(modeName: name)
            })
        }
        sheet.addAction(UIAlertAction(title: ReaderStrings.cancel, style: .cancel))
        // iPad: anchor the popover so presentation doesn't crash.
        if let pop = sheet.popoverPresentationController {
            pop.sourceView = view
            pop.sourceRect = CGRect(x: view.bounds.midX, y: 72, width: 0, height: 0)
            pop.permittedArrowDirections = .up
        }
        present(sheet, animated: true)
    }

    private func shareCurrentPage() {
        guard let image = child?.currentImage() else { return }
        let activity = UIActivityViewController(activityItems: [image], applicationActivities: nil)
        activity.popoverPresentationController?.sourceView = view
        present(activity, animated: true)
    }

    private func toast(_ message: String) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        present(alert, animated: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { alert.dismiss(animated: true) }
    }
}
